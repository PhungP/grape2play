package com.grape.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun LibraryScreen(hasPerm: Boolean, askPerm: () -> Unit, onNav: (Screen) -> Unit, onAdd: () -> Unit, onOut: () -> Unit) {
    val rawTracks by Library.tracks.collectAsState()
    val hidden by SourceVisibility.hidden
    val allTracks = remember(rawTracks, hidden) { if (hidden.isEmpty()) rawTracks else rawTracks.filter { it.src !in hidden } }
    val scanning by Library.scanning.collectAsState()
    val status by Library.status.collectAsState()
    val sources by SourceStore.list.collectAsState()
    val queue by Playback.queue.collectAsState()
    val idx by Playback.index.collectAsState()
    val playing by Playback.playing.collectAsState()
    val ctx = LocalContext.current.applicationContext
    var tab by remember { mutableStateOf(Tab.ALBUM) }
    var sel by remember { mutableStateOf<Group?>(null) }
    var srcFilter by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf(false) } // duyệt trực tiếp máy chủ DLNA
    var showVis by remember { mutableStateOf(false) }
    LaunchedEffect(hidden) { if (srcFilter != null && srcFilter in hidden) { srcFilter = null; sel = null; live = false } }
    val srcSource = srcFilter?.let { SourceStore.get(it) }
    val dlnaLive = live && srcSource?.type == "dlna"
    val all = remember(allTracks, srcFilter) { if (srcFilter == null) allTracks else allTracks.filter { it.src == srcFilter } }
    val groups = remember(all, tab) { Library.groups(tab, all) }
    val cur = queue.getOrNull(idx)

    BackHandler { if (sel != null) sel = null else if (live) live = false else onNav(Screen.NOW) }

    Row(Modifier.fillMaxSize().background(C.Bg)) {
        Rail(Screen.LIB, onNav, onAdd, onOut)
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 40.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // ---- đầu trang: tên app + lọc theo nguồn (mỗi tài khoản đám mây là một nguồn)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Wordmark(34, Modifier.padding(end = 18.dp))
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    item { Chip("Tất cả", srcFilter == null) { srcFilter = null; sel = null; live = false } }
                    if ("local" !in hidden) item { Chip("Bộ nhớ TV & USB", srcFilter == "local") { srcFilter = "local"; sel = null; live = false } }
                    items(sources.filter { it.id !in hidden }, key = { it.id }) { s -> Chip(s.name, srcFilter == s.id) { srcFilter = s.id; sel = null; live = s.type == "dlna" } }
                    item { Focusable({ showVis = true }) { T("⚙ Nguồn hiển thị", 16, C.Gold, Modifier.padding(12.dp, 7.dp)) } }
                    item { Focusable(onAdd) { T("+ Thêm nguồn", 16, C.Gold, Modifier.padding(12.dp, 7.dp)) } }
                }
                if (scanning) Focusable({ Library.cancelScan() }) { T("Dừng quét", 16, C.Gold, Modifier.padding(12.dp, 7.dp)) }
            }
            // ---- tab + thống kê
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tab.values().forEach { tb ->
                    Focusable({ tab = tb; sel = null }, selected = tb == tab) { T(tb.label, 25, if (tb == tab) C.Gold else C.Muted, Modifier.padding(16.dp, 8.dp), serif = true, bold = tb == tab) }
                }
                if (srcSource?.type == "dlna") Focusable({ live = !live }, selected = live) { T(if (live) "Xem thư viện đã quét" else "📂 Duyệt thiết bị", 16, C.Gold, Modifier.padding(12.dp, 7.dp)) }
                Spacer(Modifier.weight(1f))
                T(if (status.isNotEmpty()) "${all.size} bài · $status" else "${all.size} bài", 15, C.Muted, Modifier.widthIn(max = 620.dp), lines = 2)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    dlnaLive -> DlnaBrowser(srcSource!!, onNav)
                    !hasPerm && allTracks.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        T("GRAPEPlay cần quyền đọc bộ nhớ để tìm nhạc trên TV/USB.", 26)
                        Focusable(askPerm, Modifier.width(300.dp)) { T("Cấp quyền", 22, C.Gold, Modifier.padding(18.dp, 12.dp)) }
                        T("Hoặc bấm “+ Thêm nguồn” để dùng nhạc từ NAS.", 18, C.Muted)
                    }
                    tab == Tab.FAV -> FavoritesView(allTracks, cur, onNav)
                    all.isEmpty() -> T(if (scanning) "Đang quét nhạc…" else "Không tìm thấy nhạc. Cắm USB rồi bấm “Quét lại”, hoặc thêm nguồn NAS.", 24, C.Muted)
                    tab == Tab.FOLDER -> FolderBrowser(all, onNav)
                    sel != null -> Detail(sel!!, onNav)
                    tab == Tab.SONG -> {
                        val songs = remember(all) { all.sortedBy { it.title.lowercase() } }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(songs) { i, t -> TrackRow(i, t, t.path == cur?.path) { Playback.play(songs, i); onNav(Screen.NOW) } }
                        }
                    }
                    else -> LazyVerticalGrid(GridCells.Fixed(6), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(groups, key = { it.key }) { g -> AlbumTile(g) { sel = g } }
                    }
                }
            }
            if (showVis) SourceVisibilityDialog(sources) { showVis = false }
            if (cur != null) Focusable({ onNav(Screen.NOW) }, Modifier.fillMaxWidth(), RoundedCornerShape(12.dp)) {
                Row(Modifier.background(C.Panel).padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    CoverImage(cur, Modifier.size(56.dp), 4)
                    Column(Modifier.weight(1f)) { T(cur.title, 21); T("${cur.artist} · ${formatBadge(cur)}", 15, C.Muted) }
                    T(if (playing) "Đang phát" else "Tạm dừng", 16, C.Muted)
                    T("Mở màn hình Đang phát ▸", 16, C.Gold)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) =
    Focusable(onClick, selected = selected, shape = RoundedCornerShape(20.dp)) {
        T(text, 16, if (selected) C.Gold else C.Text, Modifier.padding(14.dp, 7.dp))
    }

@Composable
fun AlbumTile(g: Group, onClick: () -> Unit) {
    val first = g.tracks.first()
    val dsd = first.ext == "dsf" || first.ext == "dff"
    Focusable(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                CoverImage(first, Modifier.fillMaxSize(), 8)
                Box(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Bg.copy(alpha = 0.8f)).border(1.dp, if (dsd) C.PlumSoft else C.Gold, RoundedCornerShape(4.dp)).padding(6.dp, 2.dp)) {
                    T(formatBadge(first), 12, if (dsd) C.PlumSoft else C.Gold, bold = true)
                }
            }
            Spacer(Modifier.height(8.dp))
            T(g.title, 19, bold = true); T(g.subtitle, 14, C.Muted)
        }
    }
}

