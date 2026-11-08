package com.grape.play

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Điều khiển app trên TV từ điện thoại (cùng mạng Wi-Fi/LAN).
 *  - TV: [RemoteHost] mở một máy chủ HTTP nhỏ + lắng nghe gói dò tìm UDP (cổng 8929). Mọi lệnh cần mã ghép nối (PIN) hiện ở Cài đặt của TV.
 *  - Điện thoại: [TvLink] dò TV bằng gói broadcast, lưu TV + PIN, rồi gửi lệnh / đọc trạng thái.
 */
object RemoteHost {
    const val DISCOVERY_PORT = 8929
    private lateinit var sp: android.content.SharedPreferences
    @Volatile private var started = false
    @Volatile var port = 0
        private set
    val pin = mutableStateOf("")
    private val main = Handler(Looper.getMainLooper())

    fun start(ctx: Context) {
        if (started) return
        started = true
        sp = ctx.applicationContext.getSharedPreferences("tvremote", Context.MODE_PRIVATE)
        pin.value = sp.getString("pin", null) ?: "%06d".format((Math.random() * 1_000_000).toInt()).also { sp.edit().putString("pin", it).apply() }
        val ss = (8930..8950).firstNotNullOfOrNull { p -> runCatching { ServerSocket(p).also { it.reuseAddress = true } }.getOrNull() }
        if (ss == null) { started = false; return }
        port = ss.localPort
        Thread({ while (!ss.isClosed) { val c = runCatching { ss.accept() }.getOrNull() ?: break; Thread({ serve(c) }, "grape-rc").also { it.isDaemon = true }.start() } }, "grape-rc-accept").also { it.isDaemon = true }.start()
        Thread({ udp() }, "grape-rc-udp").also { it.isDaemon = true }.start()
    }

    fun newPin() { pin.value = "%06d".format((Math.random() * 1_000_000).toInt()); sp.edit().putString("pin", pin.value).apply() }

