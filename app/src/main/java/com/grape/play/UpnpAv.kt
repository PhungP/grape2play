package com.grape.play

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
    private val http = Http.client.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    @Volatile private var active = false
    @Volatile private var sawPlaying = false
    @Volatile private var userStopped = false
    @Volatile private var loadedAt = 0L
    @Volatile private var pendingSeek = 0L
    @Volatile private var trackDur = 0L
    @Volatile private var seq = 0

    init { scope.launch { pollLoop() } }

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
        val art = if (t.art.startsWith("http")) "<upnp:albumArtURI>${xml(t.art)}</upnp:albumArtURI>" else ""
        val dop = StreamServer.isDopUrl(url)
        val mime = if (dop) "audio/wav" else StreamServer.mime(t.ext)
        val dur = (if (t.durationMs > 0) " duration=\"${hms(t.durationMs)}\"" else "") + (if (dop) " bitsPerSample=\"24\" sampleFrequency=\"${DopPcm.outRate()}\"" else "")
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${xml(t.title)}</dc:title><upnp:artist>${xml(t.artist)}</upnp:artist><upnp:album>${xml(t.album)}</upnp:album>" +
            "$art<upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo=\"http-get:*:$mime:*\"$dur>${xml(url)}</res></item></DIDL-Lite>"
    }

    // ---- RemoteController ----
    override fun load(t: Track, startMs: Long) {
        val my = ++seq
        active = false; sawPlaying = false; userStopped = false
        trackDur = t.durationMs
        scope.launch {
            try {
                val url = StreamServer.urlFor(t, AudioSettings.dopRemote)
                call("SetAVTransportURI", "<CurrentURI>${xml(url)}</CurrentURI><CurrentURIMetaData>${xml(didl(t, url))}</CurrentURIMetaData>")
                call("Play", "<Speed>1</Speed>")
                if (my != seq) return@launch
                pendingSeek = startMs; loadedAt = System.currentTimeMillis(); active = true
            } catch (e: Exception) { onError("Không phát được tới ${r.name}: ${e.message ?: e.javaClass.simpleName}") }
        }
    }
    override fun play() { userStopped = false; scope.launch { runCatching { call("Play", "<Speed>1</Speed>") }.onFailure { onError("Lệnh Phát lỗi: ${it.message}") } } }
    override fun pause() { scope.launch { runCatching { call("Pause", "") }.onFailure { onError("Lệnh Tạm dừng lỗi: ${it.message}") } } }
    override fun seek(ms: Long) { scope.launch { runCatching { call("Seek", "<Unit>REL_TIME</Unit><Target>${hms(ms)}</Target>") }.onFailure { onError("Tua lỗi: ${it.message}") } } }
    override fun stop() { userStopped = true; active = false; scope.launch { runCatching { call("Stop", "") } } }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            delay(1000)
            if (!active) continue
            try {
                val st = tag(call("GetTransportInfo", ""), "CurrentTransportState")
                val pi = call("GetPositionInfo", "")
                val pos = parseHms(tag(pi, "RelTime"))
                val dur = parseHms(tag(pi, "TrackDuration")).takeIf { it > 0 } ?: trackDur
                when (st) {
                    "PLAYING" -> {
                        sawPlaying = true
                        if (pendingSeek > 0) { val p = pendingSeek; pendingSeek = 0; runCatching { call("Seek", "<Unit>REL_TIME</Unit><Target>${hms(p)}</Target>") } }
                        onState(true, pos, dur)
                    }
                    "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> onState(false, pos, dur)
                    "TRANSITIONING" -> onState(true, pos, dur)
                    "STOPPED", "NO_MEDIA_PRESENT" ->
                        if (sawPlaying && !userStopped) { active = false; onEnded() }
                        else if (!sawPlaying && System.currentTimeMillis() - loadedAt > 20_000) { active = false; onError("${r.name} không phát được bài này (thiết bị báo dừng). Thử bài khác hoặc định dạng khác.") }
                }
            } catch (e: Exception) {
                // mất kết nối thoáng qua: thử lại ở vòng sau; chỉ báo lỗi nếu mất lâu
                if (System.currentTimeMillis() - loadedAt > 60_000 && !sawPlaying) { active = false; onError("Mất kết nối tới ${r.name}: ${e.message}") }
            }
        }
    }
}
