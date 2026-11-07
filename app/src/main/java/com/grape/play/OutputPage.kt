@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.grape.play

import android.media.AudioDeviceInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Một lựa chọn đầu ra: thiết bị âm thanh của TV/máy (tv = true) hoặc thiết bị stream không dây. */
private class OutItem(val key: String, val icon: ImageVector, val title: String, val sub: String, val tv: Boolean, val type: Int = 0, val r: Renderer? = null)

private fun iconFor(type: Int): ImageVector = when (type) {
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> Icons.Outlined.SettingsInputHdmi
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> Icons.Outlined.Usb
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> Icons.Outlined.SettingsInputComponent
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> Icons.Outlined.Bluetooth
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> Icons.Outlined.Headphones
    else -> Icons.Outlined.Speaker
}

/** Trang "Đầu ra âm thanh": trái = đầu ra của máy + thiết bị stream; phải = chi tiết thiết bị đang chọn. */
@Composable
fun OutputPage(onNav: (Screen) -> Unit) {
    val compact = LocalCompact.current
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var hires by remember { mutableStateOf(AudioSettings.hires) }
    var dev by remember { mutableStateOf(AudioSettings.deviceType) }
    var startup by remember { mutableStateOf(AudioSettings.startupDev) }
    val devices = remember { AudioSettings.sinks(ctx) }
    val fmt by Playback.level.fmt.collectAsState()
    val remote by Playback.remote.collectAsState()
    var found by remember { mutableStateOf<List<Renderer>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    BackHandler { onNav(Screen.LIB) }

    fun commit() { AudioSettings.hires = hires; AudioSettings.deviceType = dev; Playback.applySettings() }
    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch { found = runCatching { Renderers.discover(ctx) }.getOrDefault(emptyList()); scanning = false; scanned = true }
    }
    LaunchedEffect(Unit) { scan(); if (!compact) runCatching { first.requestFocus() } }

    // Danh sách lựa chọn
    val tvItems = buildList {
        add(OutItem("tv:0", Icons.Outlined.Tv, "Mặc định hệ thống", if (Device.tv) "Theo cài đặt âm thanh của TV" else "Theo cài đặt âm thanh của máy", true, 0))
        devices.forEach { d ->
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() }
            val rate = d.sampleRates.maxOrNull()?.takeIf { it > 0 }?.let { "PCM tối đa ${khz(it)}" }
            add(OutItem("tv:${d.type}", iconFor(d.type), AudioSettings.label(d.type), listOfNotNull(name, rate).joinToString(" · ").ifEmpty { "Đầu ra do Android báo" }, true, d.type))
        }
    }
    val renderers = (found + listOfNotNull(remote).filter { r -> found.none { it.id == r.id } })
    val rnItems = renderers.map { r -> OutItem("rn:${r.id}", if (r.kind == "cast") Icons.Outlined.Cast else Icons.Outlined.SettingsInputAntenna, r.name,
        (if (r.kind == "cast") "Google Cast" else "UPnP / OpenHome") + " · " + r.host, false, 0, r) }

    val activeKey = remote?.let { "rn:${it.id}" } ?: "tv:$dev"
    var selKey by remember { mutableStateOf(activeKey) }
    val sel = (tvItems + rnItems).firstOrNull { it.key == selKey } ?: tvItems.first()

    @Composable
    fun OutCard(o: OutItem, req: FocusRequester?) {
        val chosen = o.key == selKey
        val active = o.key == activeKey
        OptionCard(o.icon, o.title, o.sub, chosen, tag = if (active) "ĐANG DÙNG" else if (chosen) "ĐANG CHỌN" else "", requester = req) { selKey = o.key }
    }

    @Composable
    fun Left(m: Modifier) = Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(if (Device.tv) "TRÊN TV NÀY" else "TRÊN MÁY NÀY")
        tvItems.forEachIndexed { i, o -> OutCard(o, if (i == 0) first else null) }
        Spacer(Modifier.height(6.dp))
        SectionLabel("KHÔNG DÂY — NETWORK PLAYER")
        rnItems.forEach { OutCard(it, null) }
        if (scanning) T("Đang tìm thiết bị…", 15, C.Muted)
        else if (scanned && rnItems.isEmpty()) T("Không thấy thiết bị nào. Kiểm tra thiết bị và ${if (Device.tv) "TV" else "máy"} cùng mạng Wi-Fi/LAN, rồi quét lại.", 14, C.Muted, lines = 3)
    }

    @Composable
    fun Right(m: Modifier) = DetailPanel(m, scroll = !compact) {
        SectionLabel("THIẾT BỊ ĐANG CHỌN")
        T(sel.title, if (compact) 28 else 38, serif = true, lines = 2)
        T(sel.sub, 16, C.Muted, lines = 2)
        val r = sel.r
        if (r != null) {
            val upnp = r.kind != "cast"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(if (upnp) "UPnP" else "Google Cast")
                if (upnp) Tag(if (AudioSettings.dopRemote) "DSD: DoP" else "DSD: file gốc", C.PlumSoft)
            }
            Column(Modifier.fillMaxWidth()) {
                SpecRow("Giao thức", if (upnp) "UPnP AV / OpenHome" else "Google Cast")
                SpecRow("Địa chỉ", r.host)
                SpecRow("DSD", if (!upnp) "Không hỗ trợ" else if (AudioSettings.dopRemote) "DSD64 qua DoP (24-bit/176,4 kHz)" else "Gửi file DSD gốc")
                SpecRow("Âm lượng", "Chỉnh trên thiết bị nhận")
            }
            NoteBox {
                T("Đường tín hiệu", 14, C.Muted, bold = true)
                T("File gốc được gửi nguyên bản (không chuyển mã) qua Wi-Fi/LAN; thiết bị nhận tự giải mã. Thanh âm lượng của ${if (Device.tv) "TV" else "máy"} không ảnh hưởng.", 16, C.Text, lines = 4)
            }
        } else {
            Tag(if (hires) "PCM float 32-bit" else "PCM 16-bit")
            // Chế độ PCM
            SectionLabel("CHẾ ĐỘ PCM")
            OptionCard(Icons.Outlined.GraphicEq, "Tương thích (PCM 16-bit)", "Ổn định nhất — mọi thiết bị đều ra tiếng. Hạ 24-bit xuống 16-bit.", !hires) { hires = false; commit() }
            OptionCard(Icons.Outlined.HighQuality, "Hi-res (PCM float 32-bit)", "Giữ độ phân giải 24-bit. Nếu file hi-res im lặng thì quay lại Tương thích.", hires) { hires = true; commit() }
            // Mặc định khi mở app
            SectionLabel("KHI MỞ APP")
            OptionCard(Icons.Outlined.History, "Giữ lựa chọn lần dùng trước", "", startup == AudioSettings.KEEP) { startup = AudioSettings.KEEP; AudioSettings.startupDev = startup }
            OptionCard(Icons.Outlined.PushPin, "Luôn bắt đầu bằng ${sel.title}", "Nếu thiết bị chưa cắm, app tạm dùng mặc định hệ thống và tự chuyển khi cắm.", startup == sel.type) { startup = sel.type; AudioSettings.startupDev = sel.type }
            NoteBox {
                T("Đường tín hiệu", 14, C.Muted, bold = true)
                T("Đang đưa ra: ${fmt.ifEmpty { "chưa phát" }}", 17, C.Gold, lines = 2)
                T("Nếu ${if (Device.tv) "TV" else "máy"} nối ampli qua cổng quang/analogue, hãy chọn “Mặc định hệ thống”. Trên TV Sony, việc chuyển loa / cổng quang / tai nghe thường do Cài đặt ▸ Âm thanh ▸ Đầu ra âm thanh của TV quyết định.", 14, C.Muted, lines = 5)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            GhostButton(if (scanning) "Đang quét…" else "Quét lại thiết bị") { scan() }
            PrimaryButton(if (sel.key == activeKey) "Đang dùng đầu ra này" else "Dùng đầu ra này") {
                if (sel.tv) { dev = sel.type; commit(); if (remote != null) Playback.setTarget(null) }
                else sel.r?.let { Playback.setTarget(it) }
            }
        }
    }

    if (compact) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Đầu ra âm thanh", "Phát trên ${if (Device.tv) "TV" else "máy"} hoặc stream tới Network Player")
        Left(Modifier.fillMaxWidth())
        Right(Modifier.fillMaxWidth())
    } else Row(Modifier.fillMaxSize().background(C.Bg)) {
        Rail(Screen.OUTPUT, onNav)
        Row(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 36.dp, vertical = 28.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PageHeader("Đầu ra âm thanh", "Phát trên TV hoặc stream tới Network Player")
                Left(Modifier.fillMaxWidth())
            }
            Right(Modifier.weight(1f).fillMaxHeight())
        }
    }
}
