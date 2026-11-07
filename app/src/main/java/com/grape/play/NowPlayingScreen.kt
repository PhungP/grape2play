package com.grape.play

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.launch

/**
 * Màn Đang phát (TV). Ở chế độ 4K (canvas rộng 1760 dp) cả màn được phóng đúng tỷ lệ so với canvas FHD 1560 dp:
 * ảnh bìa, chữ, các hộp thông số đều to ra và khoảng trống giữa hộp tín hiệu với sóng âm nhỏ lại, bố cục cân đối như bản FHD.
 */
@Composable
fun NowPlaying(onNav: (Screen) -> Unit, onAdd: () -> Unit, onOut: () -> Unit) {
    val k = (LocalCanvasW.current / 1560f).coerceIn(1f, 1.4f)
    val d = LocalDensity.current
    if (k > 1.02f) CompositionLocalProvider(LocalDensity provides Density(d.density * k, d.fontScale)) { NowPlayingBody(onNav, onAdd, onOut) }
    else NowPlayingBody(onNav, onAdd, onOut)
}

@Composable
private fun NowPlayingBody(onNav: (Screen) -> Unit, onAdd: () -> Unit, onOut: () -> Unit) {
    val queue by Playback.queue.collectAsState()
    val idx by Playback.index.collectAsState()
    val playing by Playback.playing.collectAsState()
    val pos by Playback.position.collectAsState()
    val dur by Playback.duration.collectAsState()
    val info by Playback.info.collectAsState()
    val shuffle by Playback.shuffle.collectAsState()
    val repeat by Playback.repeat.collectAsState()
    val error by Playback.error.collectAsState()
    val lv by Playback.level.levels.collectAsState()
    val pcm by Playback.level.fmt.collectAsState()
    val outName by Playback.outName.collectAsState()
    val remote by Playback.remote.collectAsState()
    val t = queue.getOrNull(idx)
    val isFav = t != null && t.path in Favorites.paths.value
    val isDsd = t != null && (t.ext == "dsf" || t.ext == "dff")
    val playReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playReq.requestFocus() } }

    Row(Modifier.fillMaxSize().background(C.Bg)) {
        Rail(Screen.NOW, onNav, onAdd, onOut)
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 48.dp, vertical = 26.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T("ĐANG PHÁT", 14, C.Muted, spaced = true)
                Focusable(onOut, shape = RoundedCornerShape(6.dp)) { T("Đầu ra · $outName  ▾", 16, C.Gold, Modifier.padding(14.dp, 8.dp), bold = true) }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                CoverImage(t, Modifier.size(340.dp), 10)
                Column(Modifier.weight(1f)) {
                    if (t == null) {
                        T("Chưa có bài nào đang phát", 44, serif = true)
                        T("Vào Thư viện để chọn nhạc", 22, C.Muted)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill(quality(t, info))
                            val ii = info
                            if (ii != null && ii.sampleRate > 0 && ii.bits > 0) Pill(if (isDsd) "${dsdLabel(ii.sampleRate)} · 1-BIT" else "${ii.bits}-BIT / ${khz(ii.sampleRate)}")
                        }
                        Spacer(Modifier.height(10.dp))
                        T(t.title, 44, serif = true, lines = 2)
                        T(t.artist, 24, C.Gold)
                        T("Album: ${t.album}", 18, C.Muted, lines = 1)
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                        Spacer(Modifier.height(12.dp))
                        val i = info
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Spec("Định dạng", if (isDsd) dsdLabel(i?.sampleRate ?: t.sr) else t.ext.uppercase(), Modifier.weight(1f))
                            Spec("Độ sâu bit", ((i?.bits ?: 0).takeIf { it > 0 } ?: t.bits).let { if (it > 0) "$it-bit" else "—" }, Modifier.weight(1f))
                            Spec("Tần số lấy mẫu", if (isDsd) mhz(i?.sampleRate ?: t.sr) else khz(i?.sampleRate ?: 0), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Spec("Bitrate", if (i != null && i.bitrateKbps > 0) "%,d kbps".format(i.bitrateKbps) else "—", Modifier.weight(1f))
                            Spec("Kênh", channelName(i?.channels ?: 0), Modifier.weight(1f))
                            Spec("Nguồn", t.sourceName.substringBefore(" · "), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, C.Line, RoundedCornerShape(8.dp)).padding(12.dp, 9.dp)) {
                            val src = if (isDsd) "${dsdLabel(i?.sampleRate ?: t.sr)} ${mhz(i?.sampleRate ?: t.sr)}"
                                else if (i != null && i.sampleRate > 0) "${t.ext.uppercase()} ${i.bits.takeIf { it > 0 } ?: ""}/${khz(i.sampleRate)}" else t.ext.uppercase()
                            val dspOn = !DspParams.isFlat() && remote == null   // EQ chỉ áp dụng khi phát trên TV, không áp dụng khi gửi file tới thiết bị khác
                            val (grade, gl) = signalGrade(t, i, pcm, remote)
                            Column {
                                T("ĐƯỜNG TÍN HIỆU", 11, C.Muted)
                                T((if (grade == Grade.GREEN) "✓ " else "") + gl, 17, grade.color, bold = true)
                                T(if (remote != null) (if (dopActive(t, i, remote)) "Tín hiệu: $src → DoP 24-bit/176,4 kHz → $outName (DAC phải hỗ trợ DoP)" else "Tín hiệu: $src → $outName (file gốc, thiết bị tự giải mã)") else "Tín hiệu: $src → ${pcm.ifEmpty { "PCM" }}${if (dspOn) " → EQ" else ""} → $outName", 16, grade.color, lines = 2)
                                if (dspOn) T("⚠ EQ đang chỉnh — âm thanh có thể bị méo nếu tăng quá cao", 15, Grade.YELLOW.color, lines = 2)
                            }
                        }
                    }
                    error?.let { T(it, 15, C.Gold, Modifier.padding(top = 8.dp), lines = 4) }
                }
            }
            Spacer(Modifier.weight(1f))

            var wf by remember { mutableStateOf(false) }
            Box(
                Modifier.fillMaxWidth().height(72.dp)
                    .onFocusChanged { wf = it.isFocused }
                    .then(if (wf) Modifier.border(2.dp, C.Gold, RoundedCornerShape(6.dp)) else Modifier)
                    .onKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { Playback.seekBy(-10_000); true }
                        else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { Playback.seekBy(10_000); true }
                        else false
                    }
                    .focusable()
                    .padding(6.dp),
            ) { Waveform(t?.path.hashCode(), if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, Modifier.fillMaxSize()) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T(fmt(pos), 16, C.Muted)
                T((if (shuffle) "Ngẫu nhiên" else "Tuần tự") + " · " + when (repeat) { Player.REPEAT_MODE_ONE -> "Lặp một bài"; Player.REPEAT_MODE_ALL -> "Lặp toàn bộ danh sách"; else -> "Không lặp" }, 16, C.Gold)
                T(fmt(dur), 16, C.Muted)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                Vu("L", if (playing) lv.first else 0f, Modifier.weight(1f)); Vu("R", if (playing) lv.second else 0f, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().height(132.dp), horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Filled.Shuffle, "Ngẫu nhiên", shuffle) { Playback.toggleShuffle() }
                IconBtn(Icons.Filled.SkipPrevious, "Bài trước", false) { Playback.prev() }
                Focusable({ Playback.toggle() }, Modifier.size(124.dp), CircleShape, ring = C.Text, requester = playReq) {
                    Box(Modifier.align(Alignment.Center).size(110.dp).clip(CircleShape).background(C.Gold), contentAlignment = Alignment.Center) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Tạm dừng" else "Phát", tint = C.Bg, modifier = Modifier.size(64.dp))
                    }
                }
                IconBtn(Icons.Filled.SkipNext, "Bài sau", false) { Playback.next() }
                IconBtn(if (repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Lặp lại", repeat != Player.REPEAT_MODE_OFF) { Playback.cycleRepeat() }
                IconBtn(if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Yêu thích", isFav) { t?.let { Favorites.toggle(it) } }
            }
        }
        QueuePanel(queue, idx)
    }
}

