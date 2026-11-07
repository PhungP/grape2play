package com.grape.play

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit

data class DlnaServer(val name: String, val ctrl: String)

/** Một mục trong thư mục DLNA: thư mục con (container) hoặc bài hát. */
data class DEntry(val id: String, val title: String, val container: Boolean, val cls: String, val childCount: Int, val track: Track?)
data class DPage(val entries: List<DEntry>, val returned: Int, val total: Int)

/** UPnP/DLNA MediaServer: tìm bằng SSDP, duyệt bằng ContentDirectory.Browse. */
object Upnp {
    // Client riêng cho SOAP: có giới hạn tổng thời gian để một máy chủ chậm không làm treo cả lần quét.
    private val soap by lazy {
        Http.client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    }

    suspend fun discover(ctx: Context): List<DlnaServer> = withContext(Dispatchers.IO) {
        val locs = LinkedHashSet<String>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("grapeplay")?.also { it.setReferenceCounted(false); it.acquire() } }.getOrNull()
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 800
                val grp = InetAddress.getByName("239.255.255.250")
                for (st in listOf("urn:schemas-upnp-org:device:MediaServer:1", "urn:schemas-upnp-org:service:ContentDirectory:1")) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                    repeat(2) { sock.send(DatagramPacket(msg, msg.size, grp, 1900)) }
                }
                val end = System.currentTimeMillis() + 3500
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size); sock.receive(p)
                        String(p.data, 0, p.length).lines().firstOrNull { it.startsWith("location:", true) }?.substringAfter(':')?.trim()?.let { locs += it }
                    } catch (e: SocketTimeoutException) { }
                }
            }
        } finally { runCatching { lock?.release() } }
        locs.mapNotNull { runCatching { describe(it) }.getOrNull() }.distinctBy { it.ctrl }
    }

    private fun describe(loc: String): DlnaServer? {
        val xml = soap.newCall(Request.Builder().url(loc).build()).execute().use { it.body!!.string() }
        val p = Xml.newPullParser(); p.setInput(StringReader(xml))
        var name = ""; var base = ""; var st = ""; var ctrl = ""; var inSvc = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "friendlyName" -> { val v = p.nextText(); if (name.isEmpty()) name = v }
                    "URLBase" -> base = p.nextText()
                    "service" -> { inSvc = true; st = "" }
                    "serviceType" -> { val v = p.nextText(); if (inSvc) st = v }
                    "controlURL" -> { val c = p.nextText(); if (inSvc && st.contains("ContentDirectory") && ctrl.isEmpty()) ctrl = c }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "service") inSvc = false
            ev = p.next()
        }
        if (ctrl.isEmpty()) return null
        return DlnaServer(name.ifEmpty { "Máy chủ DLNA" }, URL(URL(if (base.isNotEmpty()) base else loc), ctrl.trim()).toString())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun once(ctrl: String, id: String, start: Int, count: Int): String {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\"><ObjectID>${esc(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter>" +
            "<StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"
        val rq = Request.Builder().url(ctrl).header("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())).build()
        return soap.newCall(rq).execute().use {
            val txt = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val desc = Regex("<errorDescription>(.*?)</errorDescription>", RegexOption.DOT_MATCHES_ALL).find(txt)?.groupValues?.get(1)?.trim()
                val code = Regex("<errorCode>(.*?)</errorCode>").find(txt)?.groupValues?.get(1)?.trim()
                throw IOException("UPnP lỗi ${it.code}" + (if (code != null) " ($code)" else "") + (if (!desc.isNullOrEmpty()) ": $desc" else ""))
            }
            txt
        }
    }

    /** Trả về (DIDL-Lite, NumberReturned, TotalMatches). Thử lại 1 lần, rồi hạ số lượng mỗi trang nếu máy chủ từ chối. */
    fun browse(ctrl: String, id: String, start: Int, count: Int): Triple<String, Int, Int> {
        var xml: String? = null
        var err: Exception? = null
        for (c in listOf(count, count, minOf(count, 30))) {
            try { xml = once(ctrl, id, start, c); break } catch (e: Exception) { err = e }
        }
        if (xml == null) throw (err ?: IOException("UPnP không phản hồi"))
        val p = Xml.newPullParser(); p.setInput(StringReader(xml))
        var res = ""; var nr = 0; var tm = 0; var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) when (p.name.substringAfter(':')) {
                "Result" -> res = p.nextText()
                "NumberReturned" -> nr = p.nextText().trim().toIntOrNull() ?: 0
                "TotalMatches" -> tm = p.nextText().trim().toIntOrNull() ?: 0
            }
            ev = p.next()
        }
        return Triple(res, nr, tm)
    }

    /** Một trang con của thư mục [id], đã phân tích thành danh sách có thứ tự (thư mục + bài hát). */
    fun page(s: Source, id: String, dir: String, start: Int, count: Int): DPage {
        val (didl, ret, total) = browse(s.url, id, start, count)
        val entries = if (didl.isBlank()) emptyList() else parseDidl(didl, s, dir)
        return DPage(entries, if (ret > 0) ret else entries.size, total)
    }

    /** Toàn bộ con của một thư mục (có chống lặp trang và giới hạn). */
    private fun children(s: Source, id: String, dir: String): List<DEntry> {
        val out = ArrayList<DEntry>()
        var start = 0; var firstPrev: String? = null
        repeat(300) {
            val pg = page(s, id, dir, start, 100)
            if (pg.entries.isEmpty()) return out
            val first = pg.entries.first().id
            if (first == firstPrev) return out // máy chủ bỏ qua StartingIndex → tránh lặp vô tận
            firstPrev = first
            out += pg.entries
            start += pg.returned
            if (pg.total in 1..start) return out
        }
        return out
    }

    private val SKIP = setOf("pictures", "photos", "photo", "images", "image", "video", "videos", "movies", "tv shows", "ảnh", "hình ảnh", "video")
    private val FOLDER_VIEW = setOf("browse folders", "folders", "folder", "by folder", "folder view", "file system", "filesystem", "files", "thư mục")

    /**
     * Quét nhạc từ máy chủ DLNA. Khác bản cũ: duyệt không đệ quy (không tràn), bỏ qua ảnh/video, ưu tiên "cây thư mục thật" nếu có
     * (tránh quét trùng các view Album/Artist/Genre), chống lặp, một thư mục lỗi không làm hỏng cả lần quét, có giới hạn thời gian và dừng được.
     * Trả về ghi chú (rỗng nếu êm).
     */
    fun crawl(s: Source, cancelled: () -> Boolean, progress: (folders: Int, tracks: Int, where: String) -> Unit, sink: (Track) -> Unit): String {
        val seenIds = HashSet<String>(); val seenUrls = HashSet<String>()
        var folders = 0; var tracks = 0; var errors = 0; var lastErr = ""
        val deadline = System.currentTimeMillis() + 12 * 60_000L
        val stack = ArrayDeque<Triple<String, String, Int>>()

        // Cấp gốc: lỗi ở đây phải báo ra ngoài (không kết nối được).
        progress(0, 0, "đang mở máy chủ…")
        val root = children(s, "0", "")
        val conts = root.filter { it.container && !isSkipped(it) }
        val view = conts.firstOrNull { it.title.trim().lowercase() in FOLDER_VIEW }
        val start = if (view != null) listOf(view) else conts
        for (e in root) if (e.track != null && seenUrls.add(e.track.path)) { sink(e.track); tracks++ }
        start.reversed().forEach { stack.addLast(Triple(it.id, it.title, 1)) }

        while (stack.isNotEmpty()) {
            if (cancelled()) return "đã dừng"
            if (System.currentTimeMillis() > deadline) return "dừng vì quá thời gian (đã lấy $tracks bài)"
            if (folders >= 5000 || tracks >= 30000) return "dừng vì đạt giới hạn ($folders thư mục, $tracks bài)"
            val (id, dir, depth) = stack.removeLast()
            if (!seenIds.add(id) || depth > 12) continue
            folders++
            progress(folders, tracks, dir.substringAfterLast('/'))
            val kids = try { children(s, id, dir) } catch (e: Exception) {
                errors++; lastErr = e.message ?: e.javaClass.simpleName
                if (errors >= 15 && tracks == 0) throw IOException("Máy chủ liên tục báo lỗi: $lastErr")
                continue
            }
            val subs = ArrayList<DEntry>()
            for (e in kids) {
                if (e.container) { if (!isSkipped(e)) subs += e }
                else e.track?.let { if (seenUrls.add(it.path)) { sink(it); tracks++ } }
            }
            subs.reversed().forEach { stack.addLast(Triple(it.id, if (dir.isEmpty()) it.title else "$dir/${it.title}", depth + 1)) }
        }
        return if (errors > 0) "$errors thư mục bị lỗi (lỗi cuối: $lastErr)" else ""
    }

    private fun isSkipped(e: DEntry): Boolean {
        val c = e.cls.lowercase()
        return e.title.trim().lowercase() in SKIP || c.contains("photo") || c.contains("video") || c.contains("image")
    }

    private fun parseDur(d: String?): Long {
        if (d.isNullOrBlank()) return 0
        val parts = d.split(':'); if (parts.size != 3) return 0
        return ((parts[0].toLongOrNull() ?: 0) * 3600 + (parts[1].toLongOrNull() ?: 0) * 60) * 1000 + ((parts[2].toDoubleOrNull() ?: 0.0) * 1000).toLong()
    }
    private fun extOf(mime: String, url: String): String = when {
        mime.contains("flac") -> "flac"
        mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
        mime.contains("wav") || mime.contains("wave") || mime.contains("l16") -> "wav"
        mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
        mime.contains("ogg") -> "ogg"
        else -> url.substringBefore('?').substringAfterLast('.', "").lowercase().take(5)
    }

    /** Phân tích DIDL-Lite theo thứ tự xuất hiện; so tên thẻ theo phần cục bộ nên không phụ thuộc tiền tố (dc:, upnp:…). */
    private fun parseDidl(didl: String, s: Source, dir: String): List<DEntry> {
        val p = Xml.newPullParser(); p.setInput(StringReader(didl))
        val out = ArrayList<DEntry>()
        var id = ""; var title = ""; var artist = ""; var creator = ""; var album = ""; var genre = ""; var no = 0; var art = ""; var cls = ""; var cc = 0
        var url = ""; var mime = ""; var dur = 0L; var sr = 0; var bits = 0; var ch = 0; var kb = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name.substringAfter(':')) {
                    "container", "item" -> {
                        id = p.getAttributeValue(null, "id") ?: ""
                        cc = p.getAttributeValue(null, "childCount")?.toIntOrNull() ?: -1
                        title = ""; artist = ""; creator = ""; album = ""; genre = ""; no = 0; art = ""; cls = ""
                        url = ""; mime = ""; dur = 0; sr = 0; bits = 0; ch = 0; kb = 0
                    }
                    "title" -> title = p.nextText()
                    "artist" -> { val v = p.nextText(); if (artist.isEmpty()) artist = v }
                    "creator" -> creator = p.nextText()
                    "album" -> album = p.nextText()
                    "genre" -> genre = p.nextText()
                    "originalTrackNumber" -> no = p.nextText().trim().toIntOrNull() ?: 0
                    "albumArtURI" -> { val v = p.nextText().trim(); if (art.isEmpty()) art = v }
                    "class" -> cls = p.nextText()
                    "res" -> {
                        val pi = p.getAttributeValue(null, "protocolInfo") ?: ""
                        val d = p.getAttributeValue(null, "duration")
                        val f = p.getAttributeValue(null, "sampleFrequency")?.toIntOrNull() ?: 0
                        val b = p.getAttributeValue(null, "bitsPerSample")?.toIntOrNull() ?: 0
                        val c = p.getAttributeValue(null, "nrAudioChannels")?.toIntOrNull() ?: 0
                        val br = p.getAttributeValue(null, "bitrate")?.toLongOrNull() ?: 0L
                        val u = p.nextText().trim()
                        if (url.isEmpty() && u.isNotEmpty()) { url = u; mime = pi.split(':').getOrElse(2) { "" }.lowercase(); dur = parseDur(d); sr = f; bits = b; ch = c; kb = (br * 8 / 1000).toInt() }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name.substringAfter(':')) {
                    "container" -> out += DEntry(id, title.ifEmpty { "(không tên)" }, true, cls, cc, null)
                    "item" -> {
                        val audio = cls.startsWith("object.item.audioItem") || (cls.isEmpty() && mime.startsWith("audio"))
                        if (audio && url.isNotEmpty()) {
                            val t = Track(url, title.ifEmpty { "Không tên" }, artist.ifEmpty { creator.ifEmpty { "Không rõ nghệ sĩ" } },
                                album.ifEmpty { dir.substringAfterLast('/').ifEmpty { "Không rõ album" } }, genre.ifEmpty { "Không rõ thể loại" },
                                dur, no, s.id, extOf(mime, url), art, dir.ifEmpty { s.name }, sr, bits, ch, kb)
                            out += DEntry(id, t.title, false, cls, 0, t)
                        }
                    }
                }
            }
            ev = p.next()
        }
        return out
    }
}
