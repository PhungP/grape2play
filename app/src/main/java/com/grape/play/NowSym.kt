@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.grape.play

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Ba bố cục màn "Đang phát" lấy cảm hứng từ Symfonium, dùng chung cho điện thoại, máy tính bảng và TV (bố cục tự đổi theo hình dạng màn hình):
 *  1 Cổ điển  — ảnh bìa vuông bo góc ở trên, tiêu đề căn trái kèm nút yêu thích, nút phát hình vuông bo góc.
 *  2 Toàn ảnh — ảnh bìa phủ kín màn hình, lớp phủ tối dần xuống dưới, điều khiển nằm đè lên ảnh.
 *  3 Nền màu  — nền chuyển sắc theo màu chủ đạo của ảnh bìa, tiêu đề căn giữa.
 * Chọn trong Cài đặt ▸ Bố cục màn Đang phát. Trên TV: OK = phát/dừng (nút Phát được focus sẵn), Menu = yêu thích, Back = về Thư viện.
 */

@Composable
fun SymNow(style: Int, onNav: (Screen) -> Unit, onOut: () -> Unit) {
    val tv = !LocalCompact.current
    var showQueue by remember { mutableStateOf(false) }
    if (tv) {
        BackHandler { onNav(Screen.LIB) }
        Row(Modifier.fillMaxSize().background(C.Bg)) {
            Rail(Screen.NOW, onNav)
            Box(Modifier.weight(1f).fillMaxHeight()) { SymBody(style, true, onOut) { showQueue = true } }
        }
    } else SymBody(style, false, onOut) { showQueue = true }
    if (showQueue) QueueDialog { showQueue = false }
}

/** Màu chủ đạo của ảnh bìa (trung bình, làm tối nhẹ để chữ sáng dễ đọc). */
@Composable
private fun rememberCoverTint(t: Track?): Color {
    val fallback = C.Plum
    val c by produceState(fallback, t?.folder, t?.album) {
        value = fallback
        val tr = t ?: return@produceState
        val bmp = Covers.get(tr) ?: return@produceState
        value = withContext(Dispatchers.Default) { averageColor(bmp) }
    }
    return c
}

private fun averageColor(b: Bitmap): Color {
    val n = 12
    val s = Bitmap.createScaledBitmap(b, n, n, true)
    var r = 0L; var g = 0L; var bl = 0L
    for (y in 0 until n) for (x in 0 until n) { val p = s.getPixel(x, y); r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; bl += p and 0xff }
    val cnt = n * n
    val k = 0.6f
    return Color((r / cnt * k).toInt().coerceIn(0, 255), (g / cnt * k).toInt().coerceIn(0, 255), (bl / cnt * k).toInt().coerceIn(0, 255))
}