@Composable
private fun Spec(label: String, value: String, modifier: Modifier, size: Int = 22) = Column(modifier.height(68.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, C.Line, RoundedCornerShape(8.dp)).padding(12.dp, 9.dp)) {
    T(label.uppercase(), 11, C.Muted)
    T(value, size)
}

@Composable
private fun IconBtn(icon: ImageVector, desc: String, active: Boolean, onClick: () -> Unit) =
    Focusable(onClick, Modifier.size(96.dp), CircleShape) {
        Icon(icon, desc, tint = if (active) C.Gold else C.Text, modifier = Modifier.align(Alignment.Center).size(52.dp))
    }

@Composable
private fun QueuePanel(queue: List<Track>, idx: Int) {
    val st = rememberLazyListState()
    LaunchedEffect(idx, queue.size) { if (queue.isNotEmpty()) st.animateScrollToItem(idx.coerceIn(0, queue.lastIndex)) }
    val total = remember(queue) { queue.sumOf { it.durationMs } }
    Column(Modifier.width(460.dp).fillMaxHeight().background(C.Side).padding(horizontal = 24.dp, vertical = 26.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            T("Danh sách phát", 30, serif = true)
            T("${queue.size} bài · ${fmt(total)}", 13, C.Muted)
        }
        Spacer(Modifier.height(10.dp))
        if (queue.isEmpty()) T("Danh sách đang trống", 18, C.Muted)
        LazyColumn(state = st, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(queue) { i, t -> TrackRow(i, t, i == idx, compact = true) { Playback.jump(i) } }
        }
    }
}

fun dsdLabel(rate: Int) = if (rate >= 44100) "DSD" + (rate / 44100) else "DSD"
fun mhz(rate: Int) = if (rate <= 0) "—" else "%.4f MHz".format(rate / 1_000_000.0)
