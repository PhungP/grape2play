package com.grape.play

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay

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

private const val COVER = 420

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
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 40.dp, vertical = 22.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T("ĐANG PHÁT", 14, C.Muted, spaced = true)
                Focusable(onOut, shape = RoundedCornerShape(6.dp)) { T("Đầu ra · $outName  ▾", 16, C.Gold, Modifier.padding(14.dp, 6.dp), bold = true) }
            }
            Spacer(Modifier.height(8.dp))
            // Ảnh bìa lớn; cột thông tin cao đúng bằng ảnh bìa, hộp "Đường tín hiệu" giãn ra để đáy hộp thẳng hàng với đáy ảnh
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                CoverImage(t, Modifier.size(COVER.dp), 10)
                Column(Modifier.weight(1f).height(COVER.dp)) {
                    if (t == null) {
                        T("Chưa có bài nào đang phát", 38, serif = true, lines = 2)
                        T("Vào Thư viện để chọn nhạc", 20, C.Muted)
                    } else {
                        val i = info
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill(quality(t, i))
                            if (i != null && i.sampleRate > 0 && i.bits > 0) Pill(if (isDsd) "${dsdLabel(i.sampleRate)} · 1-BIT" else "${i.bits}-BIT / ${khz(i.sampleRate)}")
                        }
                        Spacer(Modifier.height(8.dp))
                        T(t.title, 34, serif = true, lines = 2)
                        T(t.artist, 22, C.Gold)
                        T("Album: ${t.album}", 16, C.Muted, lines = 1)
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Spec("Định dạng", if (isDsd) dsdLabel(i?.sampleRate ?: t.sr) else t.ext.uppercase(), Modifier.weight(1f))
                            Spec("Độ sâu bit", ((i?.bits ?: 0).takeIf { it > 0 } ?: t.bits).let { if (it > 0) "$it-bit" else "—" }, Modifier.weight(1f))
                            Spec("Tần số", if (isDsd) mhz(i?.sampleRate ?: t.sr) else khz(i?.sampleRate ?: 0), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Spec("Bitrate", if (i != null && i.bitrateKbps > 0) "%,d kbps".format(i.bitrateKbps) else "—", Modifier.weight(1f))
                            Spec("Kênh", channelName(i?.channels ?: 0), Modifier.weight(1f))
                            Spec("Nguồn", t.sourceName.substringBefore(" · "), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, C.Line, RoundedCornerShape(8.dp)).padding(12.dp, 8.dp)) {
                            val src = if (isDsd) "${dsdLabel(i?.sampleRate ?: t.sr)} ${mhz(i?.sampleRate ?: t.sr)}"
                                else if (i != null && i.sampleRate > 0) "${t.ext.uppercase()} ${i.bits.takeIf { it > 0 } ?: ""}/${khz(i.sampleRate)}" else t.ext.uppercase()
                            val dspOn = !DspParams.isFlat() && remote == null   // EQ chỉ áp dụng khi phát trên TV, không áp dụng khi gửi file tới thiết bị khác
                            val (grade, gl) = signalGrade(t, i, pcm, remote)
                            Column {
                                T("ĐƯỜNG TÍN HIỆU", 11, C.Muted)
                                T((if (grade == Grade.GREEN) "✓ " else "") + gl, 16, grade.color, bold = true, lines = 2)
                                val err = error
                                if (err != null) T(err.lines().first().take(160), 14, C.Gold, lines = 3)   // lỗi hiện ngay trong hộp này, không chiếm thêm chỗ
                                else {
                                    T(if (remote != null) (if (dopActive(t, i, remote)) "Tín hiệu: $src → DoP 24-bit/176,4 kHz → $outName" else "Tín hiệu: $src → $outName (file gốc, thiết bị tự giải mã)") else "Tín hiệu: $src → ${pcm.ifEmpty { "PCM" }}${if (dspOn) " → EQ" else ""} → $outName", 14, grade.color, lines = 3)
                                    if (dspOn) T("⚠ EQ đang chỉnh — có thể méo nếu tăng cao", 13, Grade.YELLOW.color, lines = 1)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // Hàng hộp phía dưới: phát trên TV → hai đồng hồ VU kim; phát tới thiết bị UPnP/Cast (không đo được mức) → thông tin thiết bị nhận + kết nối
            Row(Modifier.fillMaxWidth().height(118.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val r = remote
                if (r == null) {
                    VuDial("L", if (playing) lv.first else 0f, Modifier.weight(1f).fillMaxHeight())
                    VuDial("R", if (playing) lv.second else 0f, Modifier.weight(1f).fillMaxHeight())
                } else {
                    ReceiverBox(r, t, info, Modifier.weight(1f).fillMaxHeight())
                    LinkBox(r, t, playing, Modifier.weight(1.15f).fillMaxHeight())
                }
            }
            Spacer(Modifier.weight(1f))

            var wf by remember { mutableStateOf(false) }
            Box(
                Modifier.fillMaxWidth().height(46.dp)
                    .onFocusChanged { wf = it.isFocused }
                    .then(if (wf) Modifier.border(2.dp, C.Gold, RoundedCornerShape(6.dp)) else Modifier)
                    .onKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { Playback.seekBy(-10_000); true }
                        else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { Playback.seekBy(10_000); true }
                        else false
                    }
                    .focusable()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) { Waveform(t?.path.hashCode(), if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, Modifier.fillMaxSize()) }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T(fmt(pos), 15, C.Muted)
                T((if (shuffle) "Ngẫu nhiên" else "Tuần tự") + " · " + when (repeat) { Player.REPEAT_MODE_ONE -> "Lặp một bài"; Player.REPEAT_MODE_ALL -> "Lặp toàn bộ danh sách"; else -> "Không lặp" }, 15, C.Gold)
                T(fmt(dur), 15, C.Muted)
            }
            Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Filled.Shuffle, "Ngẫu nhiên", shuffle) { Playback.toggleShuffle() }
                IconBtn(Icons.Filled.SkipPrevious, "Bài trước", false) { Playback.prev() }
                Focusable({ Playback.toggle() }, Modifier.size(112.dp), CircleShape, ring = C.Text, requester = playReq) {
                    Box(Modifier.align(Alignment.Center).size(100.dp).clip(CircleShape).background(C.Gold), contentAlignment = Alignment.Center) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Tạm dừng" else "Phát", tint = C.Bg, modifier = Modifier.size(58.dp))
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
private fun Box1(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, C.Line, RoundedCornerShape(10.dp)).background(C.Panel).padding(12.dp, 8.dp)) {
        T(title, 11, C.Muted, spaced = true)
        content()
    }

/** Đồng hồ VU kim: thang -40…+3 dB, vùng đỏ từ 0 dB trở lên; kim chạy mượt theo mức đỉnh thật của luồng PCM đang phát trên TV. */
@Composable
private fun VuDial(label: String, level: Float, modifier: Modifier) {
    val db = if (level <= 0.0005f) -60f else (20f * kotlin.math.log10(level)).coerceAtMost(6f)
    val frac by animateFloatAsState(((db + 40f) / 43f).coerceIn(0f, 1f), tween(140), label = "vu")
    Box(modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, C.Line, RoundedCornerShape(10.dp)).background(C.Panel)) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
            val cx = size.width / 2f
            val cy = size.height * 1.02f
            val r = minOf(size.width * 0.42f, size.height * 0.92f)
            val sw = 3.dp.toPx()
            val half = 55f   // nửa góc quét (độ) so với phương thẳng đứng
            fun ang(f: Float) = Math.toRadians((-half + 2 * half * f).toDouble())
            // cung thang đo + vùng đỏ
            drawArc(C.Dim, -90f - half, 2 * half, false, Offset(cx - r, cy - r), Size(2 * r, 2 * r), style = Stroke(sw))
            val redF = 40f / 43f
            drawArc(Color(0xFFE5575A), -90f - half + 2 * half * redF, 2 * half * (1f - redF), false, Offset(cx - r, cy - r), Size(2 * r, 2 * r), style = Stroke(sw))
            // vạch chia: -40, -30, -20, -10, -5, 0, +3 dB
            for (d in listOf(-40f, -30f, -20f, -10f, -5f, 0f, 3f)) {
                val a = ang((d + 40f) / 43f); val sn = Math.sin(a).toFloat(); val cs = Math.cos(a).toFloat()
                drawLine(if (d >= 0f) Color(0xFFE5575A) else C.Muted, Offset(cx + sn * r * 0.86f, cy - cs * r * 0.86f), Offset(cx + sn * r * 0.98f, cy - cs * r * 0.98f), 2.dp.toPx())
            }
            val a = ang(frac); val sn = Math.sin(a).toFloat(); val cs = Math.cos(a).toFloat()
            drawLine(C.Gold, Offset(cx, cy), Offset(cx + sn * r * 0.94f, cy - cs * r * 0.94f), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(C.Gold, 6.dp.toPx(), Offset(cx, cy))
        }
        T(label, 16, C.Gold, Modifier.align(Alignment.TopStart).padding(12.dp, 6.dp), bold = true)
        T(if (db <= -60f) "-∞ dB" else "%.1f dB".format(db), 13, C.Muted, Modifier.align(Alignment.BottomEnd).padding(12.dp, 6.dp))
        T("VU", 11, C.Muted, Modifier.align(Alignment.TopEnd).padding(12.dp, 8.dp), spaced = true)
    }
}

