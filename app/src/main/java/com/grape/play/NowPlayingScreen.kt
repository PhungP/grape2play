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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
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
 * Màn Đang phát (TV). Ở chế độ 4K (canvas rộng 1760 dp) cả màn được phóng đúng tỷ lệ so với canvas FHD 1560 dp.
 * [alu] = giao diện "Nhôm phay xước": cùng bố cục nhưng tấm nhôm sáng có vân xước, ô đen, đồng hồ VU nền vàng, kim đen.
 *
 * Bố cục: mọi thứ chia đôi theo đường giữa của cột chính — ảnh bìa | hộp thông số; hai đồng hồ VU (hoặc Thiết bị nhận | Kết nối);
 * nút Phát/Dừng nằm đúng trên đường giữa đó, hai bên mỗi bên hai nút.
 */
@Composable
fun NowPlaying(onNav: (Screen) -> Unit, onAdd: () -> Unit, onOut: () -> Unit, alu: Boolean = false) {
    val k = (LocalCanvasW.current / 1560f).coerceIn(1f, 1.4f)
    val d = LocalDensity.current
    if (k > 1.02f) CompositionLocalProvider(LocalDensity provides Density(d.density * k, d.fontScale)) { NowPlayingBody(onNav, onAdd, onOut, alu) }
    else NowPlayingBody(onNav, onAdd, onOut, alu)
}

private const val COVER = 420
private val AluYellow = Color(0xFFFFD91F)
private val AluBlack = Color(0xFF0A0A0B)

/** Bảng màu theo giao diện: mặc định đọc từ theme đang chọn, "Nhôm" dùng màu cố định (đen / vàng / nhôm). */
private class Skin(val alu: Boolean) {
    val panel: Color get() = if (alu) AluBlack else C.Panel
    val line: Color get() = if (alu) Color(0x2EFFFFFF) else C.Line
    val acc: Color get() = if (alu) AluYellow else C.Gold
    val text: Color get() = if (alu) Color.White else C.Text
    val muted: Color get() = if (alu) Color(0xA6FFFFFF) else C.Muted
    /** Chữ/biểu tượng nằm trực tiếp trên nền (nhôm sáng → mực đen). */
    val ink: Color get() = if (alu) Color(0xFF111111) else C.Text
    val inkMuted: Color get() = if (alu) Color(0x99111111) else C.Muted
    val inkAcc: Color get() = if (alu) Color(0xFF111111) else C.Gold
}

/** Tấm nhôm phay xước: dải sáng-tối theo chiều dọc + vân ngang mảnh. */
private fun Modifier.brushedAluminum(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFF4F6F7), Color(0xFFDFE2E5), Color(0xFFC3C7CB), Color(0xFFE9EBEC), Color(0xFFB9BEC3), Color(0xFFD8DBDE), Color(0xFFAEB3B8))))
    var y = 0f
    val step = 3.dp.toPx()
    var n = 0
    while (y < size.height) {
        drawLine(Color(0x55FFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
        if (n % 2 == 0) drawLine(Color(0x0F000000), Offset(0f, y + step / 2), Offset(size.width, y + step / 2), 1f)
        y += step; n++
    }
}

@Composable
private fun Screw(modifier: Modifier) = Canvas(modifier.size(11.dp)) {
    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFAEB3B8), Color(0xFF565A5F), Color(0xFF222222)), center = Offset(size.width * .35f, size.height * .3f), radius = size.width * .9f))
    drawLine(Color(0xFF202326), Offset(size.width * .2f, size.height * .62f), Offset(size.width * .8f, size.height * .38f), 1.5.dp.toPx(), StrokeCap.Round)
}

