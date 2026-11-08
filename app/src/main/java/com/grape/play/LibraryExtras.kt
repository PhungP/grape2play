package com.grape.play

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Hộp thoại chọn nguồn nào hiện ở Thư viện, nguồn nào ẩn (không dùng) cho đỡ rối. Nhạc không bị xoá, bật lại là thấy ngay. */
@Composable
fun SourceVisibilityDialog(sources: List<Source>, onClose: () -> Unit) {
    val hidden by SourceVisibility.hidden
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val rows = listOf("local" to (if (Device.tv) "Bộ nhớ TV & USB" else "Bộ nhớ máy & USB")) + sources.map { it.id to it.name }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.dlgW(760).clip(RoundedCornerShape(18.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(18.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T("NGUỒN HIỂN THỊ Ở THƯ VIỆN", 13, C.Muted, spaced = true)
            T("Bấm vào từng nguồn để hiện hoặc ẩn. Nguồn bị ẩn không xuất hiện trong Thư viện và không lẫn vào mục “Tất cả”.", 15, C.Muted, lines = 3)
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEachIndexed { i, (id, name) ->
                    val vis = id !in hidden
                    Focusable({ SourceVisibility.toggle(id) }, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp), selected = vis, requester = if (i == 0) first else null) {
                        Row(Modifier.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(name, 20, if (vis) C.Gold else C.Muted, Modifier.weight(1f), lines = 2)
                            T(if (vis) "● Hiện" else "○ Ẩn", 18, if (vis) C.Gold else C.Muted, bold = true)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                Focusable(onClose, Modifier.width(200.dp)) { T("Đóng", 20, C.Gold, Modifier.align(Alignment.Center).padding(20.dp, 10.dp), bold = true) }
            }
        }
    }
}
