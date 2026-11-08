@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.grape.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import kotlin.math.roundToInt

/**
 * Giao diện cảm ứng cho điện thoại / máy tính bảng Android (TV vẫn dùng giao diện điều khiển bằng remote).
 * Dọc: thanh điều hướng dưới + mini-player. Ngang: thanh điều hướng bên trái.
 */
@Composable
fun MobileApp(hasPerm: Boolean, askPerm: () -> Unit, screen: Screen, onNav: (Screen) -> Unit, onAdd: () -> Unit, onOut: () -> Unit) {
    val cfg = LocalConfiguration.current
    val land = cfg.screenWidthDp > cfg.screenHeightDp
    val queue by Playback.queue.collectAsState()
    val idx by Playback.index.collectAsState()
    val cur = queue.getOrNull(idx)
    BackHandler(screen != Screen.LIB) { onNav(Screen.LIB) }

    @Composable fun Page(m: Modifier) = Box(m) {
        when (screen) {
            Screen.LIB -> MobileLibrary(hasPerm, askPerm, onNav, onAdd, onOut)
            Screen.NOW -> MobileNow(onOut)
            Screen.SOUND -> MobileSettings(onOut)
            Screen.SOURCES -> SourcesPage(onNav)
            Screen.OUTPUT -> OutputPage(onNav)
        }
    }

    if (land) Row(Modifier.fillMaxSize().background(C.Bg)) {
        MobileRail(screen, onNav)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Page(Modifier.weight(1f).fillMaxWidth())
            if (cur != null && screen != Screen.NOW) MiniPlayer(cur, onNav)
        }
    } else Column(Modifier.fillMaxSize().background(C.Bg)) {
        MobileTopBar(screen, onNav)
        Page(Modifier.weight(1f).fillMaxWidth())
        if (cur != null && screen != Screen.NOW) MiniPlayer(cur, onNav)
    }
}

private class NavItem(val s: Screen, val icon: ImageVector, val label: String)
private val NAV = listOf(
    NavItem(Screen.LIB, Icons.Filled.LibraryMusic, "Thư viện"),
    NavItem(Screen.NOW, Icons.Filled.PlayCircle, "Đang phát"),
    NavItem(Screen.SOUND, Icons.Filled.Settings, "Cài đặt"),
)

/** Thanh điều hướng trên cùng (điện thoại dọc): ba phân đoạn Thư viện · Đang phát · Cài đặt, nằm xa vùng bấm của thanh điều hướng hệ thống. */
@Composable
private fun MobileTopBar(cur: Screen, onNav: (Screen) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val active = if (cur == Screen.SOURCES || cur == Screen.OUTPUT) Screen.LIB else cur
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().clip(shape).background(C.Panel).border(1.dp, C.Line, shape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NAV.forEach { n ->
                val sel = n.s == active
                val seg = RoundedCornerShape(10.dp)
                Row(
                    Modifier.weight(1f).clip(seg).background(if (sel) C.Sel else Color.Transparent).clickable { onNav(n.s) }.padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(n.icon, null, tint = if (sel) C.Gold else C.Muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    T(n.label, 14, if (sel) C.Gold else C.Muted, bold = sel)
                }
            }
        }
    }
}

@Composable
private fun MobileRail(cur: Screen, onNav: (Screen) -> Unit) {
    Column(Modifier.width(84.dp).fillMaxHeight().background(C.Side), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        NAV.forEach { n ->
            val sel = n.s == cur
            Column(Modifier.fillMaxWidth().clickable { onNav(n.s) }.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(n.icon, null, tint = if (sel) C.Gold else C.Muted, modifier = Modifier.size(26.dp))
                T(n.label, 13, if (sel) C.Gold else C.Muted)
            }
        }
    }
}

@Composable
private fun IconBtn(icon: ImageVector, desc: String, size: Int = 26, tint: Color = C.Text, onClick: () -> Unit) =
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(size.dp))
    }

@Composable
private fun MButton(text: String, modifier: Modifier = Modifier, color: Color = C.Gold, onClick: () -> Unit) =
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, 18, color, bold = true) }