@Composable
private fun NowPlayingBody(onNav: (Screen) -> Unit, onAdd: () -> Unit, onOut: () -> Unit, alu: Boolean) {
    val sk = remember(alu) { Skin(alu) }
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
        Box(Modifier.weight(1f).fillMaxHeight().then(if (alu) Modifier.brushedAluminum() else Modifier)) {
          Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = if (alu) 18.dp else 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T("ĐANG PHÁT", 14, sk.inkMuted, spaced = true)
                Focusable(onOut, shape = RoundedCornerShape(6.dp)) { T("Đầu ra · $outName  ▾", 16, sk.inkAcc, Modifier.padding(14.dp, 6.dp), bold = true) }
            }
            Spacer(Modifier.height(8.dp))
            // Ảnh bìa và hộp thông số chia đôi bằng nhau, đường giữa trùng với đường giữa của hàng hộp bên dưới và với nút Phát/Dừng
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CoverImage(t, Modifier.weight(1f).height(COVER.dp), if (alu) 2 else 10)
                Column(Modifier.weight(1f).height(COVER.dp).then(if (alu) Modifier.background(AluBlack).padding(12.dp, 10.dp) else Modifier)) {
                    if (t == null) {
                        T("Chưa có bài nào đang phát", 38, sk.text, serif = true, lines = 2)
                        T("Vào Thư viện để chọn nhạc", 20, sk.muted)
                    } else {
                        val i = info
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill(quality(t, i))
                                if (i != null && i.sampleRate > 0 && i.bits > 0) Pill(if (isDsd) "${dsdLabel(i.sampleRate)} · 1-BIT" else "${i.bits}-BIT / ${khz(i.sampleRate)}")
                            }
                            Focusable({ Favorites.toggle(t) }, Modifier.size(38.dp), CircleShape) {
                                Icon(if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Yêu thích", tint = if (isFav) sk.acc else sk.text, modifier = Modifier.align(Alignment.Center).size(24.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        T(t.title, 34, sk.text, serif = true, lines = 2)
                        T(t.artist, 22, sk.acc)
                        T("Album: ${t.album}", 16, sk.muted, lines = 1)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(sk.line))
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Spec(sk, "Định dạng", if (isDsd) dsdLabel(i?.sampleRate ?: t.sr) else t.ext.uppercase(), Modifier.weight(1f))
                            Spec(sk, "Độ sâu bit", ((i?.bits ?: 0).takeIf { it > 0 } ?: t.bits).let { if (it > 0) "$it-bit" else "—" }, Modifier.weight(1f))
                            Spec(sk, "Tần số", if (isDsd) mhz(i?.sampleRate ?: t.sr) else khz(i?.sampleRate ?: 0), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Spec(sk, "Bitrate", if (i != null && i.bitrateKbps > 0) "%,d kbps".format(i.bitrateKbps) else "—", Modifier.weight(1f))
                            Spec(sk, "Kênh", channelName(i?.channels ?: 0), Modifier.weight(1f))
                            Spec(sk, "Nguồn", t.sourceName.substringBefore(" · "), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, sk.line, RoundedCornerShape(8.dp)).padding(12.dp, 6.dp)) {
                            val src = if (isDsd) "${dsdLabel(i?.sampleRate ?: t.sr)} ${mhz(i?.sampleRate ?: t.sr)}"
                                else if (i != null && i.sampleRate > 0) "${t.ext.uppercase()} ${i.bits.takeIf { it > 0 } ?: ""}/${khz(i.sampleRate)}" else t.ext.uppercase()
                            val dspOn = !DspParams.isFlat() && remote == null   // EQ chỉ áp dụng khi phát trên TV, không áp dụng khi gửi file tới thiết bị khác
                            val (grade, gl) = signalGrade(t, i, pcm, remote)
                            Column {
                                T("ĐƯỜNG TÍN HIỆU", 11, sk.muted)
                                T((if (grade == Grade.GREEN) "✓ " else "") + gl, 16, grade.color, bold = true, lines = 2)
                                val err = error
                                if (err != null) T(err.lines().first().take(160), 14, sk.acc, lines = 3)   // lỗi hiện ngay trong hộp này, không chiếm thêm chỗ
                                else {
                                    T(if (remote != null) (if (dopActive(t, i, remote)) "Tín hiệu: $src → DoP 24-bit/176,4 kHz → $outName" else "Tín hiệu: $src → $outName (file gốc, thiết bị tự giải mã)") else "Tín hiệu: $src → ${pcm.ifEmpty { "PCM" }}${if (dspOn) " → EQ" else ""} → $outName", 14, grade.color, lines = 3)
                                    if (dspOn) T("⚠ EQ đang chỉnh — có thể méo nếu tăng cao", 13, Grade.YELLOW.color, lines = 1)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Hàng hộp phía dưới (hai hộp bằng nhau, đường giữa thẳng với đường giữa ảnh bìa/thông số):
            // phát trên TV → hai đồng hồ VU kim; phát tới UPnP/Cast (không đo được mức) → thiết bị nhận + kết nối
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val r = remote
                if (r == null) {
                    VuDial(sk, "L", if (playing) lv.first else 0f, Modifier.weight(1f).fillMaxHeight())
                    VuDial(sk, "R", if (playing) lv.second else 0f, Modifier.weight(1f).fillMaxHeight())
                } else {
                    ReceiverBox(sk, r, t, info, Modifier.weight(1f).fillMaxHeight())
                    LinkBox(sk, r, t, playing, Modifier.weight(1f).fillMaxHeight())
                }
            }
            Spacer(Modifier.weight(1f))

            var wf by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().then(if (alu) Modifier.background(AluBlack).padding(8.dp, 6.dp) else Modifier)) {
                Box(
                    Modifier.fillMaxWidth().height(46.dp)
                        .onFocusChanged { wf = it.isFocused }
                        .then(if (wf) Modifier.border(2.dp, sk.acc, RoundedCornerShape(6.dp)) else Modifier)
                        .onKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { Playback.seekBy(-10_000); true }
                            else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { Playback.seekBy(10_000); true }
                            else false
                        }
                        .focusable()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) { Waveform(t?.path.hashCode(), if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, Modifier.fillMaxSize(), if (alu) AluYellow else null) }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    val tm = if (alu) sk.muted else sk.muted
                    T(fmt(pos), 15, tm)
                    T((if (shuffle) "Ngẫu nhiên" else "Tuần tự") + " · " + when (repeat) { Player.REPEAT_MODE_ONE -> "Lặp một bài"; Player.REPEAT_MODE_ALL -> "Lặp toàn bộ danh sách"; else -> "Không lặp" }, 15, sk.acc)
                    T(fmt(dur), 15, tm)
                }
            }
            // Nút điều khiển: Phát/Dừng đúng giữa hàng (= đường giữa các hộp phía trên), mỗi bên hai nút cách đều
            Row(Modifier.fillMaxWidth().height(108.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(sk, Icons.Filled.Shuffle, "Ngẫu nhiên", shuffle) { Playback.toggleShuffle() }
                    IconBtn(sk, Icons.Filled.SkipPrevious, "Bài trước", false) { Playback.prev() }
                }
                Spacer(Modifier.width(36.dp))
                Focusable({ Playback.toggle() }, Modifier.size(112.dp), CircleShape, ring = if (alu) AluBlack else C.Text, requester = playReq) {
                    Box(
                        Modifier.align(Alignment.Center).size(100.dp).clip(CircleShape).background(if (alu) AluBlack else C.Gold)
                            .then(if (alu) Modifier.border(3.dp, AluYellow, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Tạm dừng" else "Phát", tint = if (alu) AluYellow else C.Bg, modifier = Modifier.size(58.dp))
                    }
                }
                Spacer(Modifier.width(36.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.Start), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(sk, Icons.Filled.SkipNext, "Bài sau", false) { Playback.next() }
                    IconBtn(sk, if (repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Lặp lại", repeat != Player.REPEAT_MODE_OFF) { Playback.cycleRepeat() }
                }
            }
          }
          if (alu) {   // bốn con ốc ở góc tấm nhôm
              Screw(Modifier.align(Alignment.TopStart).padding(12.dp, 6.dp)); Screw(Modifier.align(Alignment.TopEnd).padding(12.dp, 6.dp))
              Screw(Modifier.align(Alignment.BottomStart).padding(12.dp, 6.dp)); Screw(Modifier.align(Alignment.BottomEnd).padding(12.dp, 6.dp))
          }
        }
        QueuePanel(queue, idx, alu)
    }
}

@Composable
private fun Box1(sk: Skin, title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.clip(RoundedCornerShape(if (sk.alu) 2.dp else 10.dp)).border(1.dp, sk.line, RoundedCornerShape(if (sk.alu) 2.dp else 10.dp)).background(sk.panel).padding(12.dp, 8.dp)) {
        T(title, 11, sk.muted, spaced = true)
        content()
    }

/**
 * Đồng hồ VU kim: thang -40…+3 dB, vùng đỏ từ 0 dB trở lên; kim chạy mượt theo mức đỉnh thật của luồng PCM đang phát trên TV.
 * Giao diện Nhôm: mặt đồng hồ nền vàng, vạch và kim đen, khung đen.
 */
@Composable
private fun VuDial(sk: Skin, label: String, level: Float, modifier: Modifier) {
    val alu = sk.alu
    val db = if (level <= 0.0005f) -60f else (20f * kotlin.math.log10(level)).coerceAtMost(6f)
    val frac by animateFloatAsState(((db + 40f) / 43f).coerceIn(0f, 1f), tween(140), label = "vu")
    val red = Color(if (alu) 0xFFC81F16 else 0xFFE5575A)
    val scale = if (alu) Color(0xFF111111) else C.Dim
    val tick = if (alu) Color(0xFF111111) else C.Muted
    val needle = if (alu) Color(0xFF111111) else C.Gold
    Box(modifier.clip(RoundedCornerShape(if (alu) 2.dp else 10.dp)).border(1.dp, sk.line, RoundedCornerShape(if (alu) 2.dp else 10.dp)).background(sk.panel).then(if (alu) Modifier.padding(6.dp) else Modifier)) {
        Box(Modifier.fillMaxSize().then(if (alu) Modifier.background(Brush.verticalGradient(listOf(Color(0xFFFFE84D), Color(0xFFFFD81E), Color(0xFFF5BE0C)))) else Modifier)) {
            Canvas(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
                val cx = size.width / 2f
                val cy = size.height * 1.02f
                val r = minOf(size.width * 0.42f, size.height * 0.92f)
                val sw = 3.dp.toPx()
                val half = 55f   // nửa góc quét (độ) so với phương thẳng đứng
                fun ang(f: Float) = Math.toRadians((-half + 2 * half * f).toDouble())
                drawArc(scale, -90f - half, 2 * half, false, Offset(cx - r, cy - r), Size(2 * r, 2 * r), style = Stroke(sw))
                val redF = 40f / 43f
                drawArc(red, -90f - half + 2 * half * redF, 2 * half * (1f - redF), false, Offset(cx - r, cy - r), Size(2 * r, 2 * r), style = Stroke(sw))
                for (d in listOf(-40f, -30f, -20f, -10f, -5f, 0f, 3f)) {
                    val a = ang((d + 40f) / 43f); val sn = Math.sin(a).toFloat(); val cs = Math.cos(a).toFloat()
                    drawLine(if (d > 0f) red else tick, Offset(cx + sn * r * 0.86f, cy - cs * r * 0.86f), Offset(cx + sn * r * 0.98f, cy - cs * r * 0.98f), 2.dp.toPx())
                }
                val a = ang(frac); val sn = Math.sin(a).toFloat(); val cs = Math.cos(a).toFloat()
                drawLine(needle, Offset(cx, cy), Offset(cx + sn * r * 0.94f, cy - cs * r * 0.94f), 3.dp.toPx(), StrokeCap.Round)
                drawCircle(needle, 6.dp.toPx(), Offset(cx, cy))
            }
            T(label, 16, if (alu) Color(0xFF111111) else C.Gold, Modifier.align(Alignment.TopStart).padding(12.dp, 6.dp), bold = true)
            T(if (db <= -60f) "-∞ dB" else "%.1f dB".format(db), 13, if (alu) Color(0xCC111111) else C.Muted, Modifier.align(Alignment.BottomEnd).padding(12.dp, 6.dp))
            T("VU", 11, if (alu) Color(0xFF111111) else C.Muted, Modifier.align(Alignment.TopEnd).padding(12.dp, 8.dp), spaced = true)
        }
    }
}

/** Thanh ngang nhỏ: [frac] 0..1. */
@Composable
private fun MiniBar(sk: Skin, frac: Float, modifier: Modifier = Modifier) = Canvas(modifier.fillMaxWidth().height(9.dp)) {
    val cr = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
    drawRoundRect(sk.line, cornerRadius = cr)
    if (frac > 0f) drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF8A7340), sk.acc)), size = Size(size.width * frac.coerceIn(0f, 1f), size.height), cornerRadius = cr)
}

