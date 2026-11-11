package com.grape.play

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Thẻ trong Cài đặt: mở cửa sổ nhật ký lỗi. */
@Composable
fun LogCard() {
    var open by remember { mutableStateOf(false) }
    Card {
        T("NHẬT KÝ LỖI", 13, C.Muted, spaced = true)
        T("Ghi lại các lỗi phát nhạc, kết nối loa/NAS, cập nhật trong lúc dùng. Khi gặp sự cố, gửi nhật ký này cho nhà phát triển để kiểm tra.", 14, C.Muted, lines = 4)
        Focusable({ open = true }, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) { T("Xem / gửi nhật ký", 18, C.Gold, Modifier.padding(14.dp, 9.dp), bold = true) }
    }
    if (open) LogDialog { open = false }
}

/**
 * Cửa sổ nhật ký. Điện thoại: bấm "Gửi" để mở email/chia sẻ kèm file.
 * TV: không có ứng dụng chia sẻ nên hiện địa chỉ http://IP:cổng/log — mở địa chỉ này bằng trình duyệt điện thoại/máy tính cùng mạng để xem, lưu hoặc gửi đi.
 */
@Composable
fun LogDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    var lines by remember { mutableStateOf(AppLog.tail(40)) }
    var msg by remember { mutableStateOf("") }
    var url by remember { mutableStateOf<String?>(null) }
    val compact = LocalCompact.current
    val big = compact
    fun f(n: Int) = if (big) n else (n * 0.62f).toInt().coerceAtLeast(10)

    DisposableEffect(Unit) { onDispose { AppLog.lanShare = false } }
    LaunchedEffect(Unit) {
        delay(150); runCatching { first.requestFocus() }
        // địa chỉ để máy khác trong mạng đọc nhật ký (chỉ mở trong lúc cửa sổ này còn mở)
        url = withContext(Dispatchers.IO) { runCatching { StreamServer.logUrl() }.getOrNull() }
        AppLog.lanShare = url != null
        while (true) { delay(2000); lines = AppLog.tail(40) }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            (if (compact) Modifier.dlgW(620) else Modifier.dlgFrac(0.5f)).clip(RoundedCornerShape(14.dp)).background(C.Panel)
                .border(1.dp, C.Line, RoundedCornerShape(14.dp)).dlgPad(26, 18),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            T("NHẬT KÝ LỖI", f(13), C.Muted, spaced = true)
            T("Nhật ký sử dụng ứng dụng", f(26), serif = true, bold = true, lines = 2)
            Box(Modifier.fillMaxWidth().height(if (compact) 220.dp else 260.dp).clip(RoundedCornerShape(8.dp)).background(C.Bg).border(1.dp, C.Line, RoundedCornerShape(8.dp)).padding(8.dp)) {
                val sc = rememberScrollState()
                LaunchedEffect(lines.size) { sc.scrollTo(sc.maxValue) }
                Column(Modifier.verticalScroll(sc)) {
                    if (lines.isEmpty()) T("Chưa có dòng nhật ký nào.", f(15), C.Muted)
                    for (l in lines) Text(l, color = if (" E/" in l) C.Gold else C.Muted, fontSize = (if (compact) 11 else f(12)).sp, fontFamily = FontFamily.Monospace, lineHeight = (if (compact) 14 else f(15)).sp)
                }
            }
            if (!compact) {
                val u = url
                if (u != null) {
                    T("Để gửi cho nhà phát triển: trên điện thoại/máy tính cùng mạng, mở trình duyệt và nhập địa chỉ bên dưới, rồi lưu hoặc sao chép nội dung gửi đi:", f(14), C.Muted, lines = 3)
                    T(u, f(20), C.Gold, bold = true)
                } else T("TV chưa kết nối mạng nên chưa thể mở nhật ký qua trình duyệt.", f(14), C.Muted, lines = 2)
            }
            if (msg.isNotEmpty()) T(msg, f(14), C.Gold, lines = 3)
            T("Email nhà phát triển: ${AppLog.DEV_EMAIL}", f(14), C.Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End)) {
                Focusable({ AppLog.clear(); lines = AppLog.tail(40); msg = "Đã xoá nhật ký." }) { T("Xoá", f(18), C.Muted, Modifier.padding(if (big) 22.dp else 14.dp, if (big) 10.dp else 6.dp), bold = true) }
                if (compact) Focusable({ msg = AppLog.share(ctx).orEmpty() }) {
                    T("Gửi", f(18), C.Gold, Modifier.padding(22.dp, 10.dp), bold = true)
                }
                Focusable(onClose, requester = first) { T("Đóng", f(18), C.Gold, Modifier.padding(if (big) 22.dp else 14.dp, if (big) 10.dp else 6.dp), bold = true) }
            }
        }
    }
}