@Composable
private fun SymBody(style: Int, tv: Boolean, onOut: () -> Unit, onQueue: () -> Unit) {
    val queue by Playback.queue.collectAsState()
    val idx by Playback.index.collectAsState()
    val playing by Playback.playing.collectAsState()
    val pos by Playback.position.collectAsState()
    val dur by Playback.duration.collectAsState()
    val info by Playback.info.collectAsState()
    val shuffle by Playback.shuffle.collectAsState()
    val repeat by Playback.repeat.collectAsState()
    val remote by Playback.remote.collectAsState()
    val outName by Playback.outName.collectAsState()
    val err by Playback.error.collectAsState()
    val pcm by Playback.level.fmt.collectAsState()
    val t = queue.getOrNull(idx)
    val tint = rememberCoverTint(if (style == 3) t else null)
    val playReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (tv) runCatching { playReq.requestFocus() } }

    // Màu chữ / thanh theo từng bố cục
    val fg = if (style == 2) Color.White else C.Text
    val mute = if (style == 2) Color(0xCCFFFFFF) else C.Muted
    val track = if (style == 1) C.Line else Color(0x44FFFFFF)
    val accent = if (style == 2) Color.White else C.Gold

    Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        if (e.key == Key.Menu && e.type == KeyEventType.KeyDown) { t?.let { Favorites.toggle(it) }; true } else false
    }) {
        when (style) {
            2 -> {
                CoverImage(t, Modifier.fillMaxSize(), 0)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x40000000), 0.5f to Color(0x99000000), 1f to Color(0xF5000000))))
            }
            3 -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, C.Bg))))
            else -> Box(Modifier.fillMaxSize().background(C.Bg))
        }
        if (t == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Chưa có bài nào đang phát", if (tv) 40 else 26, fg, serif = true)
                T("Vào Thư viện để chọn nhạc", if (tv) 20 else 16, mute)
            }
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight * 1.15f
            val center = style == 3
            val fav = t.path in Favorites.paths.value
            val dsd = t.ext == "dsf" || t.ext == "dff"
            val titleSz = if (tv) 44 else if (wide) 28 else 26
            val artistSz = if (tv) 22 else 17

            @Composable
            fun Ctl(icon: ImageVector, desc: String, size: Int, tint2: Color, onClick: () -> Unit) =
                Focusable(onClick, Modifier.size(if (tv) 66.dp else 48.dp), CircleShape) { f ->
                    Icon(icon, desc, tint = if (f) C.Gold else tint2, modifier = Modifier.align(Alignment.Center).size(size.dp))
                }

            @Composable
            fun TitleRow() = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (center) Spacer(Modifier.size(if (tv) 66.dp else 48.dp))
                Column(Modifier.weight(1f), horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    T(t.title, titleSz, fg, serif = true, bold = true, lines = 2)
                    T(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), artistSz, mute, lines = 2)
                }
                Ctl(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Yêu thích", 28, if (fav) accent else mute) { Favorites.toggle(t) }
            }

            @Composable
            fun Badges() = Column(Modifier.fillMaxWidth(), horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, if (center) Alignment.CenterHorizontally else Alignment.Start), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(formatBadge(t))
                    Pill(quality(t, info))
                    val i = info
                    if (i != null && i.sampleRate > 0 && i.bits > 0) Pill(if (dsd) "${dsdLabel(i.sampleRate)} · 1-BIT" else "${i.bits}-BIT / ${khz(i.sampleRate)}")
                }
                val (grade, gl) = signalGrade(t, info, pcm, remote)
                T((if (grade == Grade.GREEN) "✓ " else "") + gl, 14, grade.color, bold = true, lines = 2)
                if (remote != null) T("Đang phát tới: $outName", 14, C.PlumSoft, lines = 2)
            }

            @Composable
            fun Seek() {
                var drag by remember { mutableStateOf<Float?>(null) }
                val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                Column(Modifier.fillMaxWidth()) {
                    if (tv) Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(track)) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(accent))
                    } else Slider(
                        value = frac, onValueChange = { drag = it },
                        onValueChangeFinished = { drag?.let { f -> Playback.seekTo((f * dur).toLong()) }; drag = null },
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = track),
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        T(fmt(drag?.let { (it * dur).toLong() } ?: pos), if (tv) 16 else 14, mute)
                        T(fmt(dur), if (tv) 16 else 14, mute)
                    }
                }
            }

            @Composable
            fun Controls() = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Ctl(Icons.Filled.Shuffle, "Ngẫu nhiên", 26, if (shuffle) accent else mute) { Playback.toggleShuffle() }
                Ctl(Icons.Filled.SkipPrevious, "Bài trước", 38, fg) { Playback.prev() }
                val sz = if (tv) 92.dp else 72.dp
                val shape = if (style == 1) RoundedCornerShape(26.dp) else CircleShape
                val bg = if (style == 2) Color.White else C.Gold
                Focusable({ Playback.toggle() }, Modifier.size(sz).clip(shape).background(bg), shape, requester = playReq, ring = fg) { f ->
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Phát / Dừng", tint = if (f) C.Gold else if (style == 2) Color.Black else C.Bg,
                        modifier = Modifier.align(Alignment.Center).size(if (tv) 54.dp else 42.dp))
                }
                Ctl(Icons.Filled.SkipNext, "Bài sau", 38, fg) { Playback.next() }
                Ctl(if (repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Lặp lại", 26, if (repeat == Player.REPEAT_MODE_OFF) mute else accent) { Playback.cycleRepeat() }
            }

            @Composable
            fun Act(icon: ImageVector, label: String, onClick: () -> Unit) =
                Focusable(onClick, Modifier, RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, label, tint = mute, modifier = Modifier.size(26.dp))
                        T(label, if (tv) 14 else 12, mute)
                    }
                }

            @Composable
            fun Actions() = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Act(Icons.Filled.Speaker, "Đầu ra") { onOut() }
                Act(Icons.Filled.GraphicEq, "Phổ tần") { Overlay.spectrum.value = true }
                Act(Icons.Filled.QueueMusic, "Danh sách") { onQueue() }
            }

            @Composable
            fun Block(m: Modifier) = Column(m, verticalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 12.dp)) {
                TitleRow(); Badges(); Seek(); Controls(); Actions()
                err?.let { T(it, 14, Color(0xFFE5575A), lines = 4) }
            }

            @Composable
            fun Cover(m: Modifier) = CoverImage(t, m, if (style == 3) 28 else 20)

            val padH = if (tv) 48.dp else if (wide) 24.dp else 22.dp
            val padV = if (tv) 32.dp else 14.dp
            when {
                style == 2 && wide -> Column(Modifier.fillMaxSize().padding(horizontal = padH, vertical = padV), verticalArrangement = Arrangement.Bottom) {
                    Block(Modifier.fillMaxWidth(if (tv) 0.6f else 0.7f))
                }
                style == 2 -> Column(Modifier.fillMaxSize().padding(horizontal = padH, vertical = padV), verticalArrangement = Arrangement.Bottom) {
                    Block(Modifier.fillMaxWidth())
                }
                wide -> Row(Modifier.fillMaxSize().padding(horizontal = padH, vertical = padV), horizontalArrangement = Arrangement.spacedBy(if (tv) 48.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(Modifier.fillMaxHeight(0.9f).aspectRatio(1f))
                    Block(Modifier.weight(1f).then(if (tv) Modifier else Modifier.verticalScroll(rememberScrollState())))
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = padH, vertical = padV), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cover(Modifier.widthIn(max = 440.dp).fillMaxWidth().aspectRatio(1f))
                    Block(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