/** Hộp "Thiết bị nhận": tên, giao thức, IP và thanh âm lượng loa (cập nhật khi chỉnh trên loa hoặc app hãng). */
@Composable
private fun ReceiverBox(sk: Skin, r: Renderer, t: Track?, i: AudioInfo?, modifier: Modifier) {
    val h by RemoteStats.health.collectAsState()
    Box1(sk, "THIẾT BỊ NHẬN", modifier) {
        T(r.name, 18, sk.text, bold = true)
        val dsd = t != null && StreamServer.isDsdFile(t)
        T((if (r.kind == "cast") "Google Cast" else "UPnP / OpenHome") + (if (dsd) " · " + (if (dopActive(t!!, i, r)) "DSD: DoP" else "DSD: file gốc") else ""), 13, sk.acc, lines = 1)
        T("IP ${r.host}" + (if (r.port > 0) ":${r.port}" else ""), 13, sk.muted, lines = 1)
        Spacer(Modifier.weight(1f))
        if (h.volume >= 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                T("Âm lượng loa", 13, sk.muted); T("${h.volume}%", 13, sk.text, bold = true)
            }
            MiniBar(sk, h.volume / 100f, Modifier.padding(top = 3.dp, bottom = 2.dp))
        } else T(if (r.kind == "cast") "Thiết bị tự giải mã luồng nhận được" else "Thiết bị tự kéo nhạc về và giải mã", 13, sk.muted, lines = 2)
    }
}

