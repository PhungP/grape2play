package com.grape.play

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaFormat
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Đọc thông số kỹ thuật thật của file (FLAC/WAV đọc từ header, còn lại qua MediaExtractor). Hỗ trợ cả file trên NAS. */
object Probe {
    private fun le16(b: ByteArray, p: Int) = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, p: Int) = le16(b, p) or (le16(b, p + 2) shl 16)

    fun probe(t: Track): AudioInfo {
        if (t.ext == "dsf" || t.ext == "dff") {
            if (t.sr > 0) return AudioInfo(t.sr, 1, t.ch.coerceAtLeast(2), t.kbps)
            runCatching { val rd = Remote.reader(t.path); try { val m = DsdPcm.readMeta(ReaderSource(rd)); return AudioInfo(m.rate, 1, m.channels, 0) } finally { rd.close() } }
        }
        var sr = t.sr; var ch = t.ch; var bits = t.bits; var size = -1L
        // Luôn mở file một lần: lấy dung lượng (tính bitrate thật) và đọc header FLAC/WAV nếu còn thiếu thông số (DLNA thường không báo độ sâu bit).
        runCatching {
            val rd = Remote.reader(t.path)
            try {
                size = rd.size
                if (sr == 0 || ch == 0 || bits == 0) {
                    val b = ByteArray(4096); val n = rd.read(0, b, 0, b.size)
                    fun u(i: Int) = b[i].toInt() and 0xff
                    if (n >= 42 && String(b, 0, 4) == "fLaC") {
                        sr = (u(18) shl 12) or (u(19) shl 4) or (u(20) shr 4)
                        ch = ((u(20) shr 1) and 7) + 1
                        bits = (((u(20) and 1) shl 4) or (u(21) shr 4)) + 1
                    } else if (n >= 44 && String(b, 0, 4) == "RIFF") {
                        var p = 12
                        while (p + 8 <= n) {
                            val id = String(b, p, 4); val sz = le32(b, p + 4)
                            if (id == "fmt " && p + 24 <= n) { ch = le16(b, p + 10); sr = le32(b, p + 12); bits = le16(b, p + 22); break }
                            if (sz < 0) break
                            p += 8 + sz + (sz and 1)
                        }
                    }
                }
            } finally { rd.close() }
        }
        if (sr == 0 || ch == 0) runCatching {
            Media.withExtractor(t.path) { ex ->
                val fm = ex.getTrackFormat(0)
                sr = fm.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = fm.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        // Bitrate tính từ dung lượng/thời lượng; bỏ qua số liệu vô lý (lớn hơn PCM thô) mà một số máy chủ DLNA báo sai.
        val rawKbps = if (sr > 0 && bits > 0 && ch > 0) (sr.toLong() * bits * ch / 1000 * 102 / 100).toInt() else Int.MAX_VALUE
        val fromSize = if (size > 0 && t.durationMs > 0) (size * 8 / (t.durationMs / 1000.0) / 1000).toInt() else 0
        val kbps = when {
            fromSize in 1..rawKbps -> fromSize
            t.kbps in 1..rawKbps -> t.kbps
            else -> 0
        }
        return AudioInfo(sr, bits, ch, kbps)
    }
}

/** Ảnh bìa: ảnh từ DLNA, ảnh nhúng trong file, hoặc cover/folder/front.jpg|png cùng thư mục (cả trên NAS). */
object Covers {
    private val cache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private val none = ConcurrentHashMap.newKeySet<String>()
    private const val SIZE = 720
    private val names = listOf("cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png", "front.png")

    // ---- Ảnh bìa tìm trên mạng (Deezer, dự phòng iTunes) cho album/bài không có ảnh ----
    private lateinit var sp: SharedPreferences
    private lateinit var webDir: File
    private val webFail = ConcurrentHashMap.newKeySet<String>()   // lỗi mạng trong phiên này: không thử lại liên tục
    private val gate = Mutex()                                    // tìm lần lượt, cách nhau 350 ms để không bị chặn vì gọi quá dày
    private const val MISS_TTL = 7L * 24 * 3600 * 1000

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("covers", Context.MODE_PRIVATE)
        webDir = File(ctx.applicationContext.filesDir, "webcovers").apply { mkdirs() }
    }
    var webEnabled: Boolean
        get() = !::sp.isInitialized || sp.getBoolean("web", true)
        set(v) { if (::sp.isInitialized) sp.edit().putBoolean("web", v).apply() }
    fun setWeb(on: Boolean) { webEnabled = on; none.clear(); webFail.clear() }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun unknown(s: String) = s.isBlank() || s.startsWith("Không rõ")
    private fun same(a: String, b: String): Boolean { val x = norm(a); val y = norm(b); return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x)) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun json(url: String): JSONObject = Http.client.newCall(Request.Builder().url(url).header("User-Agent", "GRAPEPlay/0.6 (Android TV)").build()).execute().use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        JSONObject(r.body?.string() ?: throw IOException("rỗng"))
    }

    /** Trả về URL ảnh bìa hoặc null nếu không có kết quả khớp. Ném IOException nếu lỗi mạng (Deezer). */
    private fun findCoverUrl(t: Track): String? {
        val art = if (unknown(t.artist)) "" else t.artist
        // 1) Deezer: tìm album
        val q = if (art.isNotEmpty()) "artist:\"$art\" album:\"${t.album}\"" else t.album
        val d = json("https://api.deezer.com/search/album?q=${enc(q)}&limit=6").optJSONArray("data")
        if (d != null) for (i in 0 until d.length()) {
            val o = d.optJSONObject(i) ?: continue
            if (same(o.optString("title"), t.album) && (art.isEmpty() || same(o.optJSONObject("artist")?.optString("name") ?: "", art))) {
                o.optString("cover_xl").ifEmpty { o.optString("cover_big") }.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        // 2) iTunes: tìm album (dự phòng; lỗi ở đây không chặn bước sau)
        runCatching {
            val r = json("https://itunes.apple.com/search?term=${enc("$art ${t.album}".trim())}&entity=album&media=music&limit=6").optJSONArray("results")
            if (r != null) for (i in 0 until r.length()) {
                val o = r.optJSONObject(i) ?: continue
                if (same(o.optString("collectionName"), t.album) && (art.isEmpty() || same(o.optString("artistName"), art)))
                    o.optString("artworkUrl100").takeIf { it.isNotEmpty() }?.let { return it.replace("100x100", "600x600") }
            }
        }
        // 3) Deezer: tìm bài hát → lấy ảnh album của bài (khi album ghi sai/thiếu)
        if (art.isNotEmpty()) {
            val s = json("https://api.deezer.com/search?q=${enc("artist:\"$art\" track:\"${t.title}\"")}&limit=6").optJSONArray("data")
            if (s != null) for (i in 0 until s.length()) {
                val o = s.optJSONObject(i) ?: continue
                if (same(o.optString("title"), t.title) && same(o.optJSONObject("artist")?.optString("name") ?: "", art))
                    o.optJSONObject("album")?.optString("cover_xl")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    private suspend fun fromWeb(t: Track): Bitmap? {
        if (!webEnabled || !::webDir.isInitialized || norm(t.album).isEmpty()) return null
        val base = norm(t.artist) + "|" + norm(t.album)
        val id = "%08x%08x".format(base.hashCode(), base.reversed().hashCode())
        val jpg = File(webDir, "$id.jpg"); val miss = File(webDir, "$id.none")
        if (jpg.exists()) return runCatching { decode(jpg.readBytes()) }.getOrNull()
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_TTL) return null
        if (id in webFail) return null
        return gate.withLock {
            if (jpg.exists()) return@withLock runCatching { decode(jpg.readBytes()) }.getOrNull()
            try {
                val url = findCoverUrl(t)
                if (url == null) { runCatching { miss.writeText("x"); miss.setLastModified(System.currentTimeMillis()) }; null }
                else {
                    val bytes = Http.client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
                    val bmp = bytes?.let { decode(it) }
                    if (bmp == null) webFail.add(id) else if (bytes != null) runCatching { jpg.writeBytes(bytes) }
                    bmp
                }
            } catch (e: IOException) { webFail.add(id); null }
            catch (e: JSONException) { webFail.add(id); null }
            finally { delay(350) }
        }
    }

    private fun sample(o: BitmapFactory.Options): Int { var s = 1; while (o.outWidth / (s * 2) >= SIZE && o.outHeight / (s * 2) >= SIZE) s *= 2; return s }
    private fun decode(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample(o) })
    }

    private fun readAll(path: String): ByteArray? {
        val rd = Remote.reader(path)
        try {
            val sz = rd.size
            if (sz <= 0 || sz > 8_000_000) return null
            val b = ByteArray(sz.toInt()); var got = 0
            while (got < b.size) { val n = rd.read(got.toLong(), b, got, b.size - got); if (n <= 0) break; got += n }
            return if (got == b.size) b else null
        } finally { rd.close() }
    }

    private fun fromArt(t: Track): Bitmap? = if (t.art.isEmpty()) null else runCatching {
        Http.client.newCall(Request.Builder().url(t.art).build()).execute().use { r -> r.body?.bytes()?.let { decode(it) } }
    }.getOrNull()

    private fun fromFlacPicture(t: Track): Bitmap? = if (t.ext != "flac") null else runCatching {
        val rd = Remote.reader(t.path)
        try { FlacPcm.readMeta(ReaderSource(rd), true).picture?.let { decode(it) } } finally { rd.close() }
    }.getOrNull()

    private fun fromEmbedded(t: Track): Bitmap? = runCatching { Media.withMmr(t.path) { r -> r.embeddedPicture?.let { decode(it) } } }.getOrNull()

    private fun fromFolder(t: Track): Bitmap? {
        if (t.path.startsWith("http") || (t.dir.isNotEmpty() && !t.path.startsWith("/"))) return null
        for (n in names) { val b = runCatching { readAll(t.folder + "/" + n) }.getOrNull(); if (b != null) decode(b)?.let { return it } }
        return null
    }

    suspend fun get(t: Track): Bitmap? = withContext(Dispatchers.IO) {
        val key = t.src + "|" + t.folder + "|" + t.album
        cache.get(key)?.let { return@withContext it }
        if (key in none) return@withContext null
        val bmp = fromArt(t) ?: fromFlacPicture(t) ?: fromEmbedded(t) ?: fromFolder(t) ?: fromWeb(t)
        if (bmp != null) cache.put(key, bmp) else none.add(key)
        bmp
    }
}
