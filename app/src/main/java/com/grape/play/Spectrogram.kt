package com.grape.play

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin

/** Kết quả phân tích: db[kênh][cột * rows + hàng], hàng 0 = tần số thấp nhất, giá trị dBFS (đáy -140). */
class SpecData(
    val nc: Int, val cols: Int, val rows: Int, val sr: Int, val bits: Int, val srcChannels: Int, val seconds: Double,
    /** Dòng mô tả như tiêu đề biểu đồ của Spek: "FLAC (Free Lossless Audio Codec), 44100 Hz, 16 bits, 2 channels". */
    val title: String,
) {
    val db = Array(nc) { FloatArray(cols * rows) { Spectrogram.FLOOR } }
    @Volatile var done = 0
}

/** Nguồn PCM tuần tự (kênh trái/phải; nhạc mono thì r = l). */
private interface PcmSrc {
    val sr: Int
    val ch: Int
    val bits: Int
    val totalFrames: Long
    val codec: String
    val kbps: Int
    val bitsKnown: Boolean
    fun read(l: FloatArray, r: FloatArray, max: Int): Int
    fun close()
}

/** WAV (file thật hoặc luồng WAV ảo do FlacPcm tạo ra). */
private class WavSrc(private val rd: (Long, ByteArray, Int, Int) -> Int, size: Long, private val codecName: String?, private val onClose: () -> Unit) : PcmSrc {
    override val kbps = 0
    override val bitsKnown = true
    override val codec: String get() = codecName ?: (if (isFloat) "PCM ${bits}-bit floating-point little-endian" else "PCM signed ${bits}-bit little-endian")
    override var sr = 0; override var ch = 0; override var bits = 0; override var totalFrames = 0L
    private var isFloat = false
    private var start = 0L; private var len = 0L; private var pos = 0L
    private var fb = 0; private var bs = 0
    private val buf: ByteArray

    private fun full(p: Long, b: ByteArray, n: Int): Int {
        var got = 0
        while (got < n) { val k = rd(p + got, b, got, n - got); if (k <= 0) break; got += k }
        return got
    }
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int) = (le16(b, o).toLong() and 0xffff) or ((le16(b, o + 2).toLong() and 0xffff) shl 16)

    init {
        val h = ByteArray(12)
        if (full(0, h, 12) < 12 || String(h, 0, 4) != "RIFF" || String(h, 8, 4) != "WAVE") throw IOException("Không phải file WAV hợp lệ")
        var p = 12L
        val c = ByteArray(8)
        while (p + 8 <= size) {
            if (full(p, c, 8) < 8) break
            val id = String(c, 0, 4); val sz = le32(c, 4)
            if (id == "fmt ") {
                val b = ByteArray(min(sz, 40L).toInt()); full(p + 8, b, b.size)
                val tag = le16(b, 0); ch = le16(b, 2); sr = le32(b, 4).toInt(); bits = le16(b, 14)
                isFloat = if (tag == 0xFFFE && b.size >= 26) le16(b, 24) == 3 else tag == 3
            } else if (id == "data") {
                start = p + 8
                len = if (sz == 0L || sz > size - start) size - start else sz
                break
            }
            p += 8 + sz + (sz and 1L)
        }
        if (sr <= 0 || ch <= 0 || bits !in 8..32 || len <= 0) throw IOException("Không đọc được thông số WAV")
        bs = bits / 8; fb = bs * ch
        totalFrames = len / fb
        buf = ByteArray(CH * fb)
    }

    private fun smp(o: Int): Float = when (bs) {
        1 -> ((buf[o].toInt() and 0xff) - 128) / 128f
        2 -> ((buf[o].toInt() and 0xff) or (buf[o + 1].toInt() shl 8)) / 32768f
        3 -> (((buf[o].toInt() and 0xff) shl 8) or ((buf[o + 1].toInt() and 0xff) shl 16) or (buf[o + 2].toInt() shl 24)) / 2147483648f
        else -> {
            val v = (buf[o].toInt() and 0xff) or ((buf[o + 1].toInt() and 0xff) shl 8) or ((buf[o + 2].toInt() and 0xff) shl 16) or (buf[o + 3].toInt() shl 24)
            if (isFloat) java.lang.Float.intBitsToFloat(v) else v / 2147483648f
        }
    }

    override fun read(l: FloatArray, r: FloatArray, max: Int): Int {
        val want = min(min(max, CH).toLong(), totalFrames - pos).toInt()
        if (want <= 0) return 0
        val got = full(start + pos * fb, buf, want * fb) / fb
        for (i in 0 until got) { val o = i * fb; l[i] = smp(o); r[i] = if (ch > 1) smp(o + bs) else l[i] }
        pos += got
        return got
    }
    override fun close() = onClose()
    companion object { const val CH = 16384 }
}