/**
 * Hộp "Kết nối & phát liên tục". Cột trái: thanh đệm của loa (giây nhạc đã tải trước), phản hồi, mất kết nối, trạng thái, bài kế.
 * Cột phải: đồ thị tốc độ TV gửi nhạc cho loa (kbit/s), nhảy theo từng đợt loa kéo dữ liệu.
 */
@Composable
private fun LinkBox(sk: Skin, r: Renderer, t: Track?, playing: Boolean, modifier: Modifier) {
    val h by RemoteStats.health.collectAsState()
    val viaTv = t != null && !t.path.startsWith("http")
    Box1(sk, "KẾT NỐI & PHÁT LIÊN TỤC", modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                if (viaTv && h.bufferSec >= 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        T("Đệm của loa", 13, sk.muted); T("≈ ${h.bufferSec} s", 13, if (h.bufferSec < 8) sk.acc else Grade.GREEN.color, bold = true)
                    }
                    MiniBar(sk, (h.bufferSec / 60f), Modifier.padding(top = 3.dp, bottom = 3.dp))
                }
                T("Phản hồi: " + (if (h.latencyMs > 0) "${h.latencyMs} ms" else "—") + " · Mất kết nối: ${h.drops}", 13, if (h.latencyMs > 600 || h.drops > 0) sk.acc else sk.text, lines = 1)
                T("Trạng thái: " + stateText(h.state, playing), 13, if (h.state == "MẤT KẾT NỐI") sk.acc else sk.text, lines = 1)
                T("Bài kế: " + (if (r.kind != "upnp") "do thiết bị" else if (!h.nextSupported) "app nạp khi hết bài" else if (h.nextArmed) "đã nạp sẵn ✓" else "chưa nạp"), 13, sk.muted, lines = 1)
                if (h.otherController) T("⚠ Máy khác đang điều khiển loa", 12, sk.acc, lines = 1)
            }
            Column(Modifier.weight(1f)) {
                val last = h.kbps.lastOrNull()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    T("Tốc độ kéo nhạc", 13, sk.muted)
                    if (viaTv && last != null) T(if (last >= 1000) "%.1f Mbps".format(last / 1000f) else "$last kbps", 13, sk.text, bold = true)
                }
                if (viaTv) Spark(sk, h.kbps, Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp))
                else T("Loa kéo nhạc thẳng từ nguồn, không qua TV", 12, sk.muted, Modifier.padding(top = 4.dp), lines = 3)
            }
        }
    }
}

