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

    /** 段间重叠秒数：给 whisper 一点上文，避免每段开头硬切句子 */
    private const val SLICE_OVERLAP_SEC = 2

    /** PCM 峰值低于这个值视为「解出来是静音」——正常语音远高于此（约 0.05~0.9） */
    private const val MIN_PEAK = 0.003

    /**
     * 把单段转写结果贴到整集时间轴上。
     *
     * 关键点：whisper 返回的 t0/t1 是**相对本段输入起点**的毫秒；
     * 本段输入起点 = sliceStart(样本) - 重叠区，所以绝对时间 = t + baseMs。
     * 落在外层已输出区间（绝对时间 < prevEndMs）的句子属于重叠区残留，丢弃。
     */
    private fun shiftLines(
        lines: List<com.galigalimusic.data.remote.BilibiliApi.LrcLine>,
        baseMs: Long,
        prevEndMs: Long,
        audioEndMs: Long,
    ): List<com.galigalimusic.data.remote.BilibiliApi.LrcLine> =
        lines.mapNotNull { l ->
            val s = (l.startMs + baseMs).coerceAtLeast(0L)
            val e = (l.endMs + baseMs).coerceAtLeast(s)
            if (s + MIN_LINE_MS < prevEndMs) return@mapNotNull null // 重叠区内的重复句
            com.galigalimusic.data.remote.BilibiliApi.LrcLine(
                startMs = s,
                endMs = minOf(e, audioEndMs).coerceAtLeast(s),
                text = l.text,
            )
        }

    private const val MIN_LINE_MS = 100L

    /**
     * 判断一个文件是否是 MediaExtractor 能解析的容器。
     *
     * 背景：MediaCache.exportToFile 从 SimpleCache 里按 span 拼出来的字节，
     * 是 HTTP 响应体的原始缓存，**不含 MP4 容器头（ftyp/moov）**，
     * 对 B 站 dash 音频来说通常是 m4s 分片，MediaExtractor 会读不到任何轨道
     * → 解出全零 PCM → whisper 返回空 segments（本次真机实测就是这个现象）。
     *
     * 这里用「能不能真的读到音频轨」作为判据，比只看文件头可靠：
     * 有 ftyp 缺 moov 的文件同样解析不了。
     */
    fun hasParsableAudioTrack(path: String): Boolean = try {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            (0 until ex.trackCount).any { i ->
                ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
        } finally {
            runCatching { ex.release() }
        }
    } catch (e: Exception) {
        false
    }

    /**
     * 读音频轨时长（毫秒）；读不到返回 0。
     * 用于判断缓存导出的是否只是「播到一半」的片段——这种文件容器完好、能解析，
     * 但内容不全，识别会静默只出前半段歌词。
     */
    fun audioDurationMs(path: String): Long = try {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            var best = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) {
                    best = maxOf(best, f.getLong(MediaFormat.KEY_DURATION) / 1000L)
                }
            }
            best
        } finally {
            runCatching { ex.release() }
        }
    } catch (e: Exception) {
        0L
    }

    /**
     * 流式解码并分块转写。
     * @param transcribe 一段 float PCM（16k 单声道）→ 歌词行（时间戳相对该段起点）
     * @param onSlice 每段转写完成的回调（歌词行、完成段数、该段起点样本数）；
     *                歌词行时间戳**已换算成整集绝对毫秒**，调用方直接追加即可
     * @param isCancelled 外部取消钩子（每轮输入/输出检查一次）
     * @return 总歌词行数
     *
     * 注意：段与段之间带 [SLICE_OVERLAP_SEC] 重叠。whisper 对"从句中开始"的输入很敏感，
     * 硬切会让被切断的半句被当成新句、并带上静音 padding 导致该段整体偏后。
     * 重叠区的重复句子由 [shiftLines] 按绝对时间过滤掉。
     */
    fun decodeTranscribeStreaming(
        inputPath: String,
        pcmFile: File,
        sliceSec: Int,
        /** 只转写前 N 秒（0=不限）。长音频（有声书/歌单）整集转写要跑几十分钟，
         *  手机端不可接受，UI 上也会长时间停在低百分比。 */
        maxSeconds: Int = 0,
        transcribe: (FloatArray) -> List<com.galigalimusic.data.remote.BilibiliApi.LrcLine>,
        onSlice: (List<com.galigalimusic.data.remote.BilibiliApi.LrcLine>, Int, Long) -> Unit,
        isCancelled: () -> Boolean,
        debugSink: ((String) -> Unit)? = null,
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
        var sliceStart = 0L // 本段**新内容**起点（16k 样本数，已排除重叠区）
        var pcmFrames = 0L // 已写入的 16k 样本数
        var lastEmittedMs = 0L // 已输出的最大绝对时间（重叠区去重用）
        var bucketAcc = 0.0
        var bucketCount = 0
        var consumedFrames = 0L
        var nextBucketEnd = 0.0
        val sliceSamples = sliceSec.toLong() * OUT_RATE
        val overlapSamples = SLICE_OVERLAP_SEC.toLong() * OUT_RATE

        var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        var outSampleRate = inputFormat.getInteger(KEY_SAMPLE_RATE)
        var outChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        // 音频能量峰值：用于识别「解出来全是静音/全零」的坏输入。
        // 缺容器头、错误解码器、纯空轨都会表现成这种情况，
        // 若不早停，用户要等十几分钟才看到「未识别出语音内容」。
        var peak = 0.0

        fun emit(frame: Int, channels: Int, getSample: (Int) -> Double) {
            var m = 0.0
            for (c in 0 until channels) m += getSample(frame * channels + c)
            val mono = m / channels
            if (mono > peak) peak = mono
            if (-mono > peak) peak = -mono
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
            // 解码出的 PCM 能量低到近似静音 → 输入本身有问题（缺容器头/解码失败），
            // 直接抛错而不是把几十段空结果跑完
            if (peak < MIN_PEAK) {
                throw IllegalStateException(
                    "音频解码为空（信号峰值 ${"%.4f".format(peak)}），文件可能缺少容器头"
                )
            }
            // 本段输入带前置重叠：取 [sliceStart - overlap, sliceStart + count)
            val from = (sliceStart - overlapSamples).coerceAtLeast(0L)
            val realCount = (count + (sliceStart - from)).toInt()
            val samples = readSlice(pcmFile, 44, from, realCount)
            // 诊断：仅前 2 段输出 PCM 特征（RMS/峰值），用于排查「静音/解码错位」类失败；
            // 不逐段打印，避免长音频把日志刷爆
            if (debugSink != null && sliceIdx < 2) {
                var sum = 0.0
                var pk = 0.0
                for (s in samples) {
                    sum += s.toDouble() * s
                    val a = if (s < 0) -s else s
                    if (a > pk) pk = a.toDouble()
                }
                val rms = if (samples.isNotEmpty()) kotlin.math.sqrt(sum / samples.size) else 0.0
                debugSink(
                    "pcm slice#$sliceIdx from=$from count=${samples.size} " +
                    "rms=${"%.5f".format(rms)} peak=${"%.5f".format(pk)} rate=$outSampleRate ch=$outChannels"
                )
            }
            val lines = transcribe(samples)
            // 相对位移 → 绝对时间轴（本段输入起点 = from）
            val baseMs = from * 1000L / OUT_RATE
            val audioEndMs = pcmFrames.coerceAtLeast(sliceStart + count) * 1000L / OUT_RATE
            val shifted = shiftLines(lines, baseMs, lastEmittedMs, audioEndMs)
            // 打平时间轴：whisper 分句可能首尾相接或轻微重叠，
            // 让每行 endMs 不超过下一行 startMs（避免卡拉 OK 高亮"卡在上一句"）
            val flattened = shifted.mapIndexed { i, line ->
                val next = shifted.getOrNull(i + 1)
                if (next != null && line.endMs > next.startMs) line.copy(endMs = next.startMs) else line
            }
            if (flattened.isNotEmpty()) {
                totalLines += flattened.size
                lastEmittedMs = flattened.maxOf { it.endMs }
                // 回调里的偏移参数固定按「样本」语义给出（= from），与注释一致
                onSlice(flattened, ++sliceIdx, from)
            }
            sliceStart += count
        }

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val maxFrames = if (maxSeconds > 0) maxSeconds.toLong() * OUT_RATE else Long.MAX_VALUE
        // 见 TRY_AGAIN_LATER 分支：残缺输入上部分解码器不回 EOS，用空闲计数兜底
        var idleRounds = 0

        try {
            while (!outputDone) {
                if (isCancelled()) break
                // 到达识别上限：主动停，别再往后续段白跑
                if (pcmFrames >= maxFrames) break
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
                        // 注意：getInteger(String, int) 双参重载要 API 29，minSdk 24 不能用；
                        // 改用 containsKey + 单参 getInteger（API 16+）兼容旧系统
                        pcmEncoding = if (of.containsKey(KEY_PCM_ENCODING)) of.getInteger(KEY_PCM_ENCODING)
                        else android.media.AudioFormat.ENCODING_PCM_16BIT
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 华为 CCodec 实测：容器头声明 57 分钟、实际只有前几十秒数据的
                        // 残缺文件，解到数据末尾后永远不回 EOS，这里会死等下去（CPU≈0）。
                        // 输入已结束且连续 6 轮（≈60s）毫无输出，判定数据耗尽，收尾出词。
                        if (inputDone && ++idleRounds >= 6) {
                            Log.w(TAG, "decoder idle after EOS, assume truncated input; stop decoding")
                            outputDone = true
                        }
                    }
                    in 0 until Int.MAX_VALUE -> {
                        idleRounds = 0
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