/** MP3 / AAC / M4A / OGG / Opus… giải mã bằng MediaCodec của hệ thống. */
private class CodecSrc(private val reader: RemoteReader) : PcmSrc {
    private val ext = MediaExtractor()
    private lateinit var dec: MediaCodec
    override var sr = 0; override var ch = 0; override val bits = 16; override var totalFrames = 0L
    override var codec = ""; override var kbps = 0; override val bitsKnown = false
    private val info = MediaCodec.BufferInfo()
    private var inDone = false; private var outDone = false
    private var out: ByteBuffer? = null; private var outIdx = -1
    private var bpos = 0; private var blim = 0
    private var isFloat = false; private var oc = 2

    init {
        try {
            ext.setDataSource(RemoteMds(reader))
            var idx = -1; var fmt: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val f = ext.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { idx = i; fmt = f; break }
            }
            if (idx < 0 || fmt == null) throw IOException("Không tìm thấy luồng âm thanh")
            ext.selectTrack(idx)
            sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT); oc = ch
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            codec = when (mime) {
                "audio/mpeg" -> "MP3 (MPEG audio layer 3)"
                "audio/mp4a-latm" -> "AAC (Advanced Audio Coding)"
                "audio/vorbis" -> "Vorbis"
                "audio/opus" -> "Opus (Opus Interactive Audio Codec)"
                "audio/alac" -> "ALAC (Apple Lossless Audio Codec)"
                "audio/flac" -> "FLAC (Free Lossless Audio Codec)"
                else -> mime.removePrefix("audio/")
            }
            if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) kbps = fmt.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
            val us = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            totalFrames = us * sr / 1_000_000L
            dec = try { MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!) } catch (e: Exception) { throw IOException("Thiết bị không có bộ giải mã cho định dạng này") }
            dec.configure(fmt, null, null, 0); dec.start()
        } catch (e: Exception) { runCatching { ext.release() }; throw e }
    }

    /** Nạp buffer đầu ra kế tiếp; false khi hết dữ liệu. */
    private fun fill(): Boolean {
        var idle = 0
        while (true) {
            if (!inDone) {
                val i = dec.dequeueInputBuffer(0)
                if (i >= 0) {
                    val b = dec.getInputBuffer(i)!!
                    val n = ext.readSampleData(b, 0)
                    if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { dec.queueInputBuffer(i, 0, n, ext.sampleTime, 0); ext.advance() }
                }
            }
            val o = dec.dequeueOutputBuffer(info, 10_000)
            when {
                o >= 0 -> {
                    idle = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    if (info.size > 0) {
                        outIdx = o; out = dec.getOutputBuffer(o)
                        bpos = info.offset; blim = info.offset + info.size
                        return true
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (outDone) return false
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = dec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) oc = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                }
                else -> if (outDone || ++idle > 500) return false
            }
        }
    }

    override fun read(l: FloatArray, r: FloatArray, max: Int): Int {
        var n = 0
        while (n < max) {
            if (outIdx < 0) { if (outDone || !fill()) break }
            val b = out!!; b.order(ByteOrder.LITTLE_ENDIAN)
            val bsz = if (isFloat) 4 else 2
            val fb = bsz * oc
            while (n < max && bpos + fb <= blim) {
                l[n] = if (isFloat) b.getFloat(bpos) else b.getShort(bpos) / 32768f
                r[n] = if (oc > 1) (if (isFloat) b.getFloat(bpos + bsz) else b.getShort(bpos + bsz) / 32768f) else l[n]
                bpos += fb; n++
            }
            if (bpos + fb > blim) { dec.releaseOutputBuffer(outIdx, false); outIdx = -1; out = null }
        }
        return n
    }

    override fun close() {
        runCatching { dec.stop() }; runCatching { dec.release() }; runCatching { ext.release() }; runCatching { reader.close() }
    }
}

