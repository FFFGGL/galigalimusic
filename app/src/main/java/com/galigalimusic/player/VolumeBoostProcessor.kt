package com.galigalimusic.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * 简单音量增益处理器：按 gain 缩放 PCM 采样（音效增强阶段 6 的基础实现）。
 * 支持 16-bit 与 Float PCM；其它编码抛 UnhandledAudioFormatException。
 * 基于 BaseAudioProcessor，编解码缓冲与格式簿记由基类托管。
 */
class VolumeBoostProcessor(private var gain: Float = 1.0f) : BaseAudioProcessor() {

    fun setGain(g: Float) {
        gain = g.coerceAtLeast(0f)
    }

    override fun isActive(): Boolean = gain != 1.0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(buffer: ByteBuffer) {
        val out = replaceOutputBuffer(buffer.remaining())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (buffer.hasRemaining()) {
                out.putFloat((buffer.float * gain).coerceIn(-1f, 1f))
            }
        } else {
            while (buffer.hasRemaining()) {
                val boosted = (buffer.short * gain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.putShort(boosted.toShort())
            }
        }
    }
}
