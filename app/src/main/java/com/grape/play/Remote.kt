@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.grape.play

import android.media.MediaDataSource
import android.net.Uri
import android.util.Base64
import android.util.Xml
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.Security
import java.util.EnumSet
import java.util.concurrent.TimeUnit

interface RemoteReader {
    val size: Long
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
    fun close()
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
}

/** Đọc file cục bộ theo khối 256 KB căn hàng 64 KB, đọc đủ dữ liệu — ổn định hơn cho ổ NTFS/USB. */
class LocalReader(path: String) : RemoteReader {
    private val f = RandomAccessFile(path, "r")
    override val size: Long = f.length()
    private val cache = ByteArray(256 * 1024)
    private var bs = -1L
    private var bl = 0
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        if (bs < 0 || pos < bs || pos >= bs + bl) {
            val aligned = pos - pos % (64 * 1024)
            val want = minOf(cache.size.toLong(), size - aligned).toInt()
            f.seek(aligned)
            var got = 0
            while (got < want) { val r = f.read(cache, got, want - got); if (r <= 0) break; got += r }
            bs = aligned; bl = got
            if (bl <= (pos - aligned).toInt()) return -1
        }
        val n = minOf(len, (bs + bl - pos).toInt())
        System.arraycopy(cache, (pos - bs).toInt(), buf, off, n)
        return n
    }
    override fun close() { runCatching { f.close() } }
}

/** HTTP/WebDAV/DLNA: giữ một kết nối đọc tuần tự, chỉ mở lại khi tua. */
class HttpReader(private val url: String, private val auth: String?, private val authFn: (() -> String?)? = null) : RemoteReader {
    private var resp: Response? = null
    private var ins: InputStream? = null
    private var spos = -1L
    private fun req(b: Request.Builder): Request.Builder { (authFn?.invoke() ?: auth)?.let { b.header("Authorization", it) }; return b }
    override val size: Long = probeSize()

    private fun probeSize(): Long {
        runCatching {
            Http.client.newCall(req(Request.Builder().url(url).head()).build()).execute().use { r ->
                if (r.isSuccessful) { val l = r.header("Content-Length")?.toLongOrNull(); if (l != null && l > 0) return l }
            }
        }
        Http.client.newCall(req(Request.Builder().url(url).header("Range", "bytes=0-0")).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { return it }
            if (r.code == 200) r.header("Content-Length")?.toLongOrNull()?.let { return it }
        }
        return -1L
    }

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (size > 0 && pos >= size) return -1
        if (ins == null || spos != pos) {
            closeStream()
            val r = Http.client.newCall(req(Request.Builder().url(url).header("Range", "bytes=$pos-")).build()).execute()
            if (!r.isSuccessful) { r.close(); throw IOException("HTTP ${r.code}") }
            resp = r; ins = r.body!!.byteStream()
            if (r.code == 200 && pos > 0) { var s = pos; while (s > 0) { val k = ins!!.skip(s); if (k <= 0) break; s -= k } }
            spos = pos
        }
        val n = ins!!.read(buf, off, len)
        if (n > 0) spos += n
        return n
    }
    private fun closeStream() { runCatching { ins?.close() }; runCatching { resp?.close() }; ins = null; resp = null; spos = -1 }
    override fun close() = closeStream()
}

object Smb {
    private val client: SMBClient
    private val shares = HashMap<String, DiskShare>()
    init {
        // Android có sẵn provider "BC" cũ; smbj cần bản BouncyCastle đầy đủ (MD4, v.v.)
        runCatching {
            val bc = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider").getDeclaredConstructor().newInstance() as java.security.Provider
            Security.removeProvider("BC"); Security.insertProviderAt(bc, 1)
        }
        client = SMBClient(SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build())
    }

    @Synchronized fun share(s: Source): DiskShare {
        shares[s.id]?.let { if (it.isConnected) return it }
        shares.remove(s.id)
        val host = s.host.substringBefore(':'); val port = s.host.substringAfter(':', "445").toIntOrNull() ?: 445
        val conn = client.connect(host, port)
        val auth = if (s.user.isEmpty()) AuthenticationContext.guest() else {
            val dom = if (s.user.contains('\\')) s.user.substringBefore('\\') else ""
            AuthenticationContext(s.user.substringAfter('\\'), s.pass.toCharArray(), dom)
        }
        val sh = conn.authenticate(auth).connectShare(s.share) as DiskShare
        shares[s.id] = sh
        return sh
    }
    @Synchronized fun reset(id: String) { shares.remove(id)?.let { runCatching { it.close() } } }
}

class SmbReader(private val s: Source, rel: String, cacheKb: Int = 256) : RemoteReader {
    private val path = rel.replace('/', '\\')
    private val file: SmbFile = open()
    override val size: Long = file.fileInformation.standardInformation.endOfFile
    private val cache = ByteArray(cacheKb * 1024)
    private var bs = 0L
    private var bl = 0

