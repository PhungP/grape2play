package com.grape.play

import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Máy chủ HTTP nhỏ trên TV: để thiết bị bên ngoài (WiiM Ultra, Network Player UPnP, Chromecast/Nest) kéo file nhạc về phát.
 * Phát NGUYÊN BẢN file gốc (không chuyển mã) — hỗ trợ Range để tua. Nguồn: ổ TV/USB, SMB, WebDAV, Google Drive, OneDrive đều qua Remote.reader.
 * Bài đã là URL http (máy chủ DLNA, kể cả chính ổ USB của WiiM) được đưa thẳng cho thiết bị, không đi vòng qua TV.
 */
object StreamServer {
    private val tracks = ConcurrentHashMap<String, String>() // id → path
    private val counter = AtomicInteger(0)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "grape-stream").also { it.isDaemon = true } }
    @Volatile private var server: ServerSocket? = null
    @Volatile var port = 0
        private set

    @Synchronized private fun ensure() {
        if (server != null) return
        var last: Exception? = null
        for (p in 8899..8920) {
            try {
                val s = ServerSocket(p); s.reuseAddress = true
                server = s; port = p
                pool.execute { accept(s) }
                return
            } catch (e: Exception) { last = e }
        }
        throw IOException("Không mở được cổng phát nhạc: ${last?.message}")
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: Exception) { break }
            pool.execute { serve(c) }
        }
    }

    fun mime(ext: String) = when (ext.lowercase()) {
        "flac" -> "audio/flac"
        "dsf" -> "audio/x-dsf"
        "dff" -> "audio/x-dff"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a", "alac" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    /** URL để thiết bị khác tải bài này. */
    /** URL do DoP tạo ra (WAV 24-bit/176,4 kHz) có id bắt đầu bằng "dop-". */
    fun isDopUrl(url: String) = url.contains("/t/dop-")
    fun isDsdFile(t: Track) = t.ext == "dsf" || t.ext == "dff"

    /** File DSD này đóng gói DoP được không (DSD64, ≤ 2 kênh)? Đọc ~100 byte đầu file. */
    fun dopEligible(t: Track): Boolean {
        if (t.ext != "dsf" && t.ext != "dff") return false
        return try {
            val rd = Remote.reader(t.path)
            try { DopPcm.supports(DsdPcm.readMeta(ReaderSource(rd))) } finally { rd.close() }
        } catch (e: Exception) { false }
    }

    /** mode = DSD_DOP: bài DSD64 phát dưới dạng DoP (WAV 24-bit/176,4 kHz); DSD_RAW: file gốc. Chỉ dùng cho thiết bị UPnP. */
    fun urlFor(t: Track, mode: Int = AudioSettings.DSD_RAW): String {
        val asDop = mode == AudioSettings.DSD_DOP && dopEligible(t)
        if (!asDop && (t.path.startsWith("http://") || t.path.startsWith("https://"))) return t.path
        ensure()
        val ip = CloudAuth.lanIp() ?: throw IOException("TV chưa kết nối mạng LAN/Wi-Fi")
        val id = (if (asDop) "dop-" else "") + Integer.toHexString(t.path.hashCode()) + "-" + counter.incrementAndGet()
        if (tracks.size > 400) tracks.keys.take(200).forEach { tracks.remove(it) }
        tracks[id] = t.path
        return "http://$ip:$port/t/$id.${if (asDop) "wav" else t.ext.ifEmpty { "bin" }}"
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun serve(c: Socket) {
        try {
            c.use { sock ->
                sock.soTimeout = 30_000
                sock.tcpNoDelay = true
                val inp = sock.getInputStream().buffered()
                val out = sock.getOutputStream().buffered(64 * 1024)
                val parts = (readLine(inp) ?: return).split(' ')
                val method = parts[0]
                val target = parts.getOrElse(1) { "/" }
                var range: String? = null
                while (true) {
                    val h = readLine(inp) ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("range:", true)) range = h.substringAfter(':').trim()
                }
                fun reply(code: Int, text: String, extra: String = "", body: ByteArray? = null) {
                    out.write("HTTP/1.1 $code $text\r\n${extra}Connection: close\r\nContent-Length: ${body?.size ?: 0}\r\n\r\n".toByteArray())
                    if (body != null && method != "HEAD") out.write(body)
                    out.flush()
                }
                val id = target.substringAfter("/t/", "").substringBefore('?').substringBeforeLast('.')
                val path = tracks[id]
                if (path == null || (method != "GET" && method != "HEAD")) { reply(404, "Not Found"); return }
                val ext = target.substringBefore('?').substringAfterLast('.', "")
                val raw = try { Remote.reader(path) } catch (e: Exception) { reply(500, "Error"); return }
                // DoP: tạo luồng WAV 24-bit từ file DSD gốc, đọc/tua tại vị trí bất kỳ như file thường
                val rd: RemoteReader = if (!id.startsWith("dop-")) raw else try {
                    val ds = DopPcm(ReaderSource(raw))
                    object : RemoteReader {
                        override val size: Long = ds.size()
                        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int = ds.read(pos, buf, off, len)
                        override fun close() = ds.close()
                    }
                } catch (e: Exception) { runCatching { raw.close() }; reply(500, "Error"); return }
                try {
                    val size = rd.size
                    var start = 0L
                    var end = if (size > 0) size - 1 else Long.MAX_VALUE
                    var partial = false
                    val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
                    if (m != null && size > 0) {
                        val a = m.groupValues[1]; val b = m.groupValues[2]
                        if (a.isNotEmpty()) { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1) }
                        else if (b.isNotEmpty()) { start = maxOf(0L, size - b.toLong()) }
                        partial = true
                        if (start >= size) { reply(416, "Range Not Satisfiable", "Content-Range: bytes */$size\r\n"); return }
                    }
                    val len = if (size > 0) end - start + 1 else -1L
                    val head = StringBuilder()
                    head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    head.append("Content-Type: ${mime(ext)}\r\nAccept-Ranges: bytes\r\n")
                    head.append("transferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                    head.append("Access-Control-Allow-Origin: *\r\n")
                    if (partial) head.append("Content-Range: bytes $start-$end/$size\r\n")
                    if (len >= 0) head.append("Content-Length: $len\r\n")
                    head.append("Connection: close\r\n\r\n")
                    out.write(head.toString().toByteArray())
                    if (method == "GET") {
                        val buf = ByteArray(128 * 1024)
                        var pos = start
                        while (len < 0 || pos <= end) {
                            val want = if (len < 0) buf.size else minOf(buf.size.toLong(), end - pos + 1).toInt()
                            val n = rd.read(pos, buf, 0, want)
                            if (n <= 0) break
                            out.write(buf, 0, n); pos += n
                        }
                    }
                    out.flush()
                } finally { rd.close() }
            }
        } catch (e: Exception) { /* thiết bị ngắt kết nối giữa chừng (tua, đổi bài) là chuyện bình thường */ }
    }
}