@Composable
private fun Detail(g: Group, onNav: (Screen) -> Unit) {
    val cur = Playback.queue.collectAsState().value.getOrNull(Playback.index.collectAsState().value)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(g.tracks.first(), Modifier.size(180.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(g.title, 44, serif = true); T(g.subtitle, 20, C.Gold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                    Focusable({ Playback.play(g.tracks, 0, false); onNav(Screen.NOW) }) { T("▶ Phát tuần tự", 20, C.Gold, Modifier.padding(18.dp, 10.dp)) }
                    Focusable({ Playback.playShuffled(g.tracks); onNav(Screen.NOW) }) { T("⤮ Phát ngẫu nhiên", 20, C.Gold, Modifier.padding(18.dp, 10.dp)) }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(g.tracks) { i, t -> TrackRow(i, t, t.path == cur?.path) { Playback.play(g.tracks, i, false); onNav(Screen.NOW) } }
        }
    }
}

@Composable
private fun FavoritesView(all: List<Track>, cur: Track?, onNav: (Screen) -> Unit) {
    val paths = Favorites.paths.value
    val favs = remember(all, paths) { Favorites.tracks(all) }
    if (favs.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            T("Chưa có bài hát yêu thích", 26)
            T("Giữ nút OK trên một bài hát (hoặc bấm ♥ ở màn Đang phát) để thêm vào danh sách yêu thích.", 18, C.Muted, lines = 2)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Focusable({ Playback.play(favs, 0, false); onNav(Screen.NOW) }) { T("▶ Phát tuần tự", 20, C.Gold, Modifier.padding(18.dp, 10.dp)) }
            Focusable({ Playback.playShuffled(favs); onNav(Screen.NOW) }) { T("⤮ Phát ngẫu nhiên", 20, C.Gold, Modifier.padding(18.dp, 10.dp)) }
            T("${favs.size} bài yêu thích · giữ OK để bỏ yêu thích", 15, C.Muted)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(favs) { i, t -> TrackRow(i, t, t.path == cur?.path) { Playback.play(favs, i, false); onNav(Screen.NOW) } }
        }
    }
}
