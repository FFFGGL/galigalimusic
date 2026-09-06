package com.galigalimusic.whisper

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaFormat.KEY_PCM_ENCODING
import android.media.MediaFormat.KEY_SAMPLE_RATE
import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频解码 + 分块转写流水线（边解码边出句，不用先解码完整集）。
 *
 * MediaExtractor/MediaCodec 解码任意音频 → 桶平均降采样 16k 单声道 →
 * 每攒够 sliceSec 秒就回调 transcribe → 歌词立即上屏。
 * 长音频流式处理，内存占用恒定；WAV 落盘用于分段随机读取。
 */
object WhisperAudio {

    private const val TAG = "WhisperAudio"
    const val OUT_RATE = 16000

    /**
     * 流式解码并分块转写。
     * @param transcribe 一段 float PCM（16k 单声道）→ 歌词行（时间戳相对该段起点）
     * @param onSlice 每段转写完成的回调（歌词行、完成段数、该段起点样本数）
     * @param isCancelled 外部取消钩子（每轮输入/输出检查一次）
     * @return 总歌词行数
     */
    fun decodeTranscribeStreaming(
        inputPath: String,
        pcmFile: File,
        sliceSec: Int,
        transcribe: (FloatArray) -> List<com.galigalimusic.data.remote.BilibiliApi.LrcLine>,
        onSlice: (List<com.galigalimusic.data.remote.BilibiliApi.LrcLine>, Int, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Int {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        var trackIdx = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIdx = i
                inputFormat = f
                break
            }
        }
        require(trackIdx >= 0) { "没有音频轨" }
        extractor.selectTrack(trackIdx)

        val srcMime = inputFormat!!.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(srcMime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val fos = FileOutputStream(pcmFile)
        val dos = DataOutputStream(BufferedOutputStream(fos))
        writeWavHeader(dos) // 尺寸最后回填

        var totalLines = 0
        var sliceIdx = 0
        var sliceStart = 0L // 本段起点（16k 样本数）
        var pcmFrames = 0L // 已写入的 16k 样本数
        var bucketAcc = 0.0
        var bucketCount = 0
        var consumedFrames = 0L
        var nextBucketEnd = 0.0
        val sliceSamples = sliceSec.toLong() * OUT_RATE

        var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        var outSampleRate = inputFormat.getInteger(KEY_SAMPLE_RATE)
        var outChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        fun emit(frame: Int, channels: Int, getSample: (Int) -> Double) {
            var m = 0.0
            for (c in 0 until channels) m += getSample(frame * channels + c)
            val mono = m / channels
            bucketAcc += mono
            bucketCount++
            consumedFrames++
            if (consumedFrames >= nextBucketEnd) {
                val v = (bucketAcc / bucketCount).coerceIn(-1.0, 1.0)
                dos.writeLeShort((v * 32767).toInt())
                pcmFrames++
                bucketAcc = 0.0
                bucketCount = 0
                nextBucketEnd += outSampleRate.toDouble() / OUT_RATE
            }
        }

        fun transcribePending(final: Boolean) {
            val untranscribed = pcmFrames - sliceStart
            if (untranscribed <= 0) return
            if (!final && untranscribed < sliceSamples) return
            dos.flush() // 先落盘再读
            val count = if (final) untranscribed.toInt() else sliceSamples.toInt()
            if (count < OUT_RATE / 2) return // 尾巴不足半秒不识别
            val samples = readSlice(pcmFile, 44, sliceStart, count)
            val lines = transcribe(samples)
            if (lines.isNotEmpty()) {
                totalLines += lines.size
                onSlice(lines, ++sliceIdx, sliceStart)
            }
            sliceStart += count
        }

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (isCancelled()) break
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        outSampleRate = of.getInteger(KEY_SAMPLE_RATE)
                        outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = of.getInteger(KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    in 0 until Int.MAX_VALUE -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val frames = info.size / (2 * outChannels)
                            if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = outBuf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                for (fr in 0 until frames) emit(fr, outChannels) { i -> fb.get(i).toDouble() }
                            } else {
                                val sb = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                                for (fr in 0 until frames) emit(fr, outChannels) { i -> sb.get(i) / 32768.0 }
                            }
                            // 每满一段立即转写上屏（边解码边出句）
                            transcribePending(final = false)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            // 收尾：最后不足一段的部分也识别
            if (!isCancelled()) transcribePending(final = true)
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        // 回填 WAV 头
        val dataLen = pcmFrames * 2
        dos.flush()
        val raf = java.io.RandomAccessFile(pcmFile, "rw")
        raf.seek(4); raf.writeLeInt((36 + dataLen).toInt())
        raf.seek(40); raf.writeLeInt(dataLen.toInt())
        raf.close()
        dos.close()
        Log.i(TAG, "stream decoded ${pcmFrames / OUT_RATE}s → $totalLines lyric lines")
        return totalLines
    }

    private fun DataOutputStream.writeLeInt(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }
    private fun DataOutputStream.writeLeShort(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
    private fun java.io.RandomAccessFile.writeLeInt(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }
    private fun writeWavHeader(dos: DataOutputStream) {
        dos.writeBytes("RIFF"); dos.writeLeInt(0) // 回填
        dos.writeBytes("WAVE")
        dos.writeBytes("fmt "); dos.writeLeInt(16)
        dos.writeShort(1); dos.writeShort(1) // PCM, mono
        dos.writeLeInt(OUT_RATE)
        dos.writeLeInt(OUT_RATE * 2)
        dos.write(1); dos.write(2)
        dos.writeBytes("data"); dos.writeLeInt(0) // 回填
    }

    /** 读取 WAV 的 (data 块文件偏移, 总样本数)；非 16k/单声道/PCM16 返回 null */
    fun wavInfo(file: File): Pair<Long, Long>? {
        val raf = java.io.RandomAccessFile(file, "r")
        try {
            val hdr = ByteArray(12)
            if (raf.read(hdr) != 12 || String(hdr, 0, 4) != "RIFF" || String(hdr, 8, 4) != "WAVE") return null
            var fmt = -1
            var channels = 0
            var rate = 0
            var dataOff = -1L
            var dataLen = 0L
            while (true) {
                val id = ByteArray(4)
                val sizeB = ByteArray(4)
                if (raf.read(id) != 4 || raf.read(sizeB) != 4) break
                val size = java.lang.Long.parseLong(
                    (sizeB.joinToString("") { "%02x".format(it) }), 16)
                when (String(id)) {
                    "fmt " -> {
                        val fb = ByteArray(minOf(size.toInt(), 40))
                        if (raf.read(fb) != fb.size) break
                        fmt = fb[0].toInt() and 0xFF
                        channels = fb[2].toInt() and 0xFF
                        rate = (fb[4].toInt() and 0xFF) or
                            ((fb[5].toInt() and 0xFF) shl 8) or
                            ((fb[6].toInt() and 0xFF) shl 16) or
                            ((fb[7].toInt() and 0xFF) shl 24)
                        if (size > fb.size.toLong()) raf.seek(raf.filePointer + (size - fb.size))
                    }
                    "data" -> { dataOff = raf.filePointer; dataLen = size; break }
                    else -> raf.seek(raf.filePointer + size + (size and 1))
                }
            }
            if (dataOff < 0 || fmt != 1 || rate != OUT_RATE || channels != 1) return null
            return dataOff to (dataLen / 2)
        } finally {
            raf.close()
        }
    }

    /** 读取 WAV 的 [startSample, startSample+count) → float（-1~1）。dataOffset 为 data 块文件偏移。 */
    fun readSlice(file: File, dataOffset: Long, startSample: Long, count: Int): FloatArray {
        val raf = java.io.RandomAccessFile(file, "r")
        try {
            raf.seek(dataOffset + startSample * 2)
            val bytes = ByteArray(count * 2)
            val got = raf.read(bytes)
            val shorts = ShortArray(got / 2)
            ByteBuffer.wrap(bytes, 0, shorts.size * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return FloatArray(shorts.size) { shorts[it] / 32768.0f }
        } finally {
            raf.close()
        }
    }
}