private fun stateText(s: String, playing: Boolean) = when (s) {
    "PLAYING" -> "Đang phát"; "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> "Tạm dừng"; "TRANSITIONING" -> "Đang chuyển"
    "STOPPED", "NO_MEDIA_PRESENT" -> "Dừng"; "MẤT KẾT NỐI" -> "Mất kết nối"
    else -> if (playing) "Đang phát" else "Chờ"
}

/** Đồ thị cột tốc độ gửi nhạc, 60 lần đo gần nhất (cột mới nhất ở bên phải). */
@Composable
private fun Spark(sk: Skin, v: List<Int>, modifier: Modifier) = Canvas(modifier) {
    val n = 40
    val data = v.takeLast(n)
    if (data.isEmpty()) return@Canvas
    val mx = maxOf(400, data.max()).toFloat()
    val w = size.width / n
    data.forEachIndexed { k, kb ->
        val x = (n - data.size + k) * w
        val hgt = (size.height * (kb / mx)).coerceIn(2.dp.toPx(), size.height)
        drawRect(if (kb > 0) sk.acc else sk.line, Offset(x + w * 0.15f, size.height - hgt), Size(w * 0.7f, hgt))
    }
}

@Composable
private fun Spec(sk: Skin, label: String, value: String, modifier: Modifier, size: Int = 17) = Column(modifier.height(56.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, sk.line, RoundedCornerShape(8.dp)).padding(9.dp, 6.dp)) {
    T(label.uppercase(), 10, sk.muted)
    T(value, size, sk.text)
}

