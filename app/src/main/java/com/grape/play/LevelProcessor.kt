@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.grape.play

import androidx.media3.common.C
import androidx.media3.common.Format
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Đo mức tín hiệu L/R thật từ luồng PCM đang phát (không thay đổi âm thanh) → VU meter, và cho biết định dạng PCM thật đưa ra đầu ra.
 *
 * Từ 0.9.2 đo ngay tại cửa vào bộ xuất âm thanh (xem Playback.build: ForwardingAudioSink) thay vì làm một AudioProcessor.
 * Lý do: với file 24-bit (FLAC/DSD→PCM) khi chọn "Tương thích 16-bit", ExoPlayer đổi sang 16-bit ở bên trong bộ xuất âm thanh nên
 * AudioProcessor của app không được gọi → đồng hồ VU đứng yên (-∞ dB) và dòng "Đường tín hiệu" thiếu định dạng PCM. Đo ở cửa vào luôn thấy PCM gốc.
 */
class LevelProcessor {
    val levels = MutableStateFlow(0f to 0f)
    val fmt = MutableStateFlow("")
    private var l = 0f
    private var r = 0f
    private var enc = C.ENCODING_INVALID
    private var ch = 2

    private fun isPcm(e: Int) = e == C.ENCODING_PCM_16BIT || e == C.ENCODING_PCM_24BIT || e == C.ENCODING_PCM_32BIT || e == C.ENCODING_PCM_FLOAT

    /** Gọi khi bộ xuất âm thanh được cấu hình cho một luồng mới. [floatOut]: người dùng chọn Hi-res (PCM float 32-bit). */
    fun configure(f: Format, floatOut: Boolean) {
        enc = f.pcmEncoding; ch = f.channelCount.coerceAtLeast(1)
        l = 0f; r = 0f
        if (!isPcm(enc)) { fmt.value = ""; return }
        val highRes = enc != C.ENCODING_PCM_16BIT
        val out = if (floatOut && highRes) "32-bit float" else "16-bit"   // không chọn Hi-res: ExoPlayer hạ mọi thứ về 16-bit
        fmt.value = "PCM $out ${khz(f.sampleRate)}"
    }

    /** Đo một khối PCM sắp đưa vào bộ xuất âm thanh (chỉ đọc, không sửa, không làm xê dịch vị trí buffer gốc). */
    fun tap(src: ByteBuffer) {
        if (!isPcm(enc) || !src.hasRemaining()) return
        meter(src.duplicate().order(ByteOrder.LITTLE_ENDIAN))
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
