package com.grape.play

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Bảng màu như Spek: đen/tím (-120 dB) → xanh dương → xanh lơ → xanh lá → vàng → cam → đỏ (đỉnh). */
private val LUT: IntArray = run {
    val stops = listOf(
        0.00f to intArrayOf(6, 0, 20), 0.08f to intArrayOf(40, 0, 110), 0.20f to intArrayOf(20, 20, 235), 0.32f to intArrayOf(0, 130, 255),
        0.42f to intArrayOf(60, 225, 255), 0.52f to intArrayOf(60, 245, 170), 0.60f to intArrayOf(130, 245, 40), 0.70f to intArrayOf(255, 250, 20),
        0.80f to intArrayOf(255, 190, 0), 0.90f to intArrayOf(255, 105, 0), 1.00f to intArrayOf(255, 30, 20))
    IntArray(256) { i ->
        val t = i / 255f
        var k = 1; while (k < stops.size - 1 && t > stops[k].first) k++
        val (t0, c0) = stops[k - 1]; val (t1, c1) = stops[k]
        val f = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
        val c = IntArray(3) { (c0[it] + (c1[it] - c0[it]) * f).toInt() }
        (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
    }
}

private fun fmtTime(sec: Double): String { val s = sec.toInt(); return "%d:%02d".format(s / 60, s % 60) }

/** Phổ tần cố định của cả bài đang phát (như ảnh Spek). File DSD: hiện thông báo lý do không có phổ. */
@Composable
fun SpectrumDialog(onClose: () -> Unit) {
    val queue by Playback.queue.collectAsState()
    val idx by Playback.index.collectAsState()
    val t = queue.getOrNull(idx)
    val dsd = t != null && Spectrogram.isDsd(t)
    var top by remember { mutableStateOf(-20) }   // đỉnh thang màu: -20 dB (như Spek) hoặc 0 dB; đáy luôn -120 dB
    var data by remember { mutableStateOf<SpecData?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var ver by remember { mutableStateOf(0) }
    val first = remember { FocusRequester() }
    val tm = rememberTextMeasurer()
    val compact = LocalCompact.current
    val screenH = LocalConfiguration.current.screenHeightDp

    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    // Giải mã + phân tích (chạy lại khi đổi bài)
    LaunchedEffect(t?.path) {
        data = null; err = null; progress = 0f
        if (t == null || dsd) return@LaunchedEffect
        val c = Spectrogram.cached(t.path)
        if (c != null) { data = c; return@LaunchedEffect }
        try { Spectrogram.analyze(t) { data = it } }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) { err = e.message ?: e.javaClass.simpleName }
    }

    val d = data
    val topDb = top
    val bmps = remember(d) { d?.let { dd -> Array(dd.nc) { Bitmap.createBitmap(dd.cols, dd.rows, Bitmap.Config.ARGB_8888) } } }
    val images = remember(bmps) { bmps?.map { it.asImageBitmap() } }

    // Tô màu dần các cột đã tính xong
    LaunchedEffect(d, top) {
        if (d == null || bmps == null) return@LaunchedEffect
        var from = 0
        while (true) {
            val done = d.done
            if (done > from) {
                val a = from; val b = done
                withContext(Dispatchers.Default) {
                    val cnt = b - a
                    val px = IntArray(cnt * d.rows)
                    for (c in 0 until d.nc) {
                        for (col in a until b) for (y in 0 until d.rows) {
                            val db = d.db[c][col * d.rows + (d.rows - 1 - y)]
                            val v = ((db + 120f) / (top + 120f)).coerceIn(0f, 1f)
                            px[y * cnt + (col - a)] = LUT[(v * 255).toInt()]
                        }
                        bmps[c].setPixels(px, 0, cnt, a, 0, cnt, d.rows)
                    }
                }
                from = done; ver++
            }
            progress = from.toFloat() / d.cols
            if (from >= d.cols) break
            delay(150)
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.dlgW(1360).dlgH(if (compact) 820 else 800).clip(RoundedCornerShape(16.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(16.dp))
                .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier).dlgPad(36, 24),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                T("Phổ tần số (Spectrogram)", 24, serif = true)
                if (!compact) T("Back để đóng", 15, C.Muted)
            }
            if (t != null) T(if (t.path.startsWith("/")) t.path.substringAfterLast('/') else t.title, 16, C.Gold)

            // TV: khung phổ chiếm đúng phần còn lại của hộp thoại (tiêu đề + nút luôn thấy, không phải cuộn nên không mất mép 22 kHz).
            Box(Modifier.fillMaxWidth().then(if (compact) Modifier.height((screenH * 0.5f).coerceIn(240f, 440f).dp) else Modifier.weight(1f).heightIn(min = 260.dp)).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                val ims = images
                if (d != null && ims != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        ver.let { }   // đọc state để vẽ lại khi có cột mới
                        val padL = (if (compact) 54.dp else 74.dp).toPx()
                        val padR = (if (compact) 78.dp else 112.dp).toPx()
                        val padT = (if (compact) 28.dp else 40.dp).toPx()
                        val padB = (if (compact) 28.dp else 40.dp).toPx()
                        val w = size.width - padL - padR
                        val h = size.height - padT - padB
                        val lbl = TextStyle(color = Color.White, fontSize = if (compact) 11.sp else 15.sp)
                        val white = Color.White
                        val tick = 5.dp.toPx()

                        // Tiêu đề trong khung, căn giữa, cắt "…" khi quá rộng (như Spek)
                        val tl = tm.measure(d.title, lbl.copy(fontSize = if (compact) 12.sp else 17.sp), overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = w.toInt()))
                        drawText(tl, topLeft = Offset(padL + (w - tl.size.width) / 2f, (padT - tl.size.height) / 2f))

                        // Phổ: một bảng gộp 2 kênh
                        drawImage(ims[0], IntOffset.Zero, IntSize(d.cols, d.rows), IntOffset(padL.toInt(), padT.toInt()), IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.Low)
                        drawRect(white, Offset(padL, padT), Size(w, h), style = Stroke(1.dp.toPx()))

                        // Trục tần số: 0, 5, 10, 15, 20 kHz… và mép trên = Nyquist (22 kHz)
                        val nyq = d.sr / 2000f
                        val fStep = listOf(1f, 2f, 5f, 10f, 20f, 50f).first { nyq / it <= 5.5f }
                        val fLabels = ArrayList<Float>()
                        var f = 0f
                        while (f <= nyq + 0.001f) { fLabels.add(f); f += fStep }
                        if (nyq - fLabels.last() >= fStep * 0.3f) fLabels.add(nyq)
                        for (fv in fLabels) {
                            val y = padT + h * (1f - fv / nyq)
                            drawLine(white, Offset(padL - tick, y), Offset(padL, y), 1.dp.toPx())
                            val txt = "${fv.roundToInt()} kHz"; val m = tm.measure(txt, lbl)
                            drawText(m, topLeft = Offset(padL - tick - 4.dp.toPx() - m.size.width, y - m.size.height / 2f))
                        }

                        // Trục thời gian: 0:00, 1:00, … và mốc cuối bài
                        val tStep = listOf(5, 10, 15, 30, 60, 120, 300, 600, 1800).first { d.seconds / it <= 6.5 }
                        val tLabels = ArrayList<Double>()
                        var s = 0
                        while (s <= d.seconds) { tLabels.add(s.toDouble()); s += tStep }
                        if (d.seconds - tLabels.last() >= tStep * 0.3) tLabels.add(d.seconds)
                        for (sv in tLabels) {
                            val x = padL + w * (sv / d.seconds).toFloat()
                            drawLine(white, Offset(x, padT + h), Offset(x, padT + h + tick), 1.dp.toPx())
                            val txt = fmtTime(sv); val m = tm.measure(txt, lbl)
                            drawText(m, topLeft = Offset((x - m.size.width / 2f).coerceIn(0f, size.width - m.size.width), padT + h + tick + 3.dp.toPx()))
                        }

                        // Thang màu bên phải: -20 dB (đỏ) … -120 dB (tím đen)
                        val lx = padL + w + (if (compact) 10.dp else 16.dp).toPx(); val lw = (if (compact) 10.dp else 14.dp).toPx()
                        val steps = 160
                        for (i in 0 until steps) {
                            val v = 1f - i / (steps - 1f)
                            drawRect(Color(LUT[(v * 255).toInt()]), Offset(lx, padT + h * i / steps), Size(lw, h / steps + 1f))
                        }
                        var db = topDb
                        while (db >= -120) {
                            val y = padT + h * ((topDb - db) / (topDb + 120f))
                            drawLine(white, Offset(lx + lw, y), Offset(lx + lw + 4.dp.toPx(), y), 1.dp.toPx())
                            val m = tm.measure("$db dB", lbl)
                            drawText(m, topLeft = Offset(lx + lw + 7.dp.toPx(), y - m.size.height / 2f))
                            db -= 20
                        }
                    }
                }
                val msg: Pair<String, String>? = when {
                    t == null -> "Chưa có bài nào đang phát" to "Hãy phát một bài rồi mở lại bảng này để xem phổ tần số."
                    dsd -> "File DSD không có phổ tần kiểu Spek" to
                        "• DSD là luồng 1-bit tốc độ hàng triệu mẫu/giây (DSD64 = 2,8224 MHz), không phải PCM nên không thể FFT trực tiếp như FLAC/WAV.\n" +
                        "• Nhiễu lượng tử của DSD được đẩy dồn lên dải siêu âm, nên biểu đồ sẽ chỉ thấy toàn nhiễu và không phản ánh chất lượng nhạc.\n" +
                        "• Khi phát, app chuyển DSD sang PCM qua bộ lọc; phổ lúc đó là kết quả của bộ lọc chứ không phải của file gốc."
                    err != null -> "Không phân tích được file này" to (err ?: "")
                    d == null -> "Đang mở file…" to ""
                    else -> null
                }
                if (msg != null) Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 18.dp else 80.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    T(msg.first, 24, if (err != null) Color(0xFFE5575A) else C.Gold, serif = true, bold = true, lines = 2)
                    if (msg.second.isNotEmpty()) T(msg.second, 17, Color(0xFFD0D0D0), lines = 12)
                }
            }

            if (d != null && progress < 1f) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(320.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Line)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0.02f, 1f)).background(C.Gold))
                    }
                    T("Đang giải mã và phân tích… ${(progress * 100).toInt()}%", 14, C.Muted)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Thang màu", 12, C.Muted, spaced = true)
                for ((tv, label) in listOf(-20 to "-20 → -120 dB (Spek)", 0 to "0 → -120 dB")) Focusable({ top = tv }, selected = top == tv, requester = if (tv == -20) first else null) {
                    T((if (top == tv) "● " else "○ ") + label, 16, if (top == tv) C.Gold else C.Text, Modifier.padding(16.dp, 8.dp))
                }
                Spacer(Modifier.weight(1f))
                Focusable(onClose) { T("Đóng", 16, C.Gold, Modifier.padding(26.dp, 8.dp), bold = true) }
            }
        }
    }
}

/** Xác nhận thoát: dừng nhạc và đóng app. */
@Composable
fun ExitDialog(onStay: () -> Unit, onExit: () -> Unit) {
    val stay = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { stay.requestFocus() } }
    Dialog(onDismissRequest = onStay, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.dlgW(780).clip(RoundedCornerShape(16.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(16.dp)).dlgPad(48, 36),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            T("Thoát GRAPEPlay?", 38, serif = true)
            T("Nhạc đang phát sẽ dừng và app sẽ đóng.", 20, C.Muted, lines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                Focusable(onExit, ring = Color(0xFFE5575A)) { T("Thoát", 20, Color(0xFFE5575A), Modifier.padding(26.dp, 12.dp), bold = true) }
                Focusable(onStay, requester = stay) { T("Ở lại", 20, C.Gold, Modifier.padding(26.dp, 12.dp), bold = true) }
            }
        }
    }
}