private class Fft(val n: Int) {
    private val c = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val s = FloatArray(n / 2) { (-sin(2.0 * PI * it / n)).toFloat() }
    private val rev = IntArray(n).also { r -> val bits = Integer.numberOfTrailingZeros(n); for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits) }
    fun run(re: FloatArray, im: FloatArray) {
        var len = 2
        while (len <= n) {
            val half = len / 2; val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val xr = re[j + half] * c[k] - im[j + half] * s[k]
                    val xi = re[j + half] * s[k] + im[j + half] * c[k]
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
    fun bitrev(i: Int) = rev[i]
}

/** Phổ tần kiểu Spek: giải mã toàn bộ bài, FFT 2048 điểm (cửa sổ Hann), trục tần số tuyến tính 0 → Nyquist, trục x là thời gian. */
object Spectrogram {
    const val N = 2048
    const val COLS = 1100
    const val ROWS = 512
    const val FLOOR = -140f
    private const val K = 3   // số cửa sổ FFT trung bình cho mỗi cột

    private val cache = object : LinkedHashMap<String, SpecData>(4, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, SpecData>?) = size > 3
    }
    fun cached(path: String): SpecData? = synchronized(cache) { cache[path] }

    fun isDsd(t: Track) = t.ext in setOf("dsf", "dff", "dsd")

    private fun open(t: Track): PcmSrc {
        val reader = Remote.reader(t.path)
        return try {
            when (t.ext) {
                "flac" -> { val s = FlacPcm(ReaderSource(reader)); WavSrc({ p, b, o, n -> s.read(p, b, o, n) }, s.size(), "FLAC (Free Lossless Audio Codec)") { s.close() } }
                "wav", "wave" -> WavSrc({ p, b, o, n -> reader.read(p, b, o, n) }, reader.size, null) { reader.close() }
                else -> CodecSrc(reader)
            }
        } catch (e: Exception) { runCatching { reader.close() }; throw e }
    }

    /** Phân tích cả bài. onOpen được gọi ngay khi biết thông số (để UI vẽ dần theo SpecData.done). */
    suspend fun analyze(t: Track, onOpen: (SpecData) -> Unit): SpecData = withContext(Dispatchers.IO) {
        val src = open(t)
        try {
            val total = src.totalFrames
            if (total < N) throw IOException("File quá ngắn hoặc không xác định được độ dài")
            val nc = if (src.ch > 1) 2 else 1   // số kênh được đọc; phổ gộp (trung bình công suất L/R) thành một bảng
            val bits = if (t.bits > 0) t.bits else src.bits
            val title = buildString {
                append(src.codec).append(", ").append(src.sr).append(" Hz")
                if (src.bitsKnown && bits > 0) append(", ").append(bits).append(" bits")
                append(", ").append(src.ch).append(if (src.ch == 1) " channel" else " channels")
                if (src.kbps > 0) append(", ").append(src.kbps).append(" kbps")
            }
            val data = SpecData(1, COLS, ROWS, src.sr, bits, src.ch, total.toDouble() / src.sr, title)
            onOpen(data)
            val fft = Fft(N)
            val win = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
            val scale = (2f / win.sum()).let { it * it } / K
            val ring = Array(nc) { FloatArray(N) }
            val acc = FloatArray(N / 2)
            val re = FloatArray(N); val im = FloatArray(N)
            val nWin = COLS * K
            val ends = LongArray(nWin) { val c = it / K; val k = it % K; (((c + (k + 0.5) / K) * total / COLS) + N / 2).toLong().coerceIn(N.toLong(), total) }
            val l = FloatArray(WavSrc.CH); val r = FloatArray(WavSrc.CH)
            var w = 0; var fi = 0L; var wi = 0
            var nextEnd = ends[0]
            val per = (N / 2) / ROWS
            while (wi < nWin) {
                ensureActive()
                val n = src.read(l, r, WavSrc.CH)
                if (n <= 0) break
                for (i in 0 until n) {
                    ring[0][w] = l[i]; if (nc > 1) ring[1][w] = r[i]
                    w = (w + 1) and (N - 1); fi++
                    while (wi < nWin && nextEnd <= fi) {
                        for (c in 0 until nc) {
                            val rg = ring[c]
                            for (j in 0 until N) { re[fft.bitrev(j)] = rg[(w + j) and (N - 1)] * win[j]; im[j] = 0f }
                            fft.run(re, im)
                            for (b in 0 until N / 2) acc[b] += re[b] * re[b] + im[b] * im[b]
                        }
                        if (wi % K == K - 1) {
                            val col = wi / K
                            for (row in 0 until ROWS) {
                                var p = 0f
                                for (b in row * per until (row + 1) * per) p += acc[b]
                                p = p * scale / per / nc
                                data.db[0][col * ROWS + row] = (10f * log10(p.coerceAtLeast(1e-14f))).coerceAtLeast(FLOOR)
                            }
                            java.util.Arrays.fill(acc, 0f)
                            data.done = col + 1
                        }
                        wi++
                        if (wi < nWin) nextEnd = ends[wi]
                    }
                }
            }
            data.done = COLS
            synchronized(cache) { cache[t.path] = data }
            data
        } finally { runCatching { src.close() } }
    }
}