@Composable
private fun MChip(text: String, selected: Boolean, onClick: () -> Unit) =
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(if (selected) C.Sel else Color.Transparent)
            .border(1.dp, if (selected) C.Gold else C.Line, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { T(text, 15, if (selected) C.Gold else C.Text) }

// ---------------------------------------------------------------- Mini-player
@Composable
private fun MiniPlayer(t: Track, onNav: (Screen) -> Unit) {
    val playing by Playback.playing.collectAsState()
    val pos by Playback.position.collectAsState()
    val dur by Playback.duration.collectAsState()
    Column(Modifier.fillMaxWidth().background(C.Panel)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.Line)) {
            Box(Modifier.fillMaxWidth(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f).fillMaxHeight().background(C.Gold))
        }
        Row(
            Modifier.fillMaxWidth().clickable { onNav(Screen.NOW) }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CoverImage(t, Modifier.size(44.dp), 4)
            Column(Modifier.weight(1f)) { T(t.title, 17); T(t.artist, 14, C.Muted) }
            IconBtn(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Phát / Dừng", 30) { Playback.toggle() }
            IconBtn(Icons.Filled.SkipNext, "Bài sau", 28) { Playback.next() }
        }
    }
}

// ---------------------------------------------------------------- Thư viện
@Composable
private fun MobileLibrary(hasPerm: Boolean, askPerm: () -> Unit, onNav: (Screen) -> Unit, onAdd: () -> Unit, onOut: () -> Unit) {
    val rawTracks by Library.tracks.collectAsState()
    val hidden by SourceVisibility.hidden
    val allTracks = remember(rawTracks, hidden) { if (hidden.isEmpty()) rawTracks else rawTracks.filter { it.src !in hidden } }
    val scanning by Library.scanning.collectAsState()
    val status by Library.status.collectAsState()
    val sources by SourceStore.list.collectAsState()
    val queue by Playback.queue.collectAsState()
    val idx by Playback.index.collectAsState()
    val ctx = LocalContext.current.applicationContext
    var tab by remember { mutableStateOf(Tab.ALBUM) }
    var sel by remember { mutableStateOf<Group?>(null) }
    var srcFilter by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf(false) }
    var showVis by remember { mutableStateOf(false) }
    var showTv by remember { mutableStateOf(false) }
    if (showTv) TvRemoteDialog { showTv = false }
    LaunchedEffect(hidden) { if (srcFilter != null && srcFilter in hidden) { srcFilter = null; sel = null; live = false } }
    val srcSource = srcFilter?.let { SourceStore.get(it) }
    val dlnaLive = live && srcSource?.type == "dlna"
    val all = remember(allTracks, srcFilter) { if (srcFilter == null) allTracks else allTracks.filter { it.src == srcFilter } }
    val groups = remember(all, tab) { Library.groups(tab, all) }
    val cur = queue.getOrNull(idx)

    BackHandler(sel != null || live) { if (sel != null) sel = null else live = false }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(28)
            Spacer(Modifier.weight(1f))
            if (scanning) IconBtn(Icons.Filled.Close, "Dừng quét") { Library.cancelScan() }
            else IconBtn(Icons.Filled.Refresh, "Quét lại") { Library.rescanAllAsync(ctx) }
            IconBtn(Icons.Filled.Tv, "Điều khiển TV") { showTv = true }
            IconBtn(Icons.Filled.Speaker, "Đầu ra âm thanh") { onOut() }
            IconBtn(Icons.Filled.Visibility, "Nguồn hiển thị") { showVis = true }
            IconBtn(Icons.Filled.Add, "Thêm nguồn nhạc") { onAdd() }
        }
        if (showVis) SourceVisibilityDialog(sources) { showVis = false }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            item { MChip("Tất cả", srcFilter == null) { srcFilter = null; sel = null; live = false } }
            if ("local" !in hidden) item { MChip(if (Device.tv) "Bộ nhớ TV & USB" else "Bộ nhớ máy & USB", srcFilter == "local") { srcFilter = "local"; sel = null; live = false } }
            items(sources.filter { it.id !in hidden }, key = { it.id }) { s -> MChip(s.name, srcFilter == s.id) { srcFilter = s.id; sel = null; live = s.type == "dlna" } }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            items(Tab.values().toList()) { tb ->
                Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { tab = tb; sel = null }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    T(tb.label, 22, if (tb == tab) C.Gold else C.Muted, serif = true, bold = tb == tab)
                }
            }
            if (srcSource?.type == "dlna") item {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { live = !live }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    T(if (live) "Xem thư viện đã quét" else "Duyệt thiết bị", 15, C.Gold)
                }
            }
        }
        T(if (status.isNotEmpty()) "${all.size} bài · $status" else "${all.size} bài", 13, C.Muted, lines = 2)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                dlnaLive -> DlnaBrowser(srcSource!!, onNav)
                !hasPerm && allTracks.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    T("GRAPEPlay cần quyền đọc nhạc trên máy để tìm bài hát.", 20, lines = 3)
                    MButton("Cấp quyền", onClick = askPerm)
                    T("Hoặc bấm dấu + để dùng nhạc từ NAS / đám mây.", 15, C.Muted, lines = 2)
                }
                tab == Tab.FAV -> MFavorites(allTracks, cur, onNav)
                all.isEmpty() -> T(if (scanning) "Đang quét nhạc…" else "Không tìm thấy nhạc. Bấm làm mới để quét lại, hoặc thêm nguồn NAS bằng dấu +.", 18, C.Muted, lines = 4)
                tab == Tab.FOLDER -> FolderBrowser(all, onNav)
                sel != null -> MDetail(sel!!, cur, onNav)
                tab == Tab.SONG -> {
                    val songs = remember(all) { all.sortedBy { it.title.lowercase() } }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(songs) { i, t -> TrackRow(i, t, t.path == cur?.path) { Playback.play(songs, i); onNav(Screen.NOW) } }
                    }
                }
                else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(groups, key = { it.key }) { g -> AlbumTile(g) { sel = g } }
                }
            }
        }
    }
}

