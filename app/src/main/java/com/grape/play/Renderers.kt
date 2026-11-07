package com.grape.play

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
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
    /** Ghi chú lần quét gần nhất (hiện dưới danh sách thiết bị để biết vì sao không thấy loa). */
    @Volatile var report = ""
        private set

    suspend fun discover(ctx: Context): List<Renderer> = coroutineScope {
        val app = ctx.applicationContext
        val c = async(Dispatchers.IO) { runCatching { cast(app) }.getOrDefault(emptyList()) }
        val u = async(Dispatchers.IO) { runCatching { upnp(app) }.getOrDefault(Triple(emptyList<Renderer>(), 0, 0)) }
        val casts = c.await()
        val (ups, replies, addrs) = u.await()
        // Loa vừa có Google Cast vừa có UPnP (JBL L75ms…): nếu SSDP đa hướng không thấy, hỏi thẳng địa chỉ IP mà Cast đã báo.
        val missing = casts.map { it.host }.distinct().filter { h -> ups.none { it.host == h } }
        val extra = missing.map { h -> async(Dispatchers.IO) { runCatching { probeHost(h) }.getOrNull() } }.awaitAll().filterNotNull()
        report = "Quét: SSDP nhận $replies phản hồi từ $addrs địa chỉ · thiết bị UPnP ${ups.size + extra.size}" +
            (if (extra.isNotEmpty()) " (${extra.size} tìm thấy nhờ hỏi thẳng IP)" else "") + " · Google Cast ${casts.size}"
        (ups + extra + casts).distinctBy { it.kind + "|" + it.id }.sortedBy { it.name.lowercase() }
    }

    // ---------------- UPnP MediaRenderer (SSDP) ----------------
    // Nhiều thẻ tìm kiếm: một số loa chỉ trả lời "ssdp:all" / rootdevice chứ không trả lời MediaRenderer:1.
    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "upnp:rootdevice",
        "ssdp:all",
    )

    private fun msearch(st: String, mx: Int = 2): ByteArray =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: $mx\r\nST: $st\r\nUSER-AGENT: Android/8 UPnP/1.1 GRAPEPlay/0.8\r\n\r\n".toByteArray()

    private fun locationOf(reply: String): String? =
        reply.lines().firstOrNull { it.startsWith("location:", true) }?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http") }

    /** Các địa chỉ IPv4 của máy (Wi-Fi, Ethernet…): TV/máy có nhiều card mạng thì gói multicast phải gửi ra từng card. */
    private fun localAddrs(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .flatMap { ni -> ni.inetAddresses.toList().filter { it is Inet4Address && !it.isLoopbackAddress } }
    }.getOrDefault(emptyList())

    /** Gửi M-SEARCH ra một card mạng (null = để hệ thống chọn) rồi nghe phản hồi ~4,5 giây; gửi lặp 3 lần vì UDP có thể rơi gói. */
    private fun searchOn(addr: InetAddress?, locs: MutableSet<String>, replies: AtomicInteger) {
        val sock: DatagramSocket = if (addr == null) DatagramSocket() else MulticastSocket(InetSocketAddress(addr, 0)).also {
            runCatching { it.networkInterface = NetworkInterface.getByInetAddress(addr) }
            it.timeToLive = 4
        }
        sock.use {
            it.soTimeout = 300
            val grp = InetAddress.getByName("239.255.255.250")
            val end = System.currentTimeMillis() + 4500
            var nextSend = 0L; var round = 0
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                if (round < 3 && System.currentTimeMillis() >= nextSend) {
                    for (st in SEARCH_TARGETS) { val m = msearch(st); runCatching { it.send(DatagramPacket(m, m.size, grp, 1900)) } }
                    round++; nextSend = System.currentTimeMillis() + 1200
                }
                try {
                    val p = DatagramPacket(buf, buf.size); it.receive(p)
                    replies.incrementAndGet()
                    locationOf(String(p.data, 0, p.length))?.let { l -> locs += l }
                } catch (e: SocketTimeoutException) { }
            }
        }
    }

    /** Trả về (danh sách thiết bị, số phản hồi SSDP, số card mạng đã dùng). */
    private suspend fun upnp(ctx: Context): Triple<List<Renderer>, Int, Int> = coroutineScope {
        val locs = ConcurrentHashMap.newKeySet<String>()
        val replies = AtomicInteger(0)
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("grapeplay-r")?.also { it.setReferenceCounted(false); it.acquire() } }.getOrNull()
        val addrs = localAddrs()
        try {
            val targets: List<InetAddress?> = addrs.ifEmpty { listOf(null) }
            targets.map { a -> async(Dispatchers.IO) { runCatching { searchOn(a, locs, replies) } } }.awaitAll()
        } finally { runCatching { lock?.release() } }
        val found = locs.toList().map { l -> async(Dispatchers.IO) { runCatching { describe(l) }.getOrNull() } }.awaitAll().filterNotNull().distinctBy { it.id }
        Triple(found, replies.get(), addrs.size)
    }

    /** Hỏi trực tiếp (unicast) một địa chỉ IP: nhiều loa không trả lời gói multicast nhưng trả lời gói gửi thẳng tới cổng 1900. */
    private fun probeHost(host: String): Renderer? {
        val locs = LinkedHashSet<String>()
        DatagramSocket().use { sock ->
            sock.soTimeout = 400
            val ip = InetAddress.getByName(host)
            val end = System.currentTimeMillis() + 3000
            var round = 0; var nextSend = 0L
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                if (round < 3 && System.currentTimeMillis() >= nextSend) {
                    for (st in SEARCH_TARGETS) { val m = msearch(st, 1); runCatching { sock.send(DatagramPacket(m, m.size, ip, 1900)) } }
                    round++; nextSend = System.currentTimeMillis() + 900
                }
                try {
                    val p = DatagramPacket(buf, buf.size); sock.receive(p)
                    locationOf(String(p.data, 0, p.length))?.let { locs += it }
                } catch (e: SocketTimeoutException) { }
            }
        }
        return locs.firstNotNullOfOrNull { runCatching { describe(it) }.getOrNull() }
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
