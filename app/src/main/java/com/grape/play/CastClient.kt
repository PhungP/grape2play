package com.grape.play

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Google Cast (giao thức CASTV2) viết trực tiếp, không cần Cast SDK: TLS tới cổng 8009, tin nhắn protobuf,
 * mở "Default Media Receiver" rồi LOAD URL bài hát (do StreamServer của TV hoặc máy chủ DLNA cung cấp).
 * Chứng chỉ thiết bị Cast là tự ký nên chấp nhận mọi chứng chỉ — chỉ dùng trong mạng LAN của chính bạn.
 */
class CastController(private val r: Renderer) : RemoteController() {
    private companion object {
        const val NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEART = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECV = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        const val APP_ID = "CC1AD845" // Default Media Receiver
    }

    private var sock: SSLSocket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    private val ids = AtomicInteger(1)
    @Volatile private var connected = false
    @Volatile private var transportId = ""
    @Volatile private var sessionId = ""
    @Volatile private var linkedTransport = ""
    @Volatile private var mediaSession = 0
    @Volatile private var pState = ""
    @Volatile private var idleReason = ""
    @Volatile private var curSec = 0.0
    @Volatile private var durSec = 0.0
    @Volatile private var statusAt = 0L
    @Volatile private var active = false
    @Volatile private var sawPlaying = false
    @Volatile private var userStopped = false
    @Volatile private var loadedAt = 0L
    @Volatile private var trackDur = 0L
    @Volatile private var seq = 0

    init { scope.launch { pollLoop() } }

    // ---------- protobuf CastMessage ----------
    private fun field(o: ByteArrayOutputStream, tag: Int, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.write(tag)
        var n = b.size
        while (n >= 0x80) { o.write((n and 0x7f) or 0x80); n = n shr 7 }
        o.write(n); o.write(b, 0, b.size)
    }

    private fun send(ns: String, dest: String, json: String) {
        val o = ByteArrayOutputStream()
        o.write(0x08); o.write(0x00)                    // protocol_version = CASTV2_1_0
        field(o, 0x12, "sender-0"); field(o, 0x1a, dest); field(o, 0x22, ns)
        o.write(0x28); o.write(0x00)                    // payload_type = STRING
        field(o, 0x32, json)
        val p = o.toByteArray()
        val frame = ByteArray(4 + p.size)
        frame[0] = (p.size shr 24).toByte(); frame[1] = (p.size shr 16).toByte(); frame[2] = (p.size shr 8).toByte(); frame[3] = p.size.toByte()
        System.arraycopy(p, 0, frame, 4, p.size)
        synchronized(writeLock) { val w = out ?: throw IOException("Chưa kết nối tới ${r.name}"); w.write(frame); w.flush() }
    }
    private fun next() = ids.getAndIncrement()

    /** (namespace, payload) của một CastMessage. */
    private fun decode(b: ByteArray): Pair<String, String> {
        var i = 0; var ns = ""; var payload = ""
        fun varint(): Int { var res = 0; var sh = 0; while (true) { val x = b[i++].toInt() and 0xff; res = res or ((x and 0x7f) shl sh); if (x and 0x80 == 0) return res; sh += 7 } }
        while (i < b.size) {
            val tag = varint(); val f = tag shr 3
            when (tag and 7) {
                0 -> varint()
                2 -> { val l = varint(); val s = String(b, i, l, Charsets.UTF_8); i += l; if (f == 4) ns = s else if (f == 6) payload = s }
                else -> return ns to payload
            }
        }
        return ns to payload
    }