    private fun udp() {
        val name = Build.MODEL ?: "TV"
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true; s.bind(java.net.InetSocketAddress(DISCOVERY_PORT)); s.broadcast = true
                val buf = ByteArray(256)
                while (true) {
                    val p = DatagramPacket(buf, buf.size); s.receive(p)
                    if (String(p.data, 0, p.length).startsWith("GRAPE2PLAY?")) {
                        val r = JSONObject().put("app", "grape2play").put("name", name).put("port", port).toString().toByteArray()
                        runCatching { s.send(DatagramPacket(r, r.size, p.address, p.port)) }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun trackJson(t: Track) = JSONObject().put("p", t.path).put("t", t.title).put("a", t.artist).put("al", t.album).put("fmt", formatBadge(t))

    private fun <T> onMain(block: () -> T): T? {
        var out: T? = null
        val l = java.util.concurrent.CountDownLatch(1)
        main.post { try { out = block() } catch (_: Exception) { } finally { l.countDown() } }
        l.await(3, TimeUnit.SECONDS)
        return out
    }

    private fun serve(c: java.net.Socket) {
        try {
            c.use { sock ->
                sock.soTimeout = 8000
                val inp = sock.getInputStream().bufferedReader()
                val first = inp.readLine() ?: return
                var hdrPin = ""
                while (true) { val h = inp.readLine() ?: break; if (h.isEmpty()) break; if (h.startsWith("x-pin:", true)) hdrPin = h.substringAfter(':').trim() }
                val target = first.split(' ').getOrElse(1) { "/" }
                val path = target.substringBefore('?')
                val q = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val out = sock.getOutputStream()
                fun reply(code: Int, type: String, body: ByteArray) {
                    out.write("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body); out.flush()
                }
                if (path == "/ping") { reply(200, "application/json", JSONObject().put("app", "grape2play").put("name", Build.MODEL ?: "TV").toString().toByteArray()); return }
                if (hdrPin != pin.value && q["pin"] != pin.value) { reply(401, "application/json", "{\"error\":\"pin\"}".toByteArray()); return }
                when (path) {
                    "/state" -> {
                        val queue = Playback.queue.value; val i = Playback.index.value
                        val o = JSONObject().put("playing", Playback.playing.value).put("pos", Playback.position.value).put("dur", Playback.duration.value)
                            .put("index", i).put("count", queue.size).put("shuffle", Playback.shuffle.value).put("repeat", Playback.repeat.value)
                            .put("out", Playback.outName.value).put("err", Playback.error.value ?: "")
                        queue.getOrNull(i)?.let { o.put("track", trackJson(it)) }
                        reply(200, "application/json", o.toString().toByteArray())
                    }
                    "/queue" -> {
                        val arr = JSONArray(); Playback.queue.value.take(300).forEach { arr.put(trackJson(it)) }
                        reply(200, "application/json", arr.toString().toByteArray())
                    }
                    "/search" -> {
                        val k = (q["q"] ?: "").trim().lowercase()
                        val arr = JSONArray()
                        if (k.isNotEmpty()) Library.tracks.value.asSequence().filter { it.title.lowercase().contains(k) || it.artist.lowercase().contains(k) || it.album.lowercase().contains(k) }.take(60).forEach { arr.put(trackJson(it)) }
                        reply(200, "application/json", arr.toString().toByteArray())
                    }
                    "/cover" -> {
                        val t = Playback.queue.value.getOrNull(Playback.index.value)
                        val bmp = t?.let { runCatching { kotlinx.coroutines.runBlocking { Covers.get(it) } }.getOrNull() }
                        if (bmp == null) reply(404, "text/plain", ByteArray(0))
                        else {
                            val m = maxOf(bmp.width, bmp.height)
                            val b = if (m > 480) Bitmap.createScaledBitmap(bmp, bmp.width * 480 / m, bmp.height * 480 / m, true) else bmp
                            val bo = java.io.ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 85, bo)
                            reply(200, "image/jpeg", bo.toByteArray())
                        }
                    }
                    "/cmd" -> {
                        onMain {
                            when (q["c"]) {
                                "toggle" -> Playback.toggle()
                                "next" -> Playback.next()
                                "prev" -> Playback.prev()
                                "seek" -> Playback.seekTo(q["ms"]?.toLongOrNull() ?: 0L)
                                "shuffle" -> Playback.toggleShuffle()
                                "repeat" -> Playback.cycleRepeat()
                                "jump" -> Playback.jump(q["i"]?.toIntOrNull() ?: 0)
                                "play" -> {
                                    val all = Library.tracks.value
                                    val t = all.firstOrNull { it.path == q["p"] }
                                    if (t != null) {
                                        val same = all.filter { it.folder == t.folder }.sortedBy { it.path }.ifEmpty { listOf(t) }
                                        Playback.play(same, same.indexOfFirst { it.path == t.path }.coerceAtLeast(0))
                                    }
                                }
                            }
                        }
                        reply(200, "application/json", "{\"ok\":true}".toByteArray())
                    }
                    else -> reply(404, "text/plain", ByteArray(0))
                }
            }
        } catch (_: Exception) { }
    }
}

data class TvInfo(val name: String, val host: String, val port: Int)

object TvLink {
    private lateinit var sp: android.content.SharedPreferences
    val target = mutableStateOf<TvInfo?>(null)
    var pin = ""
        private set
    private val http = Http.client.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("tvlink", Context.MODE_PRIVATE)
        pin = sp.getString("pin", "") ?: ""
        val h = sp.getString("host", null)
        if (h != null) target.value = TvInfo(sp.getString("name", h) ?: h, h, sp.getInt("port", 8930))
    }
    fun save(tv: TvInfo?, p: String) {
        target.value = tv; pin = p
        sp.edit().apply { if (tv == null) clear() else putString("host", tv.host).putString("name", tv.name).putInt("port", tv.port).putString("pin", p) }.apply()
    }

    /** Dò các TV đang chạy GRAPEPlay: gửi broadcast tới mọi card mạng rồi nghe phản hồi ~2,5 giây. */
    suspend fun discover(ctx: Context): List<TvInfo> = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val lock = runCatching { wifi?.createMulticastLock("grape-tv")?.also { it.setReferenceCounted(false); it.acquire() } }.getOrNull()
        val found = LinkedHashMap<String, TvInfo>()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true; s.soTimeout = 300
                val msg = "GRAPE2PLAY?".toByteArray()
                val targets = ArrayList<InetAddress>()
                runCatching { targets += InetAddress.getByName("255.255.255.255") }
                runCatching { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.forEach { ni -> ni.interfaceAddresses.forEach { ia -> ia.broadcast?.let { targets += it } } } }
                val end = System.currentTimeMillis() + 2500; var round = 0; var next = 0L
                val buf = ByteArray(512)
                while (System.currentTimeMillis() < end) {
                    if (round < 3 && System.currentTimeMillis() >= next) { targets.distinct().forEach { a -> runCatching { s.send(DatagramPacket(msg, msg.size, a, RemoteHost.DISCOVERY_PORT)) } }; round++; next = System.currentTimeMillis() + 800 }
                    try {
                        val p = DatagramPacket(buf, buf.size); s.receive(p)
                        val j = JSONObject(String(p.data, 0, p.length))
                        if (j.optString("app") == "grape2play") found[p.address.hostAddress ?: ""] = TvInfo(j.optString("name", "TV"), p.address.hostAddress ?: "", j.optInt("port", 8930))
                    } catch (_: SocketTimeoutException) { } catch (_: Exception) { }
                }
            }
        } finally { runCatching { lock?.release() } }
        found.values.toList()
    }

    private fun req(path: String, tv: TvInfo, p: String): okhttp3.Response =
        http.newCall(Request.Builder().url("http://${tv.host}:${tv.port}$path").header("X-Pin", p).build()).execute()

    /** Trả về (mã HTTP, nội dung). Mã -1 = không kết nối được. */
    suspend fun get(path: String, tv: TvInfo = target.value ?: TvInfo("", "", 0), p: String = pin): Pair<Int, String> = withContext(Dispatchers.IO) {
        try { req(path, tv, p).use { it.code to it.body?.string().orEmpty() } } catch (e: Exception) { -1 to (e.message ?: "") }
    }
    suspend fun bytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val tv = target.value ?: return@withContext null
        try { req(path, tv, pin).use { if (it.isSuccessful) it.body?.bytes() else null } } catch (e: Exception) { null }
    }
    suspend fun cmd(c: String, extra: String = "") = get("/cmd?c=$c$extra")
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** Cửa sổ điều khiển TV trên điện thoại: chọn TV + nhập PIN (một lần), rồi điều khiển phát nhạc / tìm và phát bài trên TV. */
@Composable
fun TvRemoteDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv by TvLink.target
    var tvs by remember { mutableStateOf<List<TvInfo>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var pinIn by remember { mutableStateOf("") }
    var pickManual by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var st by remember { mutableStateOf<JSONObject?>(null) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var connected by remember { mutableStateOf(false) }

    fun scan() { if (scanning) return; scanning = true; scope.launch { tvs = TvLink.discover(ctx); scanning = false; if (tvs.isEmpty()) status = "Không thấy TV nào. Kiểm tra TV đang mở GRAPEPlay và cùng Wi-Fi, hoặc nhập địa chỉ IP của TV." } }
    LaunchedEffect(Unit) { if (tv == null) scan() }

    // Đọc trạng thái TV mỗi giây
    LaunchedEffect(tv) {
        var lastKey = ""
        while (tv != null) {
            val (code, body) = TvLink.get("/state")
            if (code == 200) {
                connected = true; status = ""
                val j = runCatching { JSONObject(body) }.getOrNull(); st = j
                val key = j?.optJSONObject("track")?.optString("p").orEmpty()
                if (key != lastKey) { lastKey = key; cover = TvLink.bytes("/cover")?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            } else { connected = false; status = if (code == 401) "Mã PIN không đúng. Xem lại mã trong Cài đặt trên TV." else "Không kết nối được tới TV." }
            delay(1000)
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f).clip(RoundedCornerShape(16.dp)).background(C.Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Điều khiển TV", 22, serif = true)
                    if (tv != null) T(tv!!.name + " · " + tv!!.host, 13, if (connected) C.Gold else C.Muted)
                }
                if (tv != null) Focusable({ TvLink.save(null, ""); st = null; connected = false; results = emptyList(); scan() }) { T("Đổi TV", 14, C.Muted, Modifier.padding(12.dp, 8.dp)) }
                Focusable(onClose) { T("Đóng", 14, C.Gold, Modifier.padding(12.dp, 8.dp), bold = true) }
            }
            if (tv == null) {
                T("Mở GRAPEPlay trên TV → Cài đặt → “Điều khiển từ điện thoại” để lấy mã PIN 6 số, nhập vào ô bên dưới rồi chọn TV.", 14, C.Muted, lines = 4)
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Bg).padding(12.dp)) {
                    BasicTextField(pinIn, { pinIn = it.filter { c -> c.isDigit() }.take(6) }, textStyle = TextStyle(color = C.Text, fontSize = 22.sp, letterSpacing = 6.sp),
                        cursorBrush = SolidColor(C.Gold), singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        decorationBox = { inner -> if (pinIn.isEmpty()) T("Mã PIN", 18, C.Muted); inner() })
                }
                if (scanning) T("Đang tìm TV…", 14, C.Muted)
                tvs.forEach { d -> Focusable({
                    if (pinIn.length < 6) status = "Hãy nhập mã PIN 6 số trước."
                    else scope.launch { val (c, _) = TvLink.get("/state", d, pinIn); if (c == 200) { TvLink.save(d, pinIn); status = "" } else status = if (c == 401) "Mã PIN không đúng." else "Không kết nối được tới ${d.name}." }
                }, Modifier.fillMaxWidth()) { T("${d.name} · ${d.host}", 18, C.Gold, Modifier.padding(14.dp, 10.dp), bold = true) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.Bg).padding(12.dp)) {
                        BasicTextField(pickManual, { pickManual = it.filter { c -> c.isDigit() || c == '.' } }, textStyle = TextStyle(color = C.Text, fontSize = 16.sp), cursorBrush = SolidColor(C.Gold), singleLine = true,
                            decorationBox = { inner -> if (pickManual.isEmpty()) T("Hoặc nhập IP của TV, ví dụ 192.168.1.31", 14, C.Muted); inner() })
                    }
                    Focusable({
                        if (pickManual.isNotBlank() && pinIn.length == 6) scope.launch {
                            val d = TvInfo("TV", pickManual.trim(), 8930)
                            val port = (8930..8950).firstOrNull { p -> TvLink.get("/ping", d.copy(port = p), pinIn).first == 200 }
                            if (port == null) status = "Không kết nối được tới ${d.host}."
                            else { val dd = d.copy(port = port); val (c, _) = TvLink.get("/state", dd, pinIn); if (c == 200) { TvLink.save(dd, pinIn); status = "" } else status = "Mã PIN không đúng." }
                        } else status = "Nhập IP và mã PIN 6 số."
                    }) { T("Kết nối", 15, C.Gold, Modifier.padding(14.dp, 10.dp), bold = true) }
                }
                Focusable({ scan() }) { T("Quét lại", 14, C.Muted, Modifier.padding(12.dp, 8.dp)) }
                if (status.isNotEmpty()) T(status, 14, C.Gold, lines = 3)
            } else {
                val j = st; val t = j?.optJSONObject("track")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).background(C.Bg)) { cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
                    Column(Modifier.weight(1f)) {
                        T(t?.optString("t") ?: "Chưa phát bài nào", 20, bold = true, lines = 2)
                        T(t?.optString("a").orEmpty(), 15, C.Muted)
                        T(t?.optString("al").orEmpty() + (t?.optString("fmt")?.let { " · $it" } ?: ""), 13, C.Muted)
                        T("Đầu ra: " + (j?.optString("out") ?: ""), 12, C.Muted, lines = 1)
                    }
                }
                val dur = (j?.optLong("dur") ?: 0L).coerceAtLeast(1L); val pos = j?.optLong("pos") ?: 0L
                Slider(seeking ?: (pos.toFloat() / dur).coerceIn(0f, 1f), { seeking = it }, onValueChangeFinished = { val f = seeking; seeking = null; if (f != null) scope.launch { TvLink.cmd("seek", "&ms=${(f * dur).toLong()}") } })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T(fmt(pos), 12, C.Muted); T(fmt(dur), 12, C.Muted) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val rep = j?.optInt("repeat") ?: 0
                    Focusable({ scope.launch { TvLink.cmd("shuffle") } }, Modifier.weight(1f)) { T(if (j?.optBoolean("shuffle") == true) "Xáo: bật" else "Xáo: tắt", 13, if (j?.optBoolean("shuffle") == true) C.Gold else C.Muted, Modifier.padding(8.dp, 12.dp)) }
                    Focusable({ scope.launch { TvLink.cmd("prev") } }, Modifier.weight(1f)) { T("⏮", 20, C.Text, Modifier.padding(8.dp, 8.dp)) }
                    Focusable({ scope.launch { TvLink.cmd("toggle") } }, Modifier.weight(1.4f)) { T(if (j?.optBoolean("playing") == true) "⏸ Tạm dừng" else "▶ Phát", 16, C.Gold, Modifier.padding(8.dp, 10.dp), bold = true) }
                    Focusable({ scope.launch { TvLink.cmd("next") } }, Modifier.weight(1f)) { T("⏭", 20, C.Text, Modifier.padding(8.dp, 8.dp)) }
                    Focusable({ scope.launch { TvLink.cmd("repeat") } }, Modifier.weight(1f)) { T(when (rep) { 1 -> "Lặp 1"; 2 -> "Lặp tất"; else -> "Không lặp" }, 13, if (rep != 0) C.Gold else C.Muted, Modifier.padding(8.dp, 12.dp)) }
                }
                if (status.isNotEmpty()) T(status, 13, C.Gold, lines = 2)
                val err = j?.optString("err").orEmpty(); if (err.isNotEmpty()) T(err, 13, C.Gold, lines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.Bg).padding(12.dp)) {
                        BasicTextField(query, { query = it }, textStyle = TextStyle(color = C.Text, fontSize = 16.sp), cursorBrush = SolidColor(C.Gold), singleLine = true,
                            decorationBox = { inner -> if (query.isEmpty()) T("Tìm bài / nghệ sĩ / album trên TV", 14, C.Muted); inner() })
                    }
                    Focusable({ scope.launch { val (c, b) = TvLink.get("/search?q=${TvLink.enc(query)}"); results = if (c == 200) runCatching { JSONArray(b).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.getOrDefault(emptyList()) else emptyList() } }) { T("Tìm", 15, C.Gold, Modifier.padding(14.dp, 10.dp), bold = true) }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(results) { r ->
                        Focusable({ scope.launch { TvLink.cmd("play", "&p=${TvLink.enc(r.optString("p"))}") } }, Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp, 7.dp)) { T(r.optString("t"), 16, lines = 1); T(r.optString("a") + " · " + r.optString("al"), 12, C.Muted) }
                        }
                    }
                }
            }
        }
    }
}

/** Thẻ ở Cài đặt của TV: hiện mã PIN để ghép nối với điện thoại. */
@Composable
fun RemoteHostCard() {
    val pin by RemoteHost.pin
    Card {
        T("ĐIỀU KHIỂN TỪ ĐIỆN THOẠI", 13, C.Muted, spaced = true)
        T("Mã PIN: $pin", 22, C.Gold, bold = true)
        T("Trên điện thoại (cùng Wi-Fi) mở GRAPEPlay → biểu tượng TV → nhập mã này để điều khiển app trên TV.", 14, C.Muted, lines = 3)
        Focusable({ RemoteHost.newPin() }, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) { T("Đổi mã PIN", 16, C.Gold, Modifier.padding(14.dp, 9.dp), bold = true) }
    }
}
