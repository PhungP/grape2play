@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.grape.play

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Đo mức tín hiệu L/R thật từ luồng PCM đang phát (không thay đổi âm thanh) → VU meter. */
class LevelProcessor : BaseAudioProcessor() {
    val levels = MutableStateFlow(0f to 0f)
    val fmt = MutableStateFlow("")
    private var l = 0f
    private var r = 0f
    private var enc = C.ENCODING_PCM_16BIT
    private var ch = 2

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        enc = f.encoding; ch = f.channelCount
        val bits = when (f.encoding) { C.ENCODING_PCM_16BIT -> "16-bit"; C.ENCODING_PCM_24BIT -> "24-bit"; C.ENCODING_PCM_32BIT -> "32-bit"; C.ENCODING_PCM_FLOAT -> "32-bit float"; else -> "?" }
        fmt.value = "PCM $bits ${khz(f.sampleRate)}"
        return when (f.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> f
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(input: ByteBuffer) {
        val n = input.remaining()
        if (n == 0) return
        meter(input.duplicate().order(ByteOrder.LITTLE_ENDIAN))
        val out = replaceOutputBuffer(n)
        out.put(input)
        out.flip()
    }

    private fun sample(b: ByteBuffer, i: Int): Float = when (enc) {
        C.ENCODING_PCM_16BIT -> b.getShort(i) / 32768f
        C.ENCODING_PCM_24BIT -> ((b.get(i + 2).toInt() shl 16) or ((b.get(i + 1).toInt() and 0xff) shl 8) or (b.get(i).toInt() and 0xff)) / 8388608f
        C.ENCODING_PCM_32BIT -> b.getInt(i) / 2147483648f
        else -> b.getFloat(i)
    }

    private fun meter(b: ByteBuffer) {
        val bytes = when (enc) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        val frame = bytes * ch
        val frames = b.remaining() / frame
        if (frames == 0) return
        val step = max(1, frames / 256)
        var pl = 0f; var pr = 0f; var k = 0
        while (k < frames) {
            val base = b.position() + k * frame
            pl = max(pl, abs(sample(b, base)))
            pr = max(pr, if (ch > 1) abs(sample(b, base + bytes)) else pl)
            k += step
        }
        l = max(pl, l * 0.92f); r = max(pr, r * 0.92f)
        levels.value = l to r
    }

}
