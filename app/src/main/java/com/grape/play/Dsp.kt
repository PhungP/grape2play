@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.grape.play

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Thông số xử lý âm thanh do người dùng chỉnh (EQ 10 băng, cân bằng trái/phải, preamp). Lưu vào SharedPreferences. */
object DspParams {
    val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val PRESETS: List<Pair<String, FloatArray>> = listOf(
        "flat" to FloatArray(10),
        "warm" to floatArrayOf(2f, 2f, 1.5f, 1f, 0f, 0f, -0.5f, -1f, -1.5f, -2f),
        "vocal" to floatArrayOf(-2f, -1.5f, -1f, 0f, 1.5f, 2.5f, 2.5f, 1.5f, 0f, -1f),
        "bass" to floatArrayOf(5f, 4.5f, 3.5f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f),
    )
    val on = MutableStateFlow(true)
    val bands = MutableStateFlow(List(10) { 0f })
    val balance = MutableStateFlow(0f)   // -1 = chỉ trái … +1 = chỉ phải
    val preamp = MutableStateFlow(0f)    // dB
    val version = AtomicInteger(0)
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("dsp", Context.MODE_PRIVATE)
        on.value = sp.getBoolean("on", true)
        bands.value = (sp.getString("bands", "") ?: "").split(',').mapNotNull { it.toFloatOrNull() }.let { if (it.size == 10) it else List(10) { 0f } }
        balance.value = sp.getFloat("bal", 0f); preamp.value = sp.getFloat("pre", 0f)
        version.incrementAndGet()
    }
    private fun save() {
        if (!::sp.isInitialized) return
        sp.edit().putBoolean("on", on.value).putString("bands", bands.value.joinToString(",")).putFloat("bal", balance.value).putFloat("pre", preamp.value).apply()
        version.incrementAndGet()
    }
    fun setOn(v: Boolean) { on.value = v; save() }
    fun setBand(i: Int, db: Float) { bands.value = bands.value.toMutableList().also { it[i] = db.coerceIn(-12f, 12f) }; save() }
    fun setBands(v: FloatArray) { bands.value = v.toList(); save() }
    fun setBalance(v: Float) { balance.value = ((v * 20).toInt() / 20f).coerceIn(-1f, 1f); save() }
    fun setPreamp(v: Float) { preamp.value = v.coerceIn(-12f, 6f); save() }
    fun reset() { bands.value = List(10) { 0f }; balance.value = 0f; preamp.value = 0f; save() }
    fun isFlat() = !on.value || (bands.value.all { kotlin.math.abs(it) < 0.05f } && kotlin.math.abs(balance.value) < 0.01f && kotlin.math.abs(preamp.value) < 0.05f)
}

/** EQ 10 băng (biquad peaking, Q≈1.4) + preamp + cân bằng L/R. Khi mọi thứ phẳng thì chép nguyên dữ liệu (bit-perfect). */
class DspProcessor : BaseAudioProcessor() {
    private var enc = C.ENCODING_PCM_16BIT
    private var ch = 2
    private var sr = 44100
    private var ver = -1
    private var nb = 0
    private var b0 = FloatArray(10); private var b1 = FloatArray(10); private var b2 = FloatArray(10)
    private var a1 = FloatArray(10); private var a2 = FloatArray(10)
    private var z1 = Array(10) { FloatArray(8) }
    private var z2 = Array(10) { FloatArray(8) }
    private var gain = FloatArray(8) { 1f }

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        enc = f.encoding; ch = f.channelCount.coerceAtMost(8); sr = f.sampleRate; ver = -1
        z1 = Array(10) { FloatArray(8) }; z2 = Array(10) { FloatArray(8) }
        return when (f.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> f
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    private fun refresh() {
        val v = DspParams.version.get()
        if (v == ver) return
        ver = v
        val g = DspParams.bands.value
        nb = 0
        for (i in 0 until 10) {
            val db = g[i]
            val f0 = DspParams.FREQS[i]
            if (kotlin.math.abs(db) < 0.05f || f0 >= sr * 0.45f) continue
            val a = 10.0.pow(db / 40.0)
            val w = 2 * PI * f0 / sr
            val al = sin(w) / (2 * 1.4)
            val cw = cos(w)
            val a0 = 1 + al / a
            b0[nb] = ((1 + al * a) / a0).toFloat(); b1[nb] = ((-2 * cw) / a0).toFloat(); b2[nb] = ((1 - al * a) / a0).toFloat()
            a1[nb] = ((-2 * cw) / a0).toFloat(); a2[nb] = ((1 - al / a) / a0).toFloat()
            nb++
        }
        val pre = 10.0.pow(DspParams.preamp.value / 20.0).toFloat()
        val bal = DspParams.balance.value
        for (c in 0 until 8) gain[c] = pre * (if (c == 0) minOf(1f, 1f - bal) else if (c == 1) minOf(1f, 1f + bal) else 1f)
    }

    override fun queueInput(input: ByteBuffer) {
        val n = input.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n).order(ByteOrder.LITTLE_ENDIAN)
        if (DspParams.isFlat()) { out.put(input); out.flip(); return }
        refresh()
        val inp = input.order(ByteOrder.LITTLE_ENDIAN)
        val bytes = when (enc) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        val frames = n / (bytes * ch.coerceAtLeast(1))
        var p = inp.position()
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                var x = when (enc) {
                    C.ENCODING_PCM_16BIT -> inp.getShort(p) / 32768f
                    C.ENCODING_PCM_24BIT -> ((inp.get(p + 2).toInt() shl 16) or ((inp.get(p + 1).toInt() and 0xff) shl 8) or (inp.get(p).toInt() and 0xff)) / 8388608f
                    C.ENCODING_PCM_32BIT -> inp.getInt(p) / 2147483648f
                    else -> inp.getFloat(p)
                }
                x *= gain[c]
                for (k in 0 until nb) {
                    val y = b0[k] * x + z1[k][c]
                    z1[k][c] = b1[k] * x - a1[k] * y + z2[k][c]
                    z2[k][c] = b2[k] * x - a2[k] * y
                    x = y
                }
                when (enc) {
                    C.ENCODING_PCM_16BIT -> out.putShort(Math.round(x * 32768f).coerceIn(-32768, 32767).toShort())
                    C.ENCODING_PCM_24BIT -> { val v = Math.round(x * 8388608f).coerceIn(-8388608, 8388607); out.put(v.toByte()); out.put((v shr 8).toByte()); out.put((v shr 16).toByte()) }
                    C.ENCODING_PCM_32BIT -> out.putInt((x.toDouble() * 2147483648.0).toLong().coerceIn(-2147483648L, 2147483647L).toInt())
                    else -> out.putFloat(x)
                }
                p += bytes
            }
        }
        val rest = n - frames * bytes * ch
        for (i in 0 until rest) { out.put(inp.get(p)); p++ }
        inp.position(inp.limit())
        out.flip()
    }
}