    private fun open(): SmbFile {
        fun go() = Smb.share(s).openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        return try { go() } catch (e: Exception) { Smb.reset(s.id); go() }
    }

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        if (pos < bs || pos >= bs + bl) {
            val want = minOf(cache.size.toLong(), size - pos).toInt()
            var got = 0
            while (got < want) { val r = file.read(cache, pos + got, got, want - got); if (r <= 0) break; got += r }
            bs = pos; bl = got
            if (bl == 0) return -1
        }
        val n = minOf(len, (bs + bl - pos).toInt())
        System.arraycopy(cache, (pos - bs).toInt(), buf, off, n)
        return n
    }
    override fun close() { runCatching { file.close() } }
}

class RemoteMds(private val r: RemoteReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val n = r.read(position, buffer, offset, size)
        return if (n <= 0) -1 else n
    }
    override fun getSize(): Long = r.size
    override fun close() { r.close() }
}

/** DataSource cho ExoPlayer: smb://, dav:// tự xử lý; http(s) (DLNA) giao cho DefaultHttpDataSource. */
class NasDataSource : BaseDataSource(true) {
    private var inner: DataSource? = null
    private var rd: RemoteReader? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val sch = dataSpec.uri.scheme
        if (sch == "http" || sch == "https") {
            val d = DefaultHttpDataSource.Factory().createDataSource()
            inner = d
            val n = d.open(dataSpec)
            opened = true; transferStarted(dataSpec)
            return n
        }
        val r = Remote.reader(Remote.fromUri(dataSpec.uri))
        rd = r
        pos = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.size - pos
        opened = true; transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        inner?.let { val n = it.read(buffer, offset, length); if (n > 0) bytesTransferred(n); return n }
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val n = rd!!.read(pos, buffer, offset, want)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri
    override fun close() {
        runCatching { inner?.close() }; inner = null
        rd?.close(); rd = null; uri = null
        if (opened) { opened = false; transferEnded() }
    }
}

