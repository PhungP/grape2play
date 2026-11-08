package com.grape.play

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.log10

object C {
    // Màu lấy từ giao diện đang chọn (Themes). Đọc state nên khi đổi giao diện toàn bộ màn hình tự vẽ lại.
    val Bg: Color get() = Themes.cur.value.bg
    val Panel: Color get() = Themes.cur.value.panel
    val Side: Color get() = Themes.cur.value.side
    val Line: Color get() = Themes.cur.value.line
    val Dim: Color get() = Themes.cur.value.dim
    val Sel: Color get() = Themes.cur.value.sel
    val Gold: Color get() = Themes.cur.value.accent
    val Text: Color get() = Themes.cur.value.text
    val Muted: Color get() = Themes.cur.value.muted
    val Plum: Color get() = Themes.cur.value.plum
    val PlumSoft: Color get() = Themes.cur.value.plumSoft
}

/** true khi chạy trên điện thoại / máy tính bảng (giao diện cảm ứng gọn), false trên TV. */
val LocalCompact = compositionLocalOf { false }
/** Chiều rộng canvas thiết kế (dp) của TV: 1560 (FHD) hoặc 1760 (4K). Điện thoại: 1560. */
val LocalCanvasW = compositionLocalOf { 1560f }
/** Hệ số co chữ: 1 trên TV và máy tính bảng lớn, nhỏ hơn trên điện thoại. */
val LocalTextScale = compositionLocalOf { 1f }

@Composable
fun T(s: String, size: Int, color: Color = C.Text, modifier: Modifier = Modifier, serif: Boolean = false,
      bold: Boolean = false, lines: Int = 1, spaced: Boolean = false) {
    val ts = LocalTextScale.current
    val px = if (ts == 1f) size.toFloat() else (size * ts).coerceAtLeast(10f)
    Text(Lang.tr(s), modifier = modifier, color = color, fontSize = px.sp,
        fontFamily = if (serif) FontFamily.Serif else null,
        fontWeight = if (bold) FontWeight.Bold else null,
        letterSpacing = if (spaced) 3.sp else TextUnit.Unspecified,
        lineHeight = if (lines > 1) (px * 1.2f).sp else TextUnit.Unspecified,
        maxLines = lines, overflow = TextOverflow.Ellipsis)
}

/** Chiều rộng hộp thoại: tối đa maxW dp, nhưng không quá 94% màn hình (để vừa điện thoại). */
fun Modifier.dlgW(maxW: Int): Modifier = layout { m, c ->
    val w = minOf(maxW.dp.roundToPx(), (c.maxWidth * 0.94f).toInt())
    val p = m.measure(c.copy(minWidth = w, maxWidth = w))
    layout(p.width, p.height) { p.placeRelative(0, 0) }
}

/** Chiều rộng hộp thoại = [f] × chiều rộng màn hình (TV: tỉ lệ cố định, không phụ thuộc kích thước dp). */
fun Modifier.dlgFrac(f: Float): Modifier = layout { m, c ->
    val w = (c.maxWidth * f).toInt()
    val p = m.measure(c.copy(minWidth = w, maxWidth = w))
    layout(p.width, p.height) { p.placeRelative(0, 0) }
}

/** Chiều cao hộp thoại = [f] × chiều cao màn hình. */
fun Modifier.dlgHFrac(f: Float): Modifier = layout { m, c ->
    val h = (c.maxHeight * f).toInt()
    val p = m.measure(c.copy(minHeight = h, maxHeight = h))
    layout(p.width, p.height) { p.placeRelative(0, 0) }
}

/** Dải cuối trang cuộn: nhận tiêu điểm D-pad để trang cuộn hẳn xuống cuối, không bị cắt chữ ở mép dưới. */
@Composable
fun ScrollTail() { Box(Modifier.fillMaxWidth().height(36.dp).focusable()) }

/** Chiều cao tối đa hộp thoại: maxH dp nhưng không quá 92% màn hình. */
fun Modifier.dlgH(maxH: Int): Modifier = layout { m, c ->
    val h = minOf(maxH.dp.roundToPx(), (c.maxHeight * 0.92f).toInt())
    val p = m.measure(c.copy(maxHeight = h))
    layout(p.width, p.height) { p.placeRelative(0, 0) }
}

/** Lề trong hộp thoại: gọn hơn trên điện thoại. */
@Composable
fun Modifier.dlgPad(h: Int, v: Int): Modifier =
    if (LocalCompact.current) padding(horizontal = (h / 3).dp, vertical = (v / 2).dp) else padding(horizontal = h.dp, vertical = v.dp)

/** Hai khối cạnh nhau (TV) hoặc xếp dọc (điện thoại). */
@Composable
fun SplitPane(modifier: Modifier, left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    if (LocalCompact.current) Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp)) { left(Modifier.fillMaxWidth()); right(Modifier.fillMaxWidth()) }
    else Row(modifier, horizontalArrangement = Arrangement.spacedBy(56.dp)) { left(Modifier.weight(1f)); right(Modifier.weight(1.3f)) }
}

/** Hai ô nhập cạnh nhau (TV) hoặc xếp dọc (điện thoại). */
@Composable
fun FieldPair(a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
    if (LocalCompact.current) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { a(Modifier.fillMaxWidth()); b(Modifier.fillMaxWidth()) }
    else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { a(Modifier.weight(1f)); b(Modifier.weight(1f)) }
}