@Composable
private fun IconBtn(sk: Skin, icon: ImageVector, desc: String, active: Boolean, onClick: () -> Unit) =
    Focusable(onClick, Modifier.size(96.dp), CircleShape, ring = if (sk.alu) AluBlack else C.Gold) {
        // Nhôm: nút đang bật = chấm đen biểu tượng vàng; nút tắt = biểu tượng đen trên nền nhôm
        if (sk.alu && active) Box(Modifier.align(Alignment.Center).size(62.dp).clip(CircleShape).background(AluBlack))
        Icon(icon, desc, tint = if (sk.alu) (if (active) AluYellow else sk.ink) else if (active) C.Gold else C.Text, modifier = Modifier.align(Alignment.Center).size(46.dp))
    }

@Composable
private fun QueuePanel(queue: List<Track>, idx: Int, alu: Boolean) {
    val st = rememberLazyListState()
    LaunchedEffect(idx, queue.size) { if (queue.isNotEmpty()) st.animateScrollToItem(idx.coerceIn(0, queue.lastIndex)) }
    val total = remember(queue) { queue.sumOf { it.durationMs } }
    Column(Modifier.width(460.dp).fillMaxHeight().background(if (alu) Color(0xFF0C0C0D) else C.Side).padding(horizontal = 24.dp, vertical = 26.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            T("Danh sách phát", 30, if (alu) Color.White else C.Text, serif = true)
            T("${queue.size} bài · ${fmt(total)}", 13, if (alu) Color(0xA6FFFFFF) else C.Muted)
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