    // ---------- kết nối ----------
    private fun connect() {
        runCatching { sock?.close() }
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS"); ctx.init(null, arrayOf<TrustManager>(tm), null)
        val s = ctx.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(r.host, if (r.port > 0) r.port else 8009), 6000)
        s.soTimeout = 25_000
        s.startHandshake()
        sock = s; out = s.outputStream
        transportId = ""; sessionId = ""; linkedTransport = ""; mediaSession = 0
        connected = true
        thread(isDaemon = true, name = "grape-cast-rx") {
            try {
                val inp = DataInputStream(s.inputStream.buffered())
                while (connected) {
                    val len = inp.readInt()
                    if (len <= 0 || len > 2_000_000) break
                    val buf = ByteArray(len); inp.readFully(buf)
                    val (ns, payload) = decode(buf)
                    handle(ns, payload)
                }
            } catch (e: Exception) { }
            if (connected && sock === s) { connected = false; if (active) { active = false; onError("${r.name} đã ngắt kết nối") } }
        }
        thread(isDaemon = true, name = "grape-cast-hb") {
            while (connected && sock === s) {
                try { send(NS_HEART, "receiver-0", "{\"type\":\"PING\"}") } catch (e: Exception) { break }
                try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
            }
        }
        send(NS_CONN, "receiver-0", "{\"type\":\"CONNECT\"}")
    }

    private fun handle(ns: String, payload: String) {
        val j = runCatching { JSONObject(payload) }.getOrNull() ?: return
        when (ns) {
            NS_HEART -> if (j.optString("type") == "PING") runCatching { send(NS_HEART, "receiver-0", "{\"type\":\"PONG\"}") }
            NS_CONN -> if (j.optString("type") == "CLOSE") { transportId = ""; linkedTransport = ""; mediaSession = 0 }
            NS_RECV -> if (j.optString("type") == "RECEIVER_STATUS") {
                val apps = j.optJSONObject("status")?.optJSONArray("applications")
                var found = false
                for (k in 0 until (apps?.length() ?: 0)) {
                    val a = apps!!.getJSONObject(k)
                    if (a.optString("appId") == APP_ID) { transportId = a.optString("transportId"); sessionId = a.optString("sessionId"); found = true }
                }
                if (!found) { transportId = ""; linkedTransport = ""; mediaSession = 0 }
            }
            NS_MEDIA -> when (j.optString("type")) {
                "MEDIA_STATUS" -> {
                    val st = j.optJSONArray("status")?.optJSONObject(0) ?: return
                    mediaSession = st.optInt("mediaSessionId", mediaSession)
                    pState = st.optString("playerState"); idleReason = st.optString("idleReason")
                    curSec = st.optDouble("currentTime", curSec); statusAt = System.currentTimeMillis()
                    st.optJSONObject("media")?.optDouble("duration", 0.0)?.takeIf { it > 0 }?.let { durSec = it }
                }
                "LOAD_FAILED", "INVALID_REQUEST", "LOAD_CANCELLED" -> if (active) { active = false; onError("${r.name} không tải được bài này (${j.optString("reason", j.optString("type"))})") }
            }
        }
    }

    private fun ensureSession() {
        if (!connected || sock?.isClosed != false) connect()
        if (transportId.isEmpty()) {
            send(NS_RECV, "receiver-0", "{\"type\":\"LAUNCH\",\"appId\":\"$APP_ID\",\"requestId\":${next()}}")
            val end = System.currentTimeMillis() + 12_000
            while (transportId.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(100)
            if (transportId.isEmpty()) throw IOException("thiết bị không mở được trình phát Cast")
        }
        if (linkedTransport != transportId) { send(NS_CONN, transportId, "{\"type\":\"CONNECT\"}"); linkedTransport = transportId }
    }

    // ---------- RemoteController ----------
    override fun load(t: Track, startMs: Long) {
        val my = ++seq
        active = false; sawPlaying = false; userStopped = false
        trackDur = t.durationMs
        scope.launch {
            try {
                ensureSession()
                val url = StreamServer.urlFor(t)
                val md = JSONObject().put("metadataType", 3).put("title", t.title).put("artist", t.artist).put("albumName", t.album)
                if (t.art.startsWith("http")) md.put("images", org.json.JSONArray().put(JSONObject().put("url", t.art)))
                val media = JSONObject().put("contentId", url).put("streamType", "BUFFERED").put("contentType", StreamServer.mime(t.ext)).put("metadata", md)
                if (t.durationMs > 0) media.put("duration", t.durationMs / 1000.0)
                pState = ""; idleReason = ""; mediaSession = 0; curSec = startMs / 1000.0; durSec = t.durationMs / 1000.0
                send(NS_MEDIA, transportId, JSONObject().put("type", "LOAD").put("requestId", next()).put("sessionId", sessionId)
                    .put("media", media).put("autoplay", true).put("currentTime", startMs / 1000.0).toString())
                if (my != seq) return@launch
                loadedAt = System.currentTimeMillis(); active = true
            } catch (e: Exception) { onError("Không phát được tới ${r.name}: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private fun mediaCmd(type: String, extra: JSONObject.() -> Unit = {}) {
        scope.launch {
            try {
                if (transportId.isEmpty() || mediaSession == 0) return@launch
                val m = JSONObject().put("type", type).put("mediaSessionId", mediaSession).put("requestId", next())
                m.extra()
                send(NS_MEDIA, transportId, m.toString())
            } catch (e: Exception) { onError("Lệnh tới ${r.name} lỗi: ${e.message}") }
        }
    }
    override fun play() { userStopped = false; mediaCmd("PLAY") }
    override fun pause() { mediaCmd("PAUSE") }
    override fun seek(ms: Long) { curSec = ms / 1000.0; statusAt = System.currentTimeMillis(); mediaCmd("SEEK") { put("currentTime", ms / 1000.0) } }
    override fun stop() { userStopped = true; active = false; mediaCmd("STOP") }

    override fun onClose() {
        connected = false
        runCatching { sock?.close() }
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            delay(1000)
            if (!active) continue
            try {
                if (!connected) { active = false; onError("${r.name} đã ngắt kết nối"); continue }
                if (transportId.isNotEmpty()) send(NS_MEDIA, transportId, "{\"type\":\"GET_STATUS\",\"requestId\":${next()}}")
                val now = System.currentTimeMillis()
                val dur = if (durSec > 0) (durSec * 1000).toLong() else trackDur
                when (pState) {
                    "PLAYING" -> {
                        sawPlaying = true
                        val pos = (curSec * 1000).toLong() + (now - statusAt).coerceIn(0, 3000)
                        onState(true, pos, dur)
                    }
                    "BUFFERING" -> onState(true, (curSec * 1000).toLong(), dur)
                    "PAUSED" -> onState(false, (curSec * 1000).toLong(), dur)
                    "IDLE" -> when {
                        idleReason == "FINISHED" && !userStopped -> { active = false; onEnded() }
                        idleReason == "ERROR" -> { active = false; onError("${r.name} báo lỗi khi phát bài này") }
                    }
                    else -> if (now - loadedAt > 25_000) { active = false; onError("${r.name} không phản hồi khi phát bài này") }
                }
            } catch (e: Exception) { /* thử lại ở vòng sau */ }
        }
    }
}