@Composable
private fun PlayButtons(tracks: List<Track>, onNav: (Screen) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MButton("▶ Phát", Modifier.weight(1f)) { Playback.play(tracks, 0, false); onNav(Screen.NOW) }
        MButton("⤮ Ngẫu nhiên", Modifier.weight(1f)) { Playback.playShuffled(tracks); onNav(Screen.NOW) }
    }
}

@Composable
private fun MDetail(g: Group, cur: Track?, onNav: (Screen) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(g.tracks.first(), Modifier.size(100.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T(g.title, 26, serif = true, lines = 2); T(g.subtitle, 16, C.Gold, lines = 2)
            }
        }
        PlayButtons(g.tracks, onNav)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(g.tracks) { i, t -> TrackRow(i, t, t.path == cur?.path) { Playback.play(g.tracks, i, false); onNav(Screen.NOW) } }
        }
    }
}

@Composable
private fun MFavorites(all: List<Track>, cur: Track?, onNav: (Screen) -> Unit) {
    val paths = Favorites.paths.value
    val favs = remember(all, paths) { Favorites.tracks(all) }
    if (favs.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            T("Chưa có bài hát yêu thích", 22)
            T("Bấm biểu tượng ♥ ở màn hình Đang phát để thêm bài vào danh sách yêu thích.", 16, C.Muted, lines = 3)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayButtons(favs, onNav)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(favs) { i, t -> TrackRow(i, t, t.path == cur?.path) { Playback.play(favs, i, false); onNav(Screen.NOW) } }
        }
    }
}

