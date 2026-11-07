@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.grape.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Found(val type: String, val label: String, val host: String = "", val name: String = "", val url: String = "")
private fun typeLabel(t: String) = when (t) { "smb" -> "SMB"; "dav" -> "WebDAV"; "gdrive" -> "Google Drive"; "onedrive" -> "OneDrive"; else -> "UPnP/DLNA" }

private class SrcType(val id: String, val icon: ImageVector, val title: String, val subtitle: String)

private fun srcTypes() = listOf(
    SrcType("local", Icons.Outlined.Usb, if (Device.tv) "Bộ nhớ TV & USB" else "Bộ nhớ máy & USB", "Tự động thêm"),
    SrcType("smb", Icons.Outlined.Dns, "NAS · SMB", "Synology, QNAP, thư mục chia sẻ Windows"),
    SrcType("dav", Icons.Outlined.Language, "WebDAV", "Nextcloud, Synology WebDAV, máy chủ web"),
    SrcType("dlna", Icons.Outlined.SettingsInputAntenna, "Máy chủ UPnP / DLNA", "Minimserver, Plex, Jellyfin…"),
    SrcType("gdrive", Icons.Outlined.CloudQueue, "Google Drive", "Cá nhân hoặc Workspace"),
    SrcType("onedrive", Icons.Outlined.Cloud, "OneDrive", "Cá nhân hoặc Microsoft 365"),
)

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, password: Boolean = false) {
    var f by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T(label, 14, C.Muted)
        Box(Modifier.fillMaxWidth().border(if (f) 2.dp else 1.dp, if (f) C.Gold else C.Dim, RoundedCornerShape(10.dp)).background(C.Bg, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(color = C.Text, fontSize = 19.sp), cursorBrush = SolidColor(C.Gold),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().onFocusChanged { f = it.isFocused },
            )
        }
    }
}

