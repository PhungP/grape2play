package com.grape.play

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Điều khiển UPnP AVTransport (WiiM Ultra, Network Player, TV…): đưa URL bài hát cho thiết bị tự kéo về phát. */
class UpnpController(private val r: Renderer) : RemoteController() {
    private val http = Http.client.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()

    @Volatile private var active = false
    @Volatile private var sawPlaying = false
    @Volatile private var userStopped = false
    @Volatile private var loadedAt = 0L
    @Volatile private var pendingSeek = 0L
    @Volatile private var trackDur = 0L
    @Volatile private var seq = 0

    /** Bài kế tiếp để nạp sẵn cho thiết bị (do Playback cung cấp) và gọi khi thiết bị đã tự qua bài đó. */
    @Volatile var nextProvider: () -> Track? = { null }
    @Volatile var onAdvanced: (Track) -> Unit = {}
    @Volatile private var nextSupported = true
    @Volatile private var armed: Track? = null
    @Volatile private var armedKey = ""
    @Volatile private var armTried = -1
    @Volatile private var lastPos = 0L

    // ---- Nhiều máy cùng điều khiển một thiết bị nhận, và theo dõi chất lượng liên lạc ----
    private val knownUrls = java.util.Collections.synchronizedList(ArrayList<String>())   // các URL do chính app này nạp (bài hiện tại, bài nạp sẵn)
    @Volatile private var health = RemoteHealth(viaTv = true)
    private val series = ArrayDeque<Int>()
    private var drops = 0
    private var ok = true
    private var lat = 0

    private fun same(a: String, b: String) = a == b || key(a) == key(b) || a.substringAfterLast('/').substringBefore('?') == b.substringAfterLast('/').substringBefore('?')
    private fun foreign(cu: String) = cu.isNotEmpty() && knownUrls.none { same(it, cu) }
    private fun remember(url: String) { knownUrls.add(url); while (knownUrls.size > 4) knownUrls.removeAt(0) }
    private fun publish(st: String, other: Boolean = false) {
        health = RemoteHealth(lat, series.toList(), drops, st, armed != null, nextSupported, health.lastSwitchAt, true, other)
        RemoteStats.health.value = health
    }
    private fun record(ms: Int) {   // ms = 0: lần hỏi thất bại
        series.addLast(ms); while (series.size > 60) series.removeFirst()
        if (ms > 0) { lat = if (lat == 0) ms else (lat * 3 + ms) / 4 }
        if (ms == 0 && ok) { drops++; AppLog.w("UPnP", "${r.name}: mất phản hồi lần thứ $drops") }
        ok = ms > 0
    }

    init { scope.launch { pollLoop() } }

    private fun key(u: String) = u.substringAfter("/t/", u).substringBefore('?')
    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    /** Báo cho thiết bị bài kế tiếp: thiết bị hỗ trợ sẽ tự chuyển bài liền mạch, không cần app (hay TV) nạp bài lúc hết bài. */
    private fun arm() {
        val n = nextProvider() ?: return
        try {
            val mode = if (StreamServer.isDsdFile(n)) AudioSettings.dsdRemote else AudioSettings.DSD_RAW
            val url = StreamServer.urlFor(n, mode)
            call("SetNextAVTransportURI", "<NextURI>${xml(url)}</NextURI><NextURIMetaData>${xml(didl(n, url))}</NextURIMetaData>")
            armed = n; armedKey = key(url); remember(url)
        } catch (e: Exception) {
            armed = null
            // 401 Invalid Action / 602 Not implemented: thiết bị không hỗ trợ → dùng cách cũ (app nạp bài khi hết bài)
            val m = e.message.orEmpty()
            if (listOf("(401)", "(602)", "(401 ", "Invalid Action").any { it in m }) nextSupported = false
        }
    }