/** Hộp "Thiết bị nhận": thông tin loa/streamer đang nhận nhạc (cùng nội dung với trang Đầu ra, không lặp lại đường tín hiệu). */
@Composable
private fun ReceiverBox(r: Renderer, t: Track?, i: AudioInfo?, modifier: Modifier) = Box1("THIẾT BỊ NHẬN", modifier) {
    T(r.name, 18, C.Text, bold = true)
    val dsd = t != null && StreamServer.isDsdFile(t)
    T((if (r.kind == "cast") "Google Cast" else "UPnP / OpenHome") + (if (dsd) " · " + (if (dopActive(t!!, i, r)) "DSD: DoP" else "DSD: file gốc") else ""), 13, C.Gold, lines = 1)
    T("IP ${r.host}" + (if (r.port > 0) ":${r.port}" else ""), 13, C.Muted, lines = 1)
    T(if (r.kind == "cast") "Thiết bị tự giải mã luồng nhận được" else "Thiết bị tự kéo nhạc về và giải mã", 13, C.Muted, lines = 2)
}

/** Hộp "Kết nối & phát liên tục": độ trễ phản hồi của loa, trạng thái, bài kế đã nạp sẵn, lần chuyển bài gần nhất, số lần mất kết nối. */
@Composable
private fun LinkBox(r: Renderer, t: Track?, playing: Boolean, modifier: Modifier) {
    val h by RemoteStats.health.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    Box1("KẾT NỐI & PHÁT LIÊN TỤC", modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                T("Phản hồi: " + (if (h.latencyMs > 0) "${h.latencyMs} ms" else "—"), 13, if (h.latencyMs > 600) C.Gold else C.Text, lines = 1)
                T("Trạng thái: " + stateText(h.state, playing), 13, if (h.state == "MẤT KẾT NỐI") C.Gold else C.Text, lines = 1)
                T("Bài kế: " + (if (r.kind != "upnp") "do thiết bị" else if (!h.nextSupported) "app nạp khi hết bài" else if (h.nextArmed) "đã nạp sẵn ✓" else "chưa nạp"), 13, C.Muted, lines = 1)
                T("Chuyển bài: " + (if (h.lastSwitchAt > 0) "${((now - h.lastSwitchAt) / 1000).coerceAtLeast(0)} s trước" else "—"), 13, C.Muted, lines = 1)
            }
            Column(Modifier.weight(1f)) {
                T("Nguồn: " + (if (t != null && t.path.startsWith("http")) "trực tiếp" else "kéo qua TV"), 13, C.Muted, lines = 1)
                T("Mất kết nối: ${h.drops} lần", 13, if (h.drops > 0) C.Gold else C.Muted, lines = 1)
                Spark(h.series, Modifier.fillMaxWidth().height(34.dp).padding(top = 4.dp))
            }
        }
        if (h.otherController) T("⚠ Máy khác đang điều khiển thiết bị này", 12, C.Gold, lines = 1)
    }
}