object Remote {
    private fun split(p: String, prefix: String): Pair<String, String> { val r = p.removePrefix(prefix); return r.substringBefore('/') to r.substringAfter('/', "") }
    fun basic(s: Source): String? = if (s.user.isEmpty()) null else "Basic " + Base64.encodeToString("${s.user}:${s.pass}".toByteArray(), Base64.NO_WRAP)
    fun davUrl(s: Source, rel: String): String {
        val base = s.url.trimEnd('/')
        val enc = rel.split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }
        return if (enc.isEmpty()) "$base/" else "$base/$enc"
    }

    fun reader(path: String): RemoteReader = when {
        path.startsWith("/") -> LocalReader(path)
        path.startsWith("smb://") -> { val (id, rel) = split(path, "smb://"); SmbReader(SourceStore.get(id) ?: throw IOException("Nguồn đã bị xoá"), rel) }
        path.startsWith("dav://") -> { val (id, rel) = split(path, "dav://"); val s = SourceStore.get(id) ?: throw IOException("Nguồn đã bị xoá"); HttpReader(davUrl(s, rel), basic(s)) }
        path.startsWith("gd://") || path.startsWith("od://") -> Cloud.reader(path)
        else -> HttpReader(path, null)
    }

    fun toUri(path: String): Uri = when {
        path.startsWith("/storage/") && !path.startsWith("/storage/emulated") ->
            Uri.Builder().scheme("usb").encodedPath(path.split('/').joinToString("/") { Uri.encode(it) }).build()
        path.startsWith("/") -> Uri.fromFile(File(path))
        path.startsWith("smb://") || path.startsWith("dav://") || path.startsWith("gd://") || path.startsWith("od://") -> {
            val sch = path.substringBefore("://"); val (id, rel) = split(path, "$sch://")
            Uri.Builder().scheme(sch).authority(id).encodedPath("/" + rel.split('/').joinToString("/") { Uri.encode(it) }).build()
        }
        else -> Uri.parse(path)
    }
    /** URI phát của một bài; URL DLNA là FLAC nhưng không có đuôi .flac thì gắn gợi ý để vào bộ giải mã. */
    fun toUri(t: Track): Uri {
        val u = toUri(t.path)
        return if (Decoders.isDecoded(t.ext) && u.path.orEmpty().lowercase().endsWith("." + t.ext).not()) u.buildUpon().fragment(Decoders.hint(t.ext)).build() else u
    }
    fun fromUri(u: Uri): String = when (u.scheme) {
        "smb", "dav", "gd", "od" -> "${u.scheme}://${u.authority}/${u.path.orEmpty().removePrefix("/")}"
        "file", "usb" -> u.path.orEmpty()
        "http", "https" -> u.buildUpon().fragment(null).build().toString()
        else -> u.toString()
    }

    /** Liệt kê mọi file nhạc trong nguồn SMB (đường dẫn tương đối, dùng '/'). */
    fun walkSmb(s: Source, sink: (String) -> Unit) {
        val share = Smb.share(s)
        fun rec(dir: String, depth: Int) {
            if (depth > 12) return
            for (i in share.list(dir.replace('/', '\\'))) {
                val n = i.fileName
                if (n == "." || n == ".." || n.startsWith(".") || n.startsWith("@") || n == "#recycle") continue
                val p = if (dir.isEmpty()) n else "$dir/$n"
                if ((i.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L) rec(p, depth + 1)
                else if (isAudioName(n)) sink(p)
            }
        }
        rec(s.root.trim('/'), 0)
    }

    private fun propfind(s: Source, rel: String): List<Pair<String, Boolean>> {
        val url = davUrl(s, rel).let { if (it.endsWith("/")) it else "$it/" }
        val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>".toRequestBody("application/xml".toMediaType())
        val rq = Request.Builder().url(url).method("PROPFIND", body).header("Depth", "1").apply { basic(s)?.let { header("Authorization", it) } }.build()
        val xml = Http.client.newCall(rq).execute().use { if (!it.isSuccessful) throw IOException("WebDAV lỗi ${it.code}"); it.body!!.string() }
        val out = ArrayList<Pair<String, Boolean>>()
        val p = Xml.newPullParser(); p.setInput(StringReader(xml))
        var href = ""; var dir = false; var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val nm = p.name?.substringAfter(':')
            if (ev == XmlPullParser.START_TAG) {
                if (nm == "response") { href = ""; dir = false }
                else if (nm == "href") href = p.nextText().trim()
                else if (nm == "collection") dir = true
            } else if (ev == XmlPullParser.END_TAG && nm == "response" && href.isNotEmpty()) {
                val raw = runCatching { java.net.URI(href).rawPath }.getOrNull() ?: href
                out += Uri.decode(raw) to dir
            }
            ev = p.next()
        }
        return out
    }

    fun walkDav(s: Source, sink: (String) -> Unit) {
        val basePath = Uri.decode(runCatching { java.net.URI(s.url).rawPath }.getOrNull() ?: "").trimEnd('/')
        fun rec(rel: String, depth: Int) {
            if (depth > 12) return
            for ((hp, isDir) in propfind(s, rel)) {
                val child = hp.trimEnd('/').removePrefix(basePath).trim('/')
                if (child.isEmpty() || child == rel.trim('/')) continue
                val name = child.substringAfterLast('/')
                if (name.startsWith(".")) continue
                if (isDir) rec(child, depth + 1) else if (isAudioName(name)) sink(child)
            }
        }
        rec("", 0)
    }

    fun test(s: Source) {
        when (s.type) {
            "smb" -> {
                require(s.host.isNotEmpty() && s.share.isNotEmpty()) { "Cần nhập địa chỉ máy chủ và thư mục chia sẻ" }
                Smb.reset(s.id); Smb.share(s).list(s.root.trim('/').replace('/', '\\'))
            }
            "dav" -> { require(s.url.startsWith("http")) { "Địa chỉ phải bắt đầu bằng http:// hoặc https://" }; propfind(s, "") }
            else -> { require(s.url.startsWith("http")) { "Chưa chọn máy chủ DLNA" }; Upnp.browse(s.url, "0", 0, 1) }
        }
    }

    suspend fun discoverSmb(): List<String> = withContext(Dispatchers.IO) {
        val prefix = NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.interfaceAddresses }?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress?.substringBeforeLast('.')
            ?: return@withContext emptyList()
        coroutineScope {
            (1..254).map { i ->
                async { val ip = "$prefix.$i"; try { Socket().use { it.connect(InetSocketAddress(ip, 445), 500) }; ip } catch (e: Exception) { null } }
            }.awaitAll().filterNotNull()
        }
    }
}

object Media {
    private fun http(p: String) = p.startsWith("http://") || p.startsWith("https://")

    fun <T> withMmr(path: String, block: (android.media.MediaMetadataRetriever) -> T): T {
        val r = android.media.MediaMetadataRetriever()
        var rd: RemoteReader? = null
        try {
            when {
                path.startsWith("/") -> r.setDataSource(path)
                http(path) -> r.setDataSource(path, HashMap<String, String>())
                else -> { rd = Remote.reader(path); r.setDataSource(RemoteMds(rd)) }
            }
            return block(r)
        } finally { runCatching { r.release() }; rd?.close() }
    }

    fun <T> withExtractor(path: String, block: (android.media.MediaExtractor) -> T): T {
        val e = android.media.MediaExtractor()
        var rd: RemoteReader? = null
        try {
            if (path.startsWith("/") || http(path)) e.setDataSource(path) else { rd = Remote.reader(path); e.setDataSource(RemoteMds(rd)) }
            return block(e)
        } finally { runCatching { e.release() }; rd?.close() }
    }
}
