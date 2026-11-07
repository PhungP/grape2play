package com.grape.play

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Thiết bị nhận nhạc không dây. kind: "upnp" (MediaRenderer/OpenHome, ví dụ WiiM Ultra) hoặc "cast" (Google Cast). */
data class Renderer(
    val kind: String, val name: String, val id: String, val host: String, val port: Int = 0,
    val avCtrl: String = "", val rcCtrl: String = "",
) {
    val label: String get() = name + if (kind == "cast") " · Google Cast" else " · UPnP"
}

object Renderers {
    suspend fun discover(ctx: Context): List<Renderer> = coroutineScope {
        val app = ctx.applicationContext
        val u = async(Dispatchers.IO) { runCatching { upnp(app) }.getOrDefault(emptyList()) }
        val c = async(Dispatchers.IO) { runCatching { cast(app) }.getOrDefault(emptyList()) }
        (u.await() + c.await()).sortedBy { it.name.lowercase() }
    }

    // ---------------- UPnP MediaRenderer (SSDP) ----------------
    private fun upnp(ctx: Context): List<Renderer> {
        val locs = LinkedHashSet<String>()
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("grapeplay-r")?.also { it.setReferenceCounted(false); it.acquire() } }.getOrNull()
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 700
                val grp = InetAddress.getByName("239.255.255.250")
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n".toByteArray()
                repeat(2) { sock.send(DatagramPacket(msg, msg.size, grp, 1900)) }
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
        return locs.mapNotNull { runCatching { describe(it) }.getOrNull() }.distinctBy { it.id }
    }

    private fun describe(loc: String): Renderer? {
        val http = Http.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
        val xml = http.newCall(Request.Builder().url(loc).build()).execute().use { it.body!!.string() }
        val p = Xml.newPullParser(); p.setInput(StringReader(xml))
        var name = ""; var udn = ""; var base = ""; var st = ""; var inSvc = false; var av = ""; var rc = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "friendlyName" -> { val v = p.nextText(); if (name.isEmpty()) name = v }
                    "UDN" -> { val v = p.nextText(); if (udn.isEmpty()) udn = v }
                    "URLBase" -> base = p.nextText()
                    "service" -> { inSvc = true; st = "" }
                    "serviceType" -> { val v = p.nextText(); if (inSvc) st = v }
                    "controlURL" -> {
                        val c = p.nextText().trim()
                        if (inSvc && st.contains("AVTransport") && av.isEmpty()) av = c
                        if (inSvc && st.contains("RenderingControl") && rc.isEmpty()) rc = c
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "service") inSvc = false
            ev = p.next()
        }
        if (av.isEmpty()) return null
        val root = URL(if (base.isNotEmpty()) base else loc)
        val u = URL(loc)
        return Renderer("upnp", name.ifEmpty { "Thiết bị UPnP" }, udn.ifEmpty { loc }, u.host, u.port,
            URL(root, av).toString(), if (rc.isEmpty()) "" else URL(root, rc).toString())
    }

    // ---------------- Google Cast (mDNS qua NsdManager) ----------------
    private suspend fun cast(ctx: Context): List<Renderer> = withContext(Dispatchers.IO) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("grapeplay-c")?.also { it.setReferenceCounted(false); it.acquire() } }.getOrNull()
        val found = ConcurrentLinkedQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { found.add(serviceInfo) }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        val out = ArrayList<Renderer>()
        try {
            nsd.discoverServices("_googlecast._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            delay(3500)
        } finally { runCatching { nsd.stopServiceDiscovery(listener) } }
        try {
            for (s in found.toList().distinctBy { it.serviceName }) {
                val r = resolve(nsd, s) ?: continue
                val host = r.host?.hostAddress ?: continue
                fun attr(k: String) = r.attributes?.get(k)?.let { String(it, Charsets.UTF_8) }.orEmpty()
                val id = attr("id").ifEmpty { r.serviceName }
                out += Renderer("cast", attr("fn").ifEmpty { r.serviceName }, id, host, r.port)
            }
        } finally { runCatching { lock?.release() } }
        out.distinctBy { it.id }
    }

    // NsdManager (Android 8) chỉ cho phép resolve từng dịch vụ một.
    private suspend fun resolve(nsd: NsdManager, s: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(2500) {
        suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (cont.isActive) cont.resume(serviceInfo) }
            })
        }
    }
}
