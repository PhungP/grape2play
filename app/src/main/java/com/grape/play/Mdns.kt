package com.grape.play

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Một thiết bị tìm thấy bằng mDNS. */
data class MdnsHit(val instance: String, val host: String, val port: Int, val txt: Map<String, String>)

/**
 * Máy khách mDNS tự viết (không dùng NsdManager — NsdManager trên nhiều Android TV đời cũ không báo thiết bị hoặc resolve thất bại).
 * Gửi truy vấn PTR tới 224.0.0.251:5353 trên MỌI card mạng (Wi-Fi và Ethernet), đọc cả phản hồi unicast lẫn multicast.
 */
object Mdns {
    private const val GROUP = "224.0.0.251"

    private fun encName(n: String, o: ByteArrayOutputStream) {
        for (l in n.trim('.').split('.')) { val b = l.toByteArray(Charsets.UTF_8); o.write(b.size); o.write(b) }
        o.write(0)
    }
    private fun query(name: String, type: Int): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        encName(name, o)
        o.write(type shr 8); o.write(type and 0xFF); o.write(0); o.write(1)
        return o.toByteArray()
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** Đọc tên (có nén con trỏ). Trả về (tên, vị trí sau tên). */
    private fun readName(b: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder(); var i = start; var end = -1; var guard = 0
        while (i < b.size && guard++ < 64) {
            val l = b[i].toInt() and 0xFF
            if (l == 0) { i++; break }
            if (l and 0xC0 == 0xC0) { if (end < 0) end = i + 2; i = ((l and 0x3F) shl 8) or (b[i + 1].toInt() and 0xFF); continue }
            if (i + 1 + l > b.size) break
            sb.append(String(b, i + 1, l, Charsets.UTF_8)).append('.'); i += 1 + l
        }
        return sb.toString() to (if (end >= 0) end else i)
    }

    private class State {
        val ptr = LinkedHashSet<String>()
        val srv = HashMap<String, Pair<Int, String>>()   // instance -> (port, target)
        val txt = HashMap<String, Map<String, String>>()
        val addr = HashMap<String, String>()             // hostname -> IPv4
        val src = HashMap<String, String>()              // instance -> IP nguồn gói tin
    }

    private fun parse(b: ByteArray, len: Int, from: String, service: String, st: State) {
        if (len < 12) return
        val qd = u16(b, 4); val rr = u16(b, 6) + u16(b, 8) + u16(b, 10)
        var i = 12
        repeat(qd) { i = readName(b, i).second + 4 }
        repeat(rr) {
            if (i >= len) return
            val (name, p) = readName(b, i); i = p
            if (i + 10 > len) return
            val type = u16(b, i); val rdl = u16(b, i + 8); val d = i + 10; i = d + rdl
            if (i > len) return
            val key = name.lowercase()
            when (type) {
                12 -> if (key == service) { val inst = readName(b, d).first; st.ptr += inst; st.src[inst.lowercase()] = from }
                33 -> if (rdl >= 7) { st.srv[key] = u16(b, d + 4) to readName(b, d + 6).first.lowercase(); st.src[key] = from }
                16 -> { val m = HashMap<String, String>(); var j = d
                    while (j < d + rdl) { val l = b[j].toInt() and 0xFF; if (l > 0 && j + 1 + l <= d + rdl) { val s = String(b, j + 1, l, Charsets.UTF_8); val e = s.indexOf('='); if (e > 0) m[s.substring(0, e).lowercase()] = s.substring(e + 1) }; j += 1 + l }
                    st.txt[key] = m }
                1 -> if (rdl == 4) st.addr[key] = "${b[d].toInt() and 0xFF}.${b[d + 1].toInt() and 0xFF}.${b[d + 2].toInt() and 0xFF}.${b[d + 3].toInt() and 0xFF}"
            }
        }
    }

    /** Tìm các thiết bị quảng bá [service] (ví dụ "_googlecast._tcp.local."). Chặn luồng gọi tối đa ~[ms] ms. */
    fun browse(service: String, ms: Long = 4500): List<MdnsHit> {
        val svc = service.lowercase().let { if (it.endsWith(".")) it else "$it." }
        val grp = InetAddress.getByName(GROUP)
        val ifs = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() && it.inetAddresses.toList().any { a -> a is Inet4Address } }.getOrDefault(false) }
        val socks = ArrayList<MulticastSocket>()
        for (nif in ifs) {
            // socket cổng ngẫu nhiên: thiết bị trả lời unicast thẳng về socket này
            runCatching { MulticastSocket(0).also { it.networkInterface = nif; it.soTimeout = 120; it.timeToLive = 255; socks += it } }
        }
        // socket 5353 (dùng chung cổng) để nghe thêm phản hồi multicast
        for (nif in ifs) runCatching {
            MulticastSocket(null).also { s -> s.reuseAddress = true; s.bind(java.net.InetSocketAddress(5353)); s.networkInterface = nif; s.joinGroup(java.net.InetSocketAddress(grp, 5353), nif); s.soTimeout = 120; socks += s }
        }
        val st = State()
        val q = query(svc, 12)
        val end = System.currentTimeMillis() + ms
        var nextSend = 0L; var round = 0
        val buf = ByteArray(9000)
        try {
            while (System.currentTimeMillis() < end) {
                val now = System.currentTimeMillis()
                if (now >= nextSend && round < 4) {
                    for (s in socks) runCatching { s.send(DatagramPacket(q, q.size, grp, 5353)) }
                    round++; nextSend = now + 900
                }
                for (s in socks) {
                    try {
                        val p = DatagramPacket(buf, buf.size); s.receive(p)
                        runCatching { parse(p.data, p.length, p.address.hostAddress ?: "", svc, st) }
                    } catch (_: SocketTimeoutException) { } catch (_: Exception) { }
                }
                // thiếu SRV/TXT/A của thiết bị đã thấy → hỏi thẳng tên đó
                if (round in 2..3 && now >= nextSend - 700) {
                    for (inst in st.ptr) {
                        val k = inst.lowercase()
                        if (!st.srv.containsKey(k) || !st.txt.containsKey(k)) { val qq = query(inst, 255); for (s in socks) runCatching { s.send(DatagramPacket(qq, qq.size, grp, 5353)) } }
                    }
                }
            }
        } finally { socks.forEach { runCatching { it.close() } } }
        return st.ptr.mapNotNull { inst ->
            val k = inst.lowercase()
            val (port, target) = st.srv[k] ?: return@mapNotNull null
            val host = st.addr[target] ?: st.src[k] ?: return@mapNotNull null
            MdnsHit(inst.dropLast(svc.length.coerceAtMost(inst.length)).trim('.'), host, port, st.txt[k] ?: emptyMap())
        }.distinctBy { it.host to it.port }
    }
}