/** Trang "Thêm nguồn nhạc": trái = các loại nguồn (thẻ), phải = bảng chi tiết của loại đang chọn. */
@Composable
fun SourcesPage(onNav: (Screen) -> Unit) {
    val compact = LocalCompact.current
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val sources by SourceStore.list.collectAsState()
    val all by Library.tracks.collectAsState()
    val status by Library.status.collectAsState()
    var type by remember { mutableStateOf("smb") }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (!compact) runCatching { first.requestFocus() } }
    BackHandler { onNav(Screen.LIB) }

    fun discover() {
        if (busy) return
        scope.launch {
            busy = true; msg = "Đang tìm thiết bị trên mạng…"
            val smb = async { runCatching { Remote.discoverSmb() }.getOrDefault(emptyList()) }
            val dl = async { runCatching { Upnp.discover(ctx) }.getOrDefault(emptyList()) }
            found = smb.await().map { Found("smb", "SMB · $it", host = it, name = it) } + dl.await().map { Found("dlna", "DLNA · ${it.name}", name = it.name, url = it.ctrl) }
            msg = if (found.isEmpty()) "Không tìm thấy thiết bị nào." else "Tìm thấy ${found.size} thiết bị — chọn một thiết bị để điền sẵn."
            busy = false
        }
    }

    fun connect() {
        if (busy) return
        scope.launch {
            busy = true; msg = "Đang kết nối…"
            val s = Source(
                "s" + java.util.UUID.randomUUID().toString().replace("-", "").take(8), type,
                name.ifBlank { when (type) { "smb" -> host; else -> url }.ifBlank { typeLabel(type) } },
                host.trim(), share.trim().trim('/'), root.trim(), url.trim(), user.trim(), pass,
            )
            val err = withContext(Dispatchers.IO) { runCatching { Remote.test(s) }.exceptionOrNull() }
            if (err != null) { msg = "Không kết nối được: ${err.message ?: err.javaClass.simpleName}"; busy = false }
            else { SourceStore.add(s); Library.scanSourceAsync(ctx, s); busy = false; onNav(Screen.LIB) }
        }
    }

    @Composable
    fun TypeList(m: Modifier) = Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        srcTypes().forEachIndexed { i, o ->
            val added = o.id == "local" || sources.any { it.type == o.id }
            OptionCard(o.icon, o.title, o.subtitle, type == o.id, tag = if (type == o.id) "ĐANG CHỌN" else if (added) "ĐÃ THÊM" else "",
                requester = if (i == 0) first else null) { type = o.id; msg = "" }
        }
        if (status.isNotEmpty()) T(status, 14, C.Muted, Modifier.padding(top = 4.dp), lines = 2)
    }

    @Composable
    fun Detail(m: Modifier) = DetailPanel(m, scroll = !compact) {
        val meta = srcTypes().first { it.id == type }
        val mine = if (type == "local") emptyList() else sources.filter { it.type == type }
        if (!Cloud.isCloud(type)) {
            T(if (type == "local") meta.title else "Kết nối ${meta.title}", if (compact) 24 else 30, serif = true, lines = 2)
        }
        // Nguồn đã thêm thuộc loại này: quét lại / xoá
        if (mine.isNotEmpty()) {
            SectionLabel("ĐÃ THÊM")
            mine.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { T(s.name, 19, lines = 1); T("${typeLabel(s.type)} · ${all.count { it.src == s.id }} bài", 14, C.Muted, lines = 1) }
                    Focusable({ Library.scanSourceAsync(ctx, s) }) { T("Quét lại", 16, C.Gold, Modifier.padding(12.dp, 8.dp)) }
                    Focusable({ Library.removeSource(ctx, s.id) }) { T("Xoá", 16, C.Text, Modifier.padding(12.dp, 8.dp)) }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
        }
        when {
            type == "local" -> {
                T(if (Device.tv) "Nhạc trong bộ nhớ TV và USB được quét tự động mỗi khi mở app. Cắm USB vào là phát được ngay." else "Nhạc trong bộ nhớ máy và USB được quét tự động mỗi khi mở app.", 17, C.Muted, lines = 4)
                NoteBox { T("${all.count { it.src == "local" }} bài trong thư viện", 18, C.Text, bold = true) }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { GhostButton("Quét lại") { Library.rescanAllAsync(ctx) } }
            }
            Cloud.isCloud(type) -> CloudLoginPanel(type) { onNav(Screen.LIB) }
            else -> {
                if (type != "dav") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GhostButton(if (busy) "Đang tìm…" else "Tìm thiết bị trên mạng", color = C.Gold) { discover() }
                    }
                    found.filter { it.type == type }.forEach { f ->
                        Focusable({ type = f.type; name = f.name; host = f.host; url = f.url; msg = "" }, Modifier.fillMaxWidth()) { T(f.label, 18, C.Text, Modifier.padding(16.dp, 10.dp)) }
                    }
                }
                Field("Tên hiển thị", name, { name = it })
                when (type) {
                    "smb" -> {
                        Field("Địa chỉ máy chủ (IP hoặc tên)", host, { host = it })
                        FieldPair({ m2 -> Field("Thư mục chia sẻ (share)", share, { share = it }, m2) }, { m2 -> Field("Thư mục con (tuỳ chọn)", root, { root = it }, m2) })
                        FieldPair({ m2 -> Field("Tài khoản (trống = khách)", user, { user = it }, m2) }, { m2 -> Field("Mật khẩu", pass, { pass = it }, m2, password = true) })
                    }
                    "dav" -> {
                        Field("Địa chỉ WebDAV, vd http://192.168.1.20:5005/music", url, { url = it })
                        FieldPair({ m2 -> Field("Tài khoản", user, { user = it }, m2) }, { m2 -> Field("Mật khẩu", pass, { pass = it }, m2, password = true) })
                    }
                    else -> T(if (url.isEmpty()) "Bấm “Tìm thiết bị trên mạng” rồi chọn một máy chủ DLNA." else "Máy chủ: $url", 16, C.Muted, lines = 2)
                }
                if (msg.isNotEmpty()) T(msg, 16, C.Gold, lines = 3)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    GhostButton("Huỷ") { onNav(Screen.LIB) }
                    PrimaryButton(if (busy) "Đang xử lý…" else "Kết nối & quét nhạc") { connect() }
                }
            }
        }
    }

    if (compact) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Thêm nguồn nhạc", "Chọn nơi chứa nhạc của bạn")
        TypeList(Modifier.fillMaxWidth())
        Detail(Modifier.fillMaxWidth())
    } else Row(Modifier.fillMaxSize().background(C.Bg)) {
        Rail(Screen.SOURCES, onNav)
        Row(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 36.dp, vertical = 28.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(0.9f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PageHeader("Thêm nguồn nhạc", "Chọn nơi chứa nhạc của bạn")
                TypeList(Modifier.fillMaxWidth())
            }
            Detail(Modifier.weight(1.1f).fillMaxHeight())
        }
    }
}