// ---------------------------------------------------------------- Đang phát
@Composable
private fun MobileNow(onOut: () -> Unit) {
    val queue by Playback.queue.collectAsState()
    val idx by Playback.index.collectAsState()
    val t = queue.getOrNull(idx)
    val cfg = LocalConfiguration.current
    val land = cfg.screenWidthDp > cfg.screenHeightDp
    var showQueue by remember { mutableStateOf(false) }
    val style by Themes.style
    if (style != 0) { SymNow(style, {}, onOut); return }
    if (t == null) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            T("Chưa có bài nào đang phát. Mở Thư viện để chọn nhạc.", 20, C.Muted, lines = 3)
        }
        return
    }
    if (land) Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverImage(t, Modifier.fillMaxHeight(0.92f).aspectRatio(1f), 14)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { NowControls(t, onOut) { showQueue = true } }
    } else Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CoverImage(t, Modifier.widthIn(max = 420.dp).fillMaxWidth(0.84f).aspectRatio(1f), 14)
        NowControls(t, onOut) { showQueue = true }
    }
    if (showQueue) QueueDialog { showQueue = false }
}

@Composable
private fun NowControls(t: Track, onOut: () -> Unit, onQueue: () -> Unit) {
    val playing by Playback.playing.collectAsState()
    val pos by Playback.position.collectAsState()
    val dur by Playback.duration.collectAsState()
    val info by Playback.info.collectAsState()
    val shuffle by Playback.shuffle.collectAsState()
    val repeat by Playback.repeat.collectAsState()
    val remote by Playback.remote.collectAsState()
    val outName by Playback.outName.collectAsState()
    val err by Playback.error.collectAsState()
    val fav = t.path in Favorites.paths.value
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val dsd = t.ext == "dsf" || t.ext == "dff"

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T(t.title, 26, serif = true, bold = true, lines = 2)
        T(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), 17, C.Muted, lines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(formatBadge(t))
            Pill(quality(t, info))
            val i = info
            if (i != null && !dsd && i.sampleRate > 0 && i.bits > 0) Pill("${i.bits}-BIT / ${khz(i.sampleRate)}")
        }
        if (remote != null) T("Đang phát tới: $outName", 14, C.PlumSoft, lines = 2)

        Column(Modifier.fillMaxWidth()) {
            Slider(
                value = frac,
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { f -> Playback.seekTo((f * dur).toLong()) }; drag = null },
                colors = SliderDefaults.colors(thumbColor = C.Gold, activeTrackColor = C.Gold, inactiveTrackColor = C.Line),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                T(fmt(drag?.let { (it * dur).toLong() } ?: pos), 14, C.Muted)
                T(fmt(dur), 14, C.Muted)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Filled.Shuffle, "Ngẫu nhiên", 24, if (shuffle) C.Gold else C.Muted) { Playback.toggleShuffle() }
            IconBtn(Icons.Filled.SkipPrevious, "Bài trước", 34) { Playback.prev() }
            Box(Modifier.size(68.dp).clip(CircleShape).background(C.Gold).clickable { Playback.toggle() }, contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Phát / Dừng", tint = C.Bg, modifier = Modifier.size(40.dp))
            }
            IconBtn(Icons.Filled.SkipNext, "Bài sau", 34) { Playback.next() }
            IconBtn(if (repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Lặp lại", 24,
                if (repeat == Player.REPEAT_MODE_OFF) C.Muted else C.Gold) { Playback.cycleRepeat() }
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionBtn(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Yêu thích", if (fav) C.Gold else C.Muted) { Favorites.toggle(t) }
            ActionBtn(Icons.Filled.Speaker, "Đầu ra", C.Muted) { onOut() }
            ActionBtn(Icons.Filled.GraphicEq, "Phổ tần", C.Muted) { Overlay.spectrum.value = true }
            ActionBtn(Icons.Filled.QueueMusic, "Danh sách", C.Muted) { onQueue() }
        }
        if (err != null) T(err ?: "", 14, Color(0xFFE5575A), lines = 4)
    }
}

@Composable
private fun ActionBtn(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) =
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp))
        T(label, 13, C.Muted)
    }

