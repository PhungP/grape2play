package com.grape.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Duyệt trực tiếp một máy chủ UPnP/DLNA (ví dụ ổ USB cắm vào WiiM Ultra): mở được ngay từng thư mục,
 * không cần quét cả máy chủ trước. Hiện từng trang ngay khi nhận được; lỗi hiện rõ kèm nút Thử lại.
 */
@Composable
fun DlnaBrowser(s: Source, onNav: (Screen) -> Unit) {
    var stack by remember(s.id) { mutableStateOf(listOf<Pair<String, String>>()) } // (id, tên) từ gốc đến thư mục hiện tại
    var entries by remember(s.id) { mutableStateOf<List<DEntry>>(emptyList()) }
    var loading by remember(s.id) { mutableStateOf(true) }
    var err by remember(s.id) { mutableStateOf("") }
    var retry by remember(s.id) { mutableStateOf(0) }
    val curId = stack.lastOrNull()?.first ?: "0"
    val dir = stack.joinToString("/") { it.second }
    val cur = Playback.queue.collectAsState().value.getOrNull(Playback.index.collectAsState().value)
    BackHandler(enabled = stack.isNotEmpty()) { stack = stack.dropLast(1) }

    LaunchedEffect(s.id, curId, retry) {
        entries = emptyList(); err = ""; loading = true
        try {
            var start = 0
            var firstPrev: String? = null
            while (true) {
                val pg = withContext(Dispatchers.IO) { Upnp.page(s, curId, dir, start, 100) }
                if (pg.entries.isEmpty()) break
                if (pg.entries.first().id == firstPrev) break // máy chủ bỏ qua StartingIndex
                firstPrev = pg.entries.first().id
                entries = entries + pg.entries
                start += pg.returned
                if ((pg.total in 1..start) || entries.size >= 3000) break
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
        loading = false
    }

    val conts = entries.filter { it.container }
    val tracks = entries.mapNotNull { it.track }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            T(s.name + if (stack.isEmpty()) "" else "  ›  " + stack.joinToString("  ›  ") { it.second }, 24, C.Gold, Modifier.weight(1f))
            if (stack.isNotEmpty()) Focusable({ stack = stack.dropLast(1) }) { T("◂ Lên một cấp", 18, C.Text, Modifier.padding(16.dp, 8.dp)) }
            if (tracks.isNotEmpty()) {
                Focusable({ Playback.play(tracks, 0, false); onNav(Screen.NOW) }) { T("▶ Phát các bài ở đây", 18, C.Gold, Modifier.padding(16.dp, 8.dp)) }
                Focusable({ Playback.playShuffled(tracks); onNav(Screen.NOW) }) { T("⤮ Ngẫu nhiên", 18, C.Gold, Modifier.padding(16.dp, 8.dp)) }
            }
        }
        if (loading) T("Đang mở thư mục… ${entries.size} mục", 18, C.Muted)
        if (err.isNotEmpty()) {
            T("Không mở được: $err", 18, C.Gold, lines = 3)
            Focusable({ retry++ }) { T("Thử lại", 18, C.Gold, Modifier.padding(16.dp, 8.dp)) }
        }
        if (!loading && err.isEmpty() && entries.isEmpty()) T("Thư mục trống (hoặc không có nhạc).", 18, C.Muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(conts) { _, d ->
                Focusable({ stack = stack + (d.id to d.title) }, Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        T("▸", 22, C.Gold)
                        T(d.title, 22, C.Text, Modifier.weight(1f))
                        if (d.childCount >= 0) T("${d.childCount} mục", 16, C.Muted)
                    }
                }
            }
            itemsIndexed(tracks) { i, t -> TrackRow(i, t, t.path == cur?.path) { Playback.play(tracks, i, false); onNav(Screen.NOW) } }
        }
    }
}