private fun stateText(s: String, playing: Boolean) = when (s) {
    "PLAYING" -> "Đang phát"; "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> "Tạm dừng"; "TRANSITIONING" -> "Đang chuyển"
    "STOPPED", "NO_MEDIA_PRESENT" -> "Dừng"; "MẤT KẾT NỐI" -> "Mất kết nối"
    else -> if (playing) "Đang phát" else "Chờ"
}

/** Biểu đồ nhỏ độ trễ phản hồi 60 lần hỏi gần nhất; vạch đỏ = lần mất kết nối. */
@Composable
private fun Spark(v: List<Int>, modifier: Modifier) = Canvas(modifier) {
    if (v.size < 2) return@Canvas
    val mx = maxOf(200, v.max()).toFloat()
    val step = size.width / 59f
    var prev: Offset? = null
    v.forEachIndexed { k, ms ->
        val x = k * step
        if (ms == 0) { drawLine(Color(0xFFE5575A), Offset(x, 0f), Offset(x, size.height), 2.dp.toPx()); prev = null }
        else {
            val p = Offset(x, size.height - size.height * (ms / mx).coerceIn(0.05f, 1f))
            prev?.let { drawLine(C.Gold, it, p, 2.dp.toPx()) }
            prev = p
        }
    }
}

@Composable
private fun Spec(label: String, value: String, modifier: Modifier, size: Int = 18) = Column(modifier.height(58.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, C.Line, RoundedCornerShape(8.dp)).padding(10.dp, 7.dp)) {
    T(label.uppercase(), 10, C.Muted)
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
