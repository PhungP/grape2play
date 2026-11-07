package com.grape.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun QrCode(text: String, side: Dp) {
    val m = remember(text) { runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0)) }.getOrNull() }
    Box(Modifier.size(side).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF1EBDD)).padding(12.dp)) {
        if (m != null) Canvas(Modifier.fillMaxSize()) {
            val n = m.width
            val cell = size.minDimension / n
            for (y in 0 until n) for (x in 0 until n) if (m.get(x, y)) drawRect(Color(0xFF0A0908), Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

private class Holder { var job: Job? = null; var ses: LoginSession? = null }

/** Đăng nhập Google Drive / OneDrive → chọn thư mục nhạc → thêm nguồn và quét. */
@Composable
fun CloudLoginPanel(type: String, onAdded: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val title = if (type == "gdrive") "Google Drive" else "OneDrive"
    val sid = remember(type) { "s" + java.util.UUID.randomUUID().toString().replace("-", "").take(8) }
    val h = remember(type) { Holder() }
    var phase by remember(type) { mutableStateOf("idle") }
    var msg by remember(type) { mutableStateOf("") }
    var session by remember(type) { mutableStateOf<LoginSession?>(null) }
    var tokens by remember(type) { mutableStateOf<Tokens?>(null) }
    var account by remember(type) { mutableStateOf("" to "") }
    DisposableEffect(type) { onDispose { h.job?.cancel(); h.ses?.cancel(); Cloud.forget(sid) } }

    fun begin() {
        phase = "wait"; msg = ""; session = null
        h.job?.cancel(); h.ses?.cancel()
        h.job = scope.launch {
            try {
                val ses = CloudAuth.start(type); h.ses = ses; session = ses
                val tk = ses.await()
                Cloud.prime(sid, tk.access, tk.expiresIn)
                val acc = withContext(Dispatchers.IO) { Cloud.profile(type, tk.access) }
                tokens = tk; account = acc; phase = "pick"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { msg = e.message ?: e.javaClass.simpleName; phase = "error"
            } finally { h.ses?.cancel() }
        }
    }

    val compact = LocalCompact.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Các bước: 1 đăng nhập → 2 chọn thư mục → 3 quét thư viện
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepChip("1 ĐĂNG NHẬP", phase != "pick")
            StepChip("2 CHỌN THƯ MỤC", phase == "pick")
            StepChip("3 QUÉT THƯ VIỆN", false)
        }
        when (phase) {
            "idle", "error" -> {
                T("Đăng nhập $title", if (compact) 24 else 30, serif = true, lines = 2)
                T(if (type == "gdrive") "Dùng được cả tài khoản Gmail cá nhân và Google Workspace. Việc đăng nhập thực hiện trên điện thoại, mật khẩu không nhập trên TV."
                  else "Dùng được cả OneDrive cá nhân và OneDrive doanh nghiệp / trường học (Microsoft 365). Việc đăng nhập thực hiện trên điện thoại, mật khẩu không nhập trên TV.", 16, C.Muted, lines = 4)
                T("Có thể thêm nhiều tài khoản — mỗi lần đăng nhập là một nguồn riêng.", 15, C.Gold, lines = 2)
                if (!Cloud.configured(type)) T("⚠ Chưa cấu hình Client ID cho $title. Mở OAuthConfig.kt và làm theo README (mục “Đăng nhập đám mây”).", 16, C.Gold, lines = 3)
                if (msg.isNotEmpty()) T(msg, 16, C.Gold, lines = 4)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PrimaryButton(if (phase == "error") "Thử lại" else "Bắt đầu đăng nhập bằng điện thoại") { begin() }
                }
            }
            "wait" -> {
                T("Đăng nhập $title", if (compact) 24 else 30, serif = true, lines = 2)
                val s = session
                if (s == null) T("Đang chuẩn bị đăng nhập…", 20, C.Muted)
                else {
                    @Composable fun Steps(m: Modifier) = Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.steps.forEach { T(it, 16, C.Text, lines = 5) }
                        if (s.code.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            T("Mã ghép nối", 14, C.Muted)
                            // Mã đăng nhập: chữ lớn, giãn chữ, không cắt (không dùng "…")
                            androidx.compose.material3.Text(s.code, color = C.Gold, fontSize = (if (compact) 34 else 44).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                letterSpacing = 4.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                        }
                    }
                    if (compact) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { QrCode(s.url, 200.dp); Steps(Modifier.fillMaxWidth()) }
                    else Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) { QrCode(s.url, 210.dp); Steps(Modifier.weight(1f)) }
                    T(s.url, 14, C.Muted, lines = 2)
                }
                NoteBox {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Dot(); T("Đang chờ xác nhận trên điện thoại…", 17, C.Text)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    GhostButton("Huỷ") { h.job?.cancel(); h.ses?.cancel(); phase = "idle" }
                    PrimaryButton("Tạo mã mới") { begin() }
                }
            }
            "pick" -> {
                val tk = tokens
                if (tk != null) {
                    val tmp = Source(sid, type, "", user = account.first, pass = tk.refresh)
                    val kind = if (account.second == "personal") "cá nhân" else "doanh nghiệp / khác"
                    T("✓ Đã đăng nhập: ${account.first}", 22, C.Gold, lines = 2)
                    T("Loại tài khoản: $kind", 15, C.Muted)
                    FolderPick(tmp) { id, path ->
                        // Cùng một tài khoản đăng nhập lại: cập nhật nguồn cũ thay vì tạo bản sao
                        val old = SourceStore.list.value.firstOrNull { it.type == type && it.user.equals(account.first, ignoreCase = true) }
                        val s = Source(old?.id ?: sid, type, "$title · ${account.first}", host = account.second, root = id, url = path, user = account.first, pass = tk.refresh)
                        if (old != null) { Cloud.forget(old.id); SourceStore.update(s) } else SourceStore.add(s)
                        if (old != null) Cloud.prime(s.id, tk.access, tk.expiresIn)
                        Library.scanSourceAsync(ctx, s); onAdded()
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPick(s: Source, onChoose: (id: String, path: String) -> Unit) {
    var stack by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var folders by remember { mutableStateOf<List<CloudItem>?>(null) }
    var err by remember { mutableStateOf("") }
    val rootId = if (s.type == "gdrive") "root" else ""
    val curId = stack.lastOrNull()?.first ?: rootId
    val path = if (stack.isEmpty()) "" else stack.joinToString("/") { it.second }
    LaunchedEffect(curId) {
        folders = null; err = ""
        try { folders = withContext(Dispatchers.IO) { Cloud.list(s, curId).filter { it.folder } } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { err = e.message ?: e.javaClass.simpleName; folders = emptyList() }
    }
    T("Chọn thư mục chứa nhạc", 20, C.Text)
    T("Vị trí: " + if (stack.isEmpty()) "Toàn bộ ${if (s.type == "gdrive") "Drive của tôi" else "OneDrive"}" else path, 16, C.Muted, lines = 2)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton("✓ Dùng thư mục này & quét nhạc") { onChoose(curId, path.ifEmpty { "/" }) }
        if (stack.isNotEmpty()) GhostButton("◂ Lên một cấp") { stack = stack.dropLast(1) }
    }
    if (err.isNotEmpty()) T(err, 16, C.Gold, lines = 3)
    val list = folders
    if (list == null) T("Đang tải thư mục…", 18, C.Muted)
    else LazyColumn(Modifier.height(190.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(list, key = { it.id }) { f ->
            Focusable({ stack = stack + (f.id to f.name) }, Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp, 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { T("▸", 20, C.Gold); T(f.name, 20, C.Text) }
            }
        }
    }
}