@Composable
fun QueueDialog(onClose: () -> Unit) {
    val queue by Playback.queue.collectAsState()
    val idx by Playback.index.collectAsState()
    val st = rememberLazyListState()
    LaunchedEffect(Unit) { st.scrollToItem(maxOf(0, idx - 2)) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.dlgW(900).dlgH(900).clip(RoundedCornerShape(16.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(16.dp)).padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T("Danh sách phát (${queue.size})", 24, serif = true)
                IconBtn(Icons.Filled.Close, "Đóng") { onClose() }
            }
            LazyColumn(Modifier.weight(1f, fill = false), state = st, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(queue) { i, t -> TrackRow(i, t, i == idx, compact = true) { Playback.jump(i); onClose() } }
            }
        }
    }
}

// ---------------------------------------------------------------- Cài đặt
@Composable
private fun MobileSettings(onOut: () -> Unit) {
    var showAbout by remember { mutableStateOf(false) }
    val outName by Playback.outName.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T("Cài đặt", 34, serif = true)
            MButton("Đầu ra âm thanh · $outName", Modifier.fillMaxWidth()) { onOut() }
            ThemeCard()
            StyleCard()
            DsdCard()
            MobileEq()
            BackgroundCard()
            UpdateCard()
            CoverWebCard()
            LanguageCard()
            MButton("Về ứng dụng (About)", Modifier.fillMaxWidth()) { showAbout = true }
            MButton("Thoát ứng dụng", Modifier.fillMaxWidth(), Color(0xFFE5575A)) { Overlay.exit.value = true }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showAbout) AboutDialog { showAbout = false }
}

@Composable
private fun MobileEq() {
    val on by DspParams.on.collectAsState()
    val bands by DspParams.bands.collectAsState()
    val bal by DspParams.balance.collectAsState()
    val pre by DspParams.preamp.collectAsState()
    val labels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val names = listOf("Phẳng", "Ấm", "Giọng hát", "Trầm+")
    Card {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T("BỘ CÂN BẰNG (EQ)", 13, C.Muted, spaced = true)
            Opt("Bật", on) { DspParams.setOn(!on) }
        }
        EqWarning(on, bands, pre)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DspParams.PRESETS.forEachIndexed { i, (_, v) ->
                val sel = bands.indices.all { kotlin.math.abs(bands[it] - v[it]) < 0.01f }
                MChip(names[i], sel) { DspParams.setBands(v) }
            }
            MChip("Đặt lại", false) { DspParams.reset() }
        }
        for (i in 0 until 10) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T(labels[i] + " Hz", 14, C.Text, Modifier.width(62.dp))
            Slider(
                value = bands[i], onValueChange = { DspParams.setBand(i, (it * 2).roundToInt() / 2f) }, valueRange = -12f..12f,
                modifier = Modifier.weight(1f), enabled = on,
                colors = SliderDefaults.colors(thumbColor = C.Gold, activeTrackColor = C.Gold, inactiveTrackColor = C.Line, disabledThumbColor = C.Dim, disabledActiveTrackColor = C.Dim, disabledInactiveTrackColor = C.Line),
            )
            T("%+.1f".format(bands[i]), 13, C.Muted, Modifier.width(46.dp))
        }
        MSlider("Cân bằng trái / phải", bal, -1f..1f, { if (kotlin.math.abs(it) < 0.01f) "L = R" else if (it < 0) "L %d%%".format((-it * 100).toInt()) else "R %d%%".format((it * 100).toInt()) }) { DspParams.setBalance((it * 20).roundToInt() / 20f) }
        MSlider("Preamp (dB)", pre, -12f..6f, { "%+.1f dB".format(it) }) { DspParams.setPreamp((it * 2).roundToInt() / 2f) }
        T("Khi EQ phẳng và cân bằng ở giữa, âm thanh đi thẳng không qua xử lý (bit-perfect).", 14, C.Gold, lines = 3)
    }
}

@Composable
private fun MSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, show: (Float) -> String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T(label, 15, C.Text); T(show(value), 14, C.Gold) }
        Slider(
            value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = C.Gold, activeTrackColor = C.Gold, inactiveTrackColor = C.Line),
        )
    }
}