/** Ô điều khiển được bằng remote: có viền vàng khi được chọn. `onLong`: giữ nút OK (hoặc bấm Menu) để chạy hành động phụ. */
@Composable
fun Focusable(onClick: () -> Unit, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp),
              selected: Boolean = false, ring: Color = C.Gold, requester: FocusRequester? = null,
              onLong: (() -> Unit)? = null,
              content: @Composable BoxScope.(Boolean) -> Unit) {
    var f by remember { mutableStateOf(false) }
    val longDone = remember { BooleanArray(1) }
    var m = modifier
    if (requester != null) m = m.focusRequester(requester)
    Box(
        m.onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { e ->
                val lg = onLong
                if (lg == null) false
                else {
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0) {
                        if (!longDone[0]) { longDone[0] = true; lg() }
                        true
                    } else if (e.key == Key.Menu && e.type == KeyEventType.KeyDown) { lg(); true }
                    else false
                }
            }
            .clip(shape)
            .background(if (f || selected) C.Sel else Color.Transparent)
            .then(if (f) Modifier.border(3.dp, ring, shape) else Modifier)
            .clickable { if (longDone[0]) longDone[0] = false else onClick() },
    ) { content(f) }
}

@Composable
fun Pill(text: String) = Box(Modifier.border(1.dp, C.Gold, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) { T(text, 13, C.Gold, bold = true) }

@Composable
fun CoverImage(t: Track?, modifier: Modifier = Modifier, corner: Int = 6) {
    val bmp by produceState<Bitmap?>(null, t?.folder, t?.album) { value = if (t == null) null else Covers.get(t) }
    Box(modifier.clip(RoundedCornerShape(corner.dp)).background(Brush.linearGradient(listOf(Color(0xFF3A2F1E), Color(0xFF14110C))))) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (t == null) Icon(Icons.Filled.MusicNote, null, tint = C.Dim, modifier = Modifier.align(Alignment.Center).fillMaxSize(0.4f))
        else GeneratedCover(t.album.hashCode())
    }
}

/** Bìa tạo sẵn (gradient theo tên album + đĩa vàng) khi file không có ảnh bìa. */
@Composable
fun GeneratedCover(seed: Int) {
    val bases = listOf(0xFF4A2F6B, 0xFF2F4A6B, 0xFF6B4A2F, 0xFF2F6B4F, 0xFF6B2F4A, 0xFF3B2F6B, 0xFF1F5B6B, 0xFF6B5A2F)
    val base = Color(bases[Math.floorMod(seed, bases.size)])
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(listOf(base, Color(0xFF120C1A))))
        val r = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0x33D4B36A), r * 0.34f, c, style = Stroke(r * 0.008f))
        drawCircle(Color(0x55D4B36A), r * 0.24f, c, style = Stroke(r * 0.010f))
        drawCircle(Color(0x88D4B36A), r * 0.14f, c, style = Stroke(r * 0.012f))
        drawCircle(Color(0xFFD4B36A), r * 0.04f, c)
    }
}

/** Thanh sóng âm + tiến trình. Hình dạng sóng được tạo từ đường dẫn file (chưa phải sóng âm thật, sẽ làm ở giai đoạn sau). */
@Composable
fun Waveform(seed: Int, progress: Float, modifier: Modifier = Modifier) {
    val n = 110
    val hs = remember(seed) {
        val r = java.util.Random(seed.toLong()); val raw = FloatArray(n) { 0.2f + 0.8f * r.nextFloat() }
        FloatArray(n) { i -> (raw[maxOf(0, i - 1)] + raw[i] * 2 + raw[minOf(n - 1, i + 1)]) / 4f }
    }
    Canvas(modifier) {
        val w = size.width / n
        hs.forEachIndexed { i, h ->
            val bh = size.height * h
            drawRoundRect(if (i < progress * n) C.Gold else C.Dim, Offset(i * w + w * 0.15f, (size.height - bh) / 2), Size(w * 0.7f, bh), CornerRadius(w * 0.3f))
        }
    }
}

@Composable
fun Vu(label: String, level: Float, modifier: Modifier = Modifier) {
    val db = if (level <= 0.0005f) -60f else 20f * log10(level)
    val frac = ((db + 40f) / 40f).coerceIn(0f, 1f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        T(label, 14, C.Muted, Modifier.width(26.dp))
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val cr = CornerRadius(5.dp.toPx())
            drawRoundRect(C.Line, cornerRadius = cr)
            drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF8A7340), C.Gold)), size = Size(size.width * frac, size.height), cornerRadius = cr)
        }
        T(if (db <= -60f) "-∞ dB" else "%.1f dB".format(db), 14, C.Muted, Modifier.width(88.dp).padding(start = 12.dp))
    }
}

@Composable
fun TrackRow(i: Int, t: Track, current: Boolean, compact: Boolean = false, onClick: () -> Unit) =
    Focusable(onClick, Modifier.fillMaxWidth(), selected = current, onLong = { Favorites.toggle(t) }) {
        Row(Modifier.padding(if (compact) 5.dp else 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
            T("${i + 1}", if (compact) 13 else 16, C.Muted, Modifier.width(if (compact) 26.dp else 36.dp))
            CoverImage(t, Modifier.size(if (compact) 38.dp else 56.dp), 4)
            Column(Modifier.weight(1f)) {
                T(t.title, if (compact) 16 else 19, if (current) C.Gold else C.Text)
                T("${t.artist} · ${t.ext.uppercase()}", if (compact) 12 else 14, C.Muted)
            }
            if (t.path in Favorites.paths.value) Icon(Icons.Filled.Favorite, null, tint = C.Gold, modifier = Modifier.size(if (compact) 16.dp else 22.dp))
            T(fmt(t.durationMs), if (compact) 12 else 14, C.Muted)
        }
    }