    // ---- SOAP ----
    private fun call(action: String, args: String): String {
        val svc = "urn:schemas-upnp-org:service:AVTransport:1"
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$svc\"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
        val rq = Request.Builder().url(r.avCtrl).header("SOAPACTION", "\"$svc#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())).build()
        return http.newCall(rq).execute().use {
            val txt = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val d = Regex("<errorDescription>(.*?)</errorDescription>", RegexOption.DOT_MATCHES_ALL).find(txt)?.groupValues?.get(1)?.trim()
                val c = Regex("<errorCode>(.*?)</errorCode>").find(txt)?.groupValues?.get(1)?.trim()
                throw IOException("$action lỗi ${it.code}" + (if (c != null) " ($c)" else "") + (if (!d.isNullOrEmpty()) ": $d" else ""))
            }
            txt
        }
    }
    private fun tag(xml: String, name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim().orEmpty()
    private fun parseHms(s: String): Long {
        val p = s.split(':'); if (p.size != 3) return 0
        return (((p[0].toLongOrNull() ?: 0) * 3600 + (p[1].toLongOrNull() ?: 0) * 60) * 1000 + ((p[2].toDoubleOrNull() ?: 0.0) * 1000).toLong())
    }

    private fun didl(t: Track, url: String): String {
        val au = StreamServer.artUrl(t)
        val art = if (au != null) "<upnp:albumArtURI xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\" dlna:profileID=\"JPEG_SM\">${xml(au)}</upnp:albumArtURI>" else ""
        val dop = StreamServer.isDopUrl(url)
        val mime = if (dop) "audio/wav" else StreamServer.mime(t.ext)
        val dur = (if (t.durationMs > 0) " duration=\"${hms(t.durationMs)}\"" else "") + (if (dop) " bitsPerSample=\"24\" sampleFrequency=\"${DopPcm.outRate()}\"" else "")
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${xml(t.title)}</dc:title><upnp:artist>${xml(t.artist)}</upnp:artist><upnp:album>${xml(t.album)}</upnp:album>" +
            "$art<upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo=\"http-get:*:$mime:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\"$dur>${xml(url)}</res></item></DIDL-Lite>"
    }

    // ---- Trình tự nạp bài ----
    private fun state(): String = runCatching { tag(call("GetTransportInfo", ""), "CurrentTransportState") }.getOrDefault("")

    /** Đưa thiết bị về trạng thái nghỉ trước khi nạp bài mới: nạp/Play khi thiết bị đang chuyển trạng thái sẽ bị báo lỗi 701 "Transition not available". */
    private suspend fun settle(my: Int) {
        val st = state()
        if (st == "PLAYING" || st == "PAUSED_PLAYBACK" || st == "PAUSED_RECORDING" || st == "TRANSITIONING") runCatching { call("Stop", "") }
        repeat(12) {
            if (my != seq) return
            val s = state()
            if (s == "STOPPED" || s == "NO_MEDIA_PRESENT" || s.isEmpty()) return
            delay(250)
        }
    }

    /** Sau SetAVTransportURI thiết bị thường ở TRANSITIONING một lúc; chờ xong rồi mới Play. */
    private suspend fun waitReady(my: Int) {
        repeat(12) {
            if (my != seq) return
            if (state() != "TRANSITIONING") return
            delay(250)
        }
    }

    /** Thiết bị đang bận (701/705…) thì thử lại vài lần, mỗi lần chờ lâu hơn một chút. */
    private suspend fun <T> retrying(my: Int, tries: Int = 6, block: () -> T): T {
        var last: Exception? = null
        for (n in 0 until tries) {
            if (my != seq) throw CancellationException("đã có lệnh mới")
            try { return block() } catch (e: Exception) {
                last = e
                val m = e.message.orEmpty()
                // 714/715/716/718: thiết bị từ chối định dạng/nguồn này, thử lại cũng vô ích
                if (listOf("(714)", "(715)", "(716)", "(718)").any { it in m }) throw e
                delay(300L * (n + 1))
            }
        }
        throw last ?: IOException("UPnP không phản hồi")
    }

    // ---- RemoteController ----
    override fun load(t: Track, startMs: Long) {
        val my = ++seq
        active = false; sawPlaying = false; userStopped = false; armed = null; armedKey = ""; lastPos = 0
        trackDur = t.durationMs
        health = health.copy(lastSwitchAt = System.currentTimeMillis(), otherController = false)
        scope.launch {
            try {
                val mode = if (StreamServer.isDsdFile(t)) AudioSettings.dsdRemote else AudioSettings.DSD_RAW
                val url = StreamServer.urlFor(t, mode)
                knownUrls.clear(); remember(url)
                AppLog.i("UPnP", "Nạp bài ${t.ext} tới ${r.name} (${if (StreamServer.isDsdFile(t)) (if (mode == AudioSettings.DSD_DOP) "DoP" else "DSD gốc") else "file gốc"})")
                settle(my)
                if (my != seq) return@launch
                try {
                    retrying(my) { call("SetAVTransportURI", "<CurrentURI>${xml(url)}</CurrentURI><CurrentURIMetaData>${xml(didl(t, url))}</CurrentURIMetaData>") }
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    // Một số TV (Bravia đời cũ) từ chối metadata DIDL: thử lại với metadata rỗng
                    retrying(my, 3) { call("SetAVTransportURI", "<CurrentURI>${xml(url)}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>") }
                }
                waitReady(my)
                if (my != seq) return@launch
                retrying(my) { call("Play", "<Speed>1</Speed>") }
                if (my != seq) return@launch
                pendingSeek = startMs; loadedAt = System.currentTimeMillis(); active = true
            } catch (e: CancellationException) {
                // bị thay bằng lệnh nạp mới hơn (ví dụ đổi chế độ DSD): bỏ qua, lệnh mới sẽ lo
            } catch (e: Exception) {
                if (my == seq) {
                    AppLog.e("UPnP", "Nạp bài tới ${r.name} lỗi: ${e.message}")
                    runCatching { call("Stop", "") }   // đưa thiết bị về trạng thái nghỉ để lần Phát kế tiếp không bị kẹt
                    onError("Không phát được tới ${r.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }
    override fun play() { userStopped = false; scope.launch { runCatching { call("Play", "<Speed>1</Speed>") }.onFailure { onError("Lệnh Phát lỗi: ${it.message}") } } }
    override fun pause() { scope.launch { runCatching { call("Pause", "") }.onFailure { onError("Lệnh Tạm dừng lỗi: ${it.message}") } } }
    override fun seek(ms: Long) { scope.launch { runCatching { call("Seek", "<Unit>REL_TIME</Unit><Target>${hms(ms)}</Target>") }.onFailure { onError("Tua lỗi: ${it.message}") } } }
    override fun stop() { userStopped = true; active = false; scope.launch { runCatching { call("Stop", "") } } }

    /**
     * Hỏi trạng thái thiết bị. Thiết kế để nhiều máy cùng mở app không làm loa lag:
     *  - lệch pha ngẫu nhiên khi bắt đầu, để hai máy không hỏi trùng một lúc;
     *  - đang phát bình thường chỉ hỏi vị trí mỗi giây, hỏi trạng thái 3 giây một lần; tạm dừng thì hỏi thưa hơn (2,5 giây);
     *  - lỗi/chậm thì giãn dần 2 → 4 → 8 giây thay vì dồn dập thêm;
     *  - phát hiện bài đang phát KHÔNG phải do app này nạp (máy khác giành quyền): ngừng gửi lệnh và không tự chuyển bài, tránh hai máy giằng co.
     */
    private suspend fun pollLoop() {
        delay((Math.random() * 800).toLong())
        var fails = 0; var foreignN = 0; var tick = 0; var lastSt = ""
        while (scope.isActive) {
            val gap = when { fails > 0 -> minOf(8000L, 1000L shl minOf(fails, 3)); lastSt == "PLAYING" -> 1000L; else -> 2500L }
            delay(gap)
            if (!active) continue
            try {
                val t0 = SystemClock.elapsedRealtime()
                val pi = call("GetPositionInfo", "")
                val ms = (SystemClock.elapsedRealtime() - t0).toInt().coerceAtLeast(1)
                if (fails > 0) AppLog.i("UPnP", "${r.name}: liên lạc lại được")
                fails = 0; record(ms)
                val pos = parseHms(tag(pi, "RelTime"))
                val st = if (lastSt == "PLAYING" && tick++ % 3 != 0 && pos != lastPos) "PLAYING" else tag(call("GetTransportInfo", ""), "CurrentTransportState")
                lastSt = st
                val dur = parseHms(tag(pi, "TrackDuration")).takeIf { it > 0 } ?: trackDur
                val prevPos = lastPos; lastPos = pos
                val cu = unescape(tag(pi, "TrackURI"))

                // Máy khác đã nạp bài khác lên thiết bị này? (cần thấy 3 lần liên tiếp và đã quá thời gian chờ thiết bị đổi URL)
                if ((st == "PLAYING" || st == "PAUSED_PLAYBACK") && foreign(cu) && System.currentTimeMillis() - loadedAt > 8000) foreignN++ else foreignN = 0
                if (foreignN >= 3) {
                    AppLog.w("UPnP", "${r.name} đang được máy khác điều khiển (URL lạ) — app này ngừng gửi lệnh")
                    active = false; armed = null; armedKey = ""; publish(st, other = true)
                    onError("${r.name} đang được một thiết bị khác điều khiển. Để tránh giằng co, app đã ngừng gửi lệnh. Bấm Phát để điều khiển lại từ máy này.")
                    continue
                }

                // Thiết bị đã tự qua bài đã nạp sẵn?
                val a = armed
                if (a != null) {
                    // Dự phòng khi thiết bị viết lại URL (TrackURI khác): bài trước đã gần hết, giờ vị trí về gần 0 và đang PLAYING → đã tự qua bài kế
                    val wrapped = st == "PLAYING" && trackDur > 0 && prevPos >= trackDur - 4000 && pos in 0..4000
                    if ((cu.isNotEmpty() && key(cu) == armedKey) || wrapped) {
                        armed = null; armedKey = ""; armTried = -1; lastPos = 0
                        trackDur = a.durationMs; sawPlaying = true; loadedAt = System.currentTimeMillis(); pendingSeek = 0
                        health = health.copy(lastSwitchAt = System.currentTimeMillis())
                        AppLog.i("UPnP", "${r.name} tự qua bài kế (gapless)")
                        onAdvanced(a); publish(st)
                        continue
                    }
                }
                when (st) {
                    "PLAYING" -> {
                        sawPlaying = true
                        if (nextSupported && armed == null && armTried != seq) { armTried = seq; arm() }
                        if (pendingSeek > 0) { val p = pendingSeek; pendingSeek = 0; runCatching { call("Seek", "<Unit>REL_TIME</Unit><Target>${hms(p)}</Target>") } }
                        onState(true, pos, dur)
                    }
                    "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> onState(false, pos, dur)
                    "TRANSITIONING" -> onState(true, pos, dur)
                    "STOPPED", "NO_MEDIA_PRESENT" ->
                        if (sawPlaying && !userStopped) { active = false; AppLog.i("UPnP", "${r.name} báo dừng: hết bài"); onEnded() }
                        else if (!sawPlaying && System.currentTimeMillis() - loadedAt > 20_000) { active = false; AppLog.e("UPnP", "${r.name} không phát được bài (báo dừng sau 20 s)"); onError("${r.name} không phát được bài này (thiết bị báo dừng). Thử bài khác hoặc định dạng khác.") }
                }
                publish(st)
            } catch (e: Exception) {
                // mất kết nối thoáng qua: thử lại ở vòng sau (giãn dần); chỉ báo lỗi nếu mất lâu
                fails++; record(0); lastSt = ""
                publish("MẤT KẾT NỐI")
                if (System.currentTimeMillis() - loadedAt > 60_000 && !sawPlaying) { active = false; AppLog.e("UPnP", "Mất kết nối tới ${r.name}: ${e.message}"); onError("Mất kết nối tới ${r.name}: ${e.message}") }
            }
        }
    }
}
