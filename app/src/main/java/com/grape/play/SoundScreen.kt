@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.grape.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SoundScreen(onNav: (Screen) -> Unit, onSources: () -> Unit, onOut: () -> Unit) {
    var showAbout by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().background(C.Bg)) {
        Rail(Screen.SOUND, onNav, onSources, onOut)
        Row(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 40.dp, vertical = 28.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(0.85f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                T("Cài đặt", 44, serif = true)
                T("DSD, đầu ra và tinh chỉnh chỉ dùng cho Sony Bravia", 16, C.Muted)
                ThemeCard()
                StyleCard()
                DisplayCard()
                BurnInCard()
                DsdCard()
                BackgroundCard()
                UpdateCard()
                GaplessCard()
                LogCard()
                TvHomeCard()
                CoverWebCard()
                LanguageCard()
                Focusable({ showAbout = true }, Modifier.fillMaxWidth(), RoundedCornerShape(12.dp)) {
                    T("Về ứng dụng (About)", 20, C.Gold, Modifier.padding(20.dp, 14.dp), bold = true)
                }
                ScrollTail()
            }
            EqPanel(Modifier.weight(1.15f).fillMaxHeight())
        }
    }
    if (showAbout) AboutDialog { showAbout = false }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(12.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)

@Composable
fun Opt(text: String, sel: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Focusable(onClick, modifier, RoundedCornerShape(8.dp), selected = sel) { T((if (sel) "● " else "○ ") + text, 18, if (sel) C.Gold else C.Text, Modifier.padding(14.dp, 9.dp)) }

@Composable
fun ThemeCard() {
    val cur by Themes.cur
    Card {
        T("GIAO DIỆN", 13, C.Muted, spaced = true)
        Themes.ALL.chunked(3).forEach { row ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { p -> ThemeOpt(p, cur.id == p.id) { Themes.set(p.id) } }
            }
        }
        T("Đổi màu toàn bộ ứng dụng ngay lập tức.", 14, C.Muted)
    }
}

@Composable
fun StyleCard() {
    val cur by Themes.style
    val styles = listOf(0 to "Mặc định", 4 to "Nhôm phay xước", 1 to "Cổ điển", 3 to "Nền màu")   // chỉ số 2 (Toàn ảnh) đã bỏ
    Card {
        T("BỐ CỤC MÀN ĐANG PHÁT", 13, C.Muted, spaced = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            styles.forEach { (i, nm) -> Opt(nm, cur == i) { Themes.setStyle(i) } }
        }
        T("Nhôm phay xước: tấm nhôm sáng, ô đen, đồng hồ VU nền vàng (chỉ TV; điện thoại dùng bố cục mặc định). Cổ điển và Nền màu lấy cảm hứng từ Symfonium, dùng được trên điện thoại, máy tính bảng và TV. TV: OK = phát/dừng · Menu = yêu thích · Back = về Thư viện.", 14, C.Muted, lines = 4)
    }
}

@Composable
private fun ThemeOpt(p: Palette, sel: Boolean, onClick: () -> Unit) =
    Focusable(onClick, shape = RoundedCornerShape(8.dp), selected = sel) {
        Row(Modifier.padding(12.dp, 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(p.bg).border(3.dp, p.accent, CircleShape))
            T(p.name, 17, if (sel) C.Gold else C.Text)
        }
    }

@Composable
fun BackgroundCard() {
    val ctx = LocalContext.current
    var keep by remember { mutableStateOf(BackgroundAccess.keep) }
    var batt by remember { mutableStateOf(BackgroundAccess.ignoringBattery(ctx)) }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { batt = BackgroundAccess.ignoringBattery(ctx); keep = BackgroundAccess.keep; delay(1500) } }
    val on = keep || batt
    Card {
        T("CHẠY NGẦM", 13, C.Muted, spaced = true)
        T(if (on) "✓ Đang bật chạy ngầm" else "Chưa bật chạy ngầm", 20, if (on) Grade.GREEN.color else C.Text)
        T(if (LocalCompact.current) "Giữ app chạy nền bằng dịch vụ phát nhạc (có biểu tượng thông báo) để máy không ngắt nhạc khi bạn tắt màn hình hoặc dùng app khác." else "Giữ app chạy nền bằng dịch vụ phát nhạc (có biểu tượng thông báo) để TV không ngắt nhạc khi bạn thoát ra màn hình chính hoặc dùng app khác.", 15, C.Muted, lines = 3)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Opt("Bật", keep) {
                val ok = (ctx.findActivity() as? MainActivity)?.askBackground()
                keep = BackgroundAccess.keep
                msg = if (ok == false) "Đã bật dịch vụ chạy nền. TV này không có màn hình cấp quyền tiết kiệm pin để mở — không sao, không bắt buộc." else "Đã bật dịch vụ chạy nền."
            }
            Opt("Tắt", !keep) {
                BackgroundAccess.keep = false; Keepalive.release(ctx); keep = false; msg = "Đã tắt chạy ngầm."
            }
        }
        T(if (batt) "✓ Đã miễn tối ưu pin cho app" else "Miễn tối ưu pin: chưa cấp (không bắt buộc trên TV)", 14, C.Muted, lines = 2)
        if (msg.isNotEmpty()) T(msg, 14, C.Gold, lines = 3)
    }
}

@Composable
fun CoverWebCard() {
    var web by remember { mutableStateOf(Covers.webEnabled) }
    Card {
        T("ẢNH BÌA TRÊN MẠNG", 13, C.Muted, spaced = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Opt("Bật", web) { Covers.setWeb(true); web = true }
            Opt("Tắt", !web) { Covers.setWeb(false); web = false }
        }
        T("Album/bài hát không có ảnh sẽ được tìm trên Deezer và iTunes theo tên nghệ sĩ + album (chỉ gửi tên, không gửi file nhạc). Ảnh tìm được lưu lại trong máy, không tải lại.", 15, C.Muted, lines = 4)
    }
}

@Composable
private fun TvHomeCard() {
    val ctx = LocalContext.current
    val on by TvHome.enabled
    val scope = rememberCoroutineScope()
    Card {
        T("TRANG CHỦ TV", 13, C.Muted, spaced = true)
        T("Hiện nhanh trên màn hình chính của TV", 22)
        T("Thêm hàng “GRAPEPlay” lên màn hình chính Android TV: bài yêu thích và nhạc phát gần đây kèm ảnh bìa, bấm vào là phát ngay.", 15, C.Muted, lines = 3)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Opt("Bật", on) { scope.launch { TvHome.enable(ctx); ctx.findActivity()?.let { TvHome.requestBrowsable(it) } } }
            Opt("Tắt", !on) { scope.launch { TvHome.disable(ctx) } }
        }
        T("Nếu chưa thấy hàng này, vào Tuỳ chỉnh kênh trên màn hình chính của TV rồi bật “GRAPEPlay”.", 14, C.Muted, lines = 2)
    }
}

@Composable
fun GaplessCard() {
    var on by remember { mutableStateOf(AudioSettings.gapless) }
    Card {
        T("PHÁT LIÊN TỤC", 13, C.Muted, spaced = true)
        T("Gapless (qua bài không có khoảng chờ)", 22, lines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Opt("Bật", on) { if (!on) { on = true; AudioSettings.gapless = true; Playback.applySettings() } }
            Opt("Tắt", !on) { if (on) { on = false; AudioSettings.gapless = false; Playback.applySettings() } }
        }
        T("Bật: app nạp trước bài kế tiếp ở bộ đệm lớn (90 giây) nên nối bài liền mạch, đặc biệt khi phát từ NAS / đám mây. Chỉ áp dụng khi phát trên chính TV/máy; phát tới thiết bị UPnP/Cast vẫn do thiết bị đó quyết định. Đổi cài đặt sẽ nạp lại bài đang nghe.", 14, C.Muted, lines = 5)
    }
}

@Composable
fun DsdCard() {
    var rate by remember { mutableStateOf(AudioSettings.dsdRate) }
    var dsdMode by remember { mutableStateOf(AudioSettings.dsdRemote) }
    Card {
        T("PHÁT DSD", 13, C.Muted, spaced = true)
        T("Chuyển DSD sang PCM", 22)
        T(if (LocalCompact.current) "Android không xuất DSD gốc: app giải mã DSF/DFF thành PCM 24-bit chất lượng cao rồi đưa ra loa, tai nghe hoặc DAC." else "TV Android 8.0 không xuất DSD gốc: app giải mã DSF/DFF thành PCM 24-bit chất lượng cao rồi đưa ra cổng quang/analogue.", 15, C.Muted, lines = 3)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Opt("44.1 kHz", rate == 44100) { rate = 44100; AudioSettings.dsdRate = 44100; AudioSettings.resetDsdLimits() }
            Opt("88.2 kHz", rate == 88200) { rate = 88200; AudioSettings.dsdRate = 88200; AudioSettings.resetDsdLimits() }
            Opt("176.4 kHz", rate == 176400) { rate = 176400; AudioSettings.dsdRate = 176400; AudioSettings.resetDsdLimits() }
        }
        T("Cổng quang S/PDIF thường chỉ nhận tới 48 kHz. Nếu đầu ra không nhận tốc độ đã chọn, app tự hạ xuống mức thấp hơn cho riêng đầu ra đó và phát lại. Áp dụng từ bài kế tiếp.", 14, C.Muted, lines = 2)
        Spacer(Modifier.height(10.dp))
        T("KHI PHÁT TỚI THIẾT BỊ UPnP (WiiM, Network Player…)", 13, C.Muted, spaced = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Opt("Gửi file DSD gốc", dsdMode == AudioSettings.DSD_RAW) { if (dsdMode != AudioSettings.DSD_RAW) { dsdMode = AudioSettings.DSD_RAW; AudioSettings.dsdRemote = AudioSettings.DSD_RAW; Playback.reloadRemoteDsd() } }
            Opt("DoP (DSD64 → PCM 24-bit/176,4 kHz)", dsdMode == AudioSettings.DSD_DOP) { if (dsdMode != AudioSettings.DSD_DOP) { dsdMode = AudioSettings.DSD_DOP; AudioSettings.dsdRemote = AudioSettings.DSD_DOP; Playback.reloadRemoteDsd() } }
        }
        T("DoP gói nguyên bit DSD64 vào PCM để thiết bị chuyển tiếp cho DAC ngoài tự giải mã DSD (WiiM Ultra không giải mã DSD gốc, chỉ chuyển sang PCM). Cần DAC hỗ trợ DoP, tắt EQ và để âm lượng thiết bị 100%. DAC không hỗ trợ DoP sẽ phát tiếng nhiễu rất to: hạ âm lượng trước khi thử. Chỉ áp dụng cho DSD64; DSD128 trở lên vẫn gửi file gốc. Đổi chế độ khi đang phát: bài hiện tại tự nạp lại ngay theo chế độ mới.", 14, C.Muted, lines = 6)
    }
}

@Composable
private fun DisplayCard() {
    val mode by DisplayPrefs.mode
    Card {
        T("HIỂN THỊ", 13, C.Muted, spaced = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Opt("Tự động", mode == 0) { DisplayPrefs.set(0) }
            Opt("FHD", mode == 1) { DisplayPrefs.set(1) }
            Opt("4K", mode == 2) { DisplayPrefs.set(2) }
        }
        T("Chữ và giao diện hơi nhỏ trên TV Full HD? Chọn FHD để phóng to.", 14, C.Muted, lines = 2)
    }
}

@Composable
private fun BurnInCard() {
    val on by BurnIn.enabled
    Card {
        T("CHỐNG LƯU ẢNH MÀN HÌNH OLED", 13, C.Muted, spaced = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Opt("Bật", on) { BurnIn.set(true) }
            Opt("Tắt", !on) { BurnIn.set(false) }
        }
        T("Dịch nhẹ toàn bộ giao diện vài điểm ảnh mỗi phút để các chi tiết cố định (thanh bên, logo, viền) không nằm yên một chỗ, và tự giảm sáng sau 5 phút không thao tác. Bấm phím hoặc chạm màn hình là sáng lại.", 15, C.Muted, lines = 4)
    }
}

@Composable
fun LanguageCard() {
    val en by Lang.en
    Card {
        T("NGÔN NGỮ", 13, C.Muted, spaced = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LangOpt("Tiếng Việt", !en) { Lang.set(false) }
            LangOpt("English", en) { Lang.set(true) }
        }
    }
}

@Composable
private fun LangOpt(text: String, sel: Boolean, onClick: () -> Unit) =
    Focusable(onClick, shape = RoundedCornerShape(8.dp), selected = sel) { RawText((if (sel) "● " else "○ ") + text, if (sel) C.Gold else C.Text) }

/** Chữ KHÔNG qua bộ dịch (tên ngôn ngữ luôn hiện bằng chính ngôn ngữ đó). */
@Composable
private fun RawText(s: String, c: Color) = androidx.compose.material3.Text(s, color = c, fontSize = 20.sp, modifier = Modifier.padding(16.dp, 10.dp))

// ---------------------------------------------------------------- EQ
@Composable
private fun EqPanel(modifier: Modifier) {
    val on by DspParams.on.collectAsState()
    val bands by DspParams.bands.collectAsState()
    val bal by DspParams.balance.collectAsState()
    val pre by DspParams.preamp.collectAsState()
    val labels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(12.dp)).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T("BỘ CÂN BẰNG (EQ)", 13, C.Muted, spaced = true)
            Focusable({ DspParams.setOn(!on) }, shape = RoundedCornerShape(8.dp)) { T((if (on) "● " else "○ ") + "Bật bộ cân bằng", 18, if (on) C.Gold else C.Text, Modifier.padding(14.dp, 8.dp)) }
        }
        EqWarning(on, bands, pre)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val names = listOf("Phẳng", "Ấm", "Giọng hát", "Trầm+")
            DspParams.PRESETS.forEachIndexed { i, (_, v) ->
                val sel = bands.indices.all { kotlin.math.abs(bands[it] - v[it]) < 0.01f }
                Focusable({ DspParams.setBands(v) }, shape = RoundedCornerShape(8.dp), selected = sel) { T(names[i], 17, if (sel) C.Gold else C.Text, Modifier.padding(14.dp, 8.dp)) }
            }
            Spacer(Modifier.weight(1f))
            Focusable({ DspParams.reset() }, shape = RoundedCornerShape(8.dp)) { T("Đặt lại", 17, C.Muted, Modifier.padding(14.dp, 8.dp)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until 10) EqBand(labels[i], bands[i], on, Modifier.weight(1f)) { DspParams.setBand(i, it) }
        }
        T("Dùng ◂ ▸ để chọn băng, bấm OK rồi ▴ ▾ để chỉnh ±0,5 dB.", 14, C.Muted)
        HSlider("Cân bằng trái / phải", bal, -1f, 1f, 0.05f, { if (kotlin.math.abs(it) < 0.01f) "L = R" else if (it < 0) "L %d%%".format((-it * 100).toInt()) else "R %d%%".format((it * 100).toInt()) }) { DspParams.setBalance(it) }
        HSlider("Preamp (dB)", pre, -12f, 6f, 0.5f, { "%+.1f dB".format(it) }) { DspParams.setPreamp(it) }
        T("Khi EQ phẳng và cân bằng ở giữa, âm thanh đi thẳng không qua xử lý (bit-perfect).", 14, C.Gold, lines = 2)
    }
}

/** Cảnh báo méo tiếng: đỏ = nguy cơ cắt đỉnh (tăng ròng > 0 dB), vàng = EQ làm đổi tín hiệu gốc, xám = EQ đang phẳng. */
@Composable
fun EqWarning(on: Boolean, bands: List<Float>, pre: Float) {
    val flat = DspParams.isFlat()
    val peak = (bands.maxOrNull() ?: 0f) + pre            // mức tăng lớn nhất so với tín hiệu gốc (dB)
    val risk = on && !flat && peak > 0.05f
    val col = if (risk) Grade.RED.color else if (on && !flat) Grade.YELLOW.color else C.Muted
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, col, RoundedCornerShape(8.dp)).background(col.copy(alpha = 0.10f)).padding(14.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (risk) {
            T("⚠ Nguy cơ méo tiếng (cắt đỉnh / clipping)", 17, col, bold = true)
            T("Đỉnh EQ đang tăng %+.1f dB — giảm Preamp xuống ≤ %.1f dB hoặc giảm các băng đang tăng.".format(peak, -peak), 15, col, lines = 3)
        } else if (on && !flat) {
            T("⚠ Chỉnh EQ làm thay đổi tín hiệu gốc", 17, col, bold = true)
            T("Không còn bit-perfect. Tăng nhiều ở dải trầm/cao có thể làm méo tiếng — nên chỉnh nhẹ và để Preamp âm.", 15, col, lines = 3)
        } else {
            T("Lưu ý: chỉnh EQ có thể làm méo tiếng, nhất là khi tăng cao.", 15, col, lines = 2)
        }
    }
}

@Composable
private fun EqBand(label: String, db: Float, enabled: Boolean, modifier: Modifier, onChange: (Float) -> Unit) {
    var f by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    Column(
        modifier.clip(RoundedCornerShape(8.dp))
            .onFocusChanged { f = it.isFocused; if (!it.isFocused) edit = false }
            .onKeyEvent { e ->
                if (edit && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { onChange(db + 0.5f); true }
                else if (edit && e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { onChange(db - 0.5f); true }
                else false
            }
            .clickable { edit = !edit }
            .then(if (f) Modifier.border(3.dp, if (edit) C.PlumSoft else C.Gold, RoundedCornerShape(8.dp)) else Modifier)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        T("%+.1f".format(db), 13, if (edit) C.PlumSoft else C.Muted)
        val on = enabled
        Canvas(Modifier.width(30.dp).height(210.dp)) {
            val w = size.width; val h = size.height
            drawRoundRect(C.Line, Offset(w / 2 - 2.dp.toPx(), 0f), Size(4.dp.toPx(), h), CornerRadius(2.dp.toPx()))
            val y = h * (12f - db) / 24f
            drawRect(C.Dim, Offset(0f, h / 2 - 0.5f), Size(w, 1f))
            drawRoundRect(if (on) C.Gold else C.Dim, Offset(w / 2 - 2.dp.toPx(), minOf(y, h / 2)), Size(4.dp.toPx(), kotlin.math.abs(y - h / 2)), CornerRadius(2.dp.toPx()))
            drawCircle(if (edit) C.PlumSoft else if (on) C.Gold else C.Dim, 11.dp.toPx(), Offset(w / 2, y))
        }
        T(label, 14, C.Text)
    }
}

@Composable
private fun HSlider(label: String, value: Float, min: Float, max: Float, step: Float, show: (Float) -> String, onChange: (Float) -> Unit) {
    var f by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .onFocusChanged { f = it.isFocused; if (!it.isFocused) edit = false }
            .onKeyEvent { e ->
                if (edit && e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onChange((value + step).coerceIn(min, max)); true }
                else if (edit && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onChange((value - step).coerceIn(min, max)); true }
                else false
            }
            .clickable { edit = !edit }
            .then(if (f) Modifier.border(3.dp, if (edit) C.PlumSoft else C.Gold, RoundedCornerShape(8.dp)) else Modifier)
            .padding(12.dp, 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T(label, 17, C.Text); T(show(value), 16, if (edit) C.PlumSoft else C.Gold) }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val frac = (value - min) / (max - min)
            val cy = size.height / 2
            drawRoundRect(C.Line, Offset(0f, cy - 2.dp.toPx()), Size(size.width, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
            drawRect(C.Dim, Offset(size.width * (0 - min) / (max - min) - 1f, cy - 8.dp.toPx()), Size(2f, 16.dp.toPx()))
            drawCircle(if (edit) C.PlumSoft else C.Gold, 10.dp.toPx(), Offset(size.width * frac, cy))
        }
    }
}

// ---------------------------------------------------------------- About
@Composable
fun AboutDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val compact = LocalCompact.current
    @Composable fun CloseBar() {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (compact) 16.dp else 8.dp), contentAlignment = Alignment.Center) {
            Focusable(onClose, Modifier.width(if (compact) 200.dp else 130.dp), requester = first) { T("Đóng", if (compact) 20 else 14, C.Gold, Modifier.align(Alignment.Center).padding(if (compact) 20.dp else 12.dp, if (compact) 10.dp else 6.dp), bold = true) }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (compact) Column(
            Modifier.dlgW(980).dlgH(900).clip(RoundedCornerShape(18.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(18.dp)).verticalScroll(rememberScrollState()),
        ) {
            AboutBrand(ver, Modifier.fillMaxWidth().background(C.Bg).padding(horizontal = 20.dp, vertical = 20.dp), true)
            AboutBody(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), true)
            CloseBar()
        } else Column(
            Modifier.dlgFrac(0.5f).clip(RoundedCornerShape(14.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(18.dp)),
        ) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                AboutBrand(ver, Modifier.width(190.dp).fillMaxHeight().background(C.Bg).padding(horizontal = 14.dp, vertical = 18.dp), false)
                AboutBody(Modifier.weight(1f).padding(horizontal = 22.dp, vertical = 18.dp), false)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
            CloseBar()
        }
    }
}

/** Cột nhận diện thương hiệu: logo, tên, mô tả, phiên bản. horizontal = true: xếp ngang (điện thoại). */
@Composable
private fun AboutBrand(ver: String, modifier: Modifier, horizontal: Boolean) {
    @Composable fun pill() { if (ver.isNotEmpty()) Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, C.Gold, RoundedCornerShape(50)).padding(horizontal = if (horizontal) 16.dp else 10.dp, vertical = if (horizontal) 6.dp else 4.dp)) { T("Phiên bản $ver", if (horizontal) 14 else 12, C.Gold, bold = !horizontal) } }
    if (horizontal) Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GrapeLogo(Modifier.size(64.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Wordmark(30)
            T("Trình phát nhạc hi-end cho Android", 15, C.Muted, lines = 2)
            pill()
        }
    } else Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        GrapeLogo(Modifier.size(56.dp))
        Wordmark(22)
        T(if (Device.tv) "Trình phát nhạc hi-end cho Android TV" else "Trình phát nhạc hi-end cho Android", 12, C.Muted, lines = 3)
        if (ver.isNotEmpty()) { Spacer(Modifier.height(2.dp)); pill() }
    }
}

@Composable
private fun AboutBody(modifier: Modifier, big: Boolean) {
    val k = if (big) 0.82f else 0.62f
    fun f(n: Int) = (n * k).toInt()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (big) 12.dp else 8.dp)) {
        T("LIMITED EDITION", f(13).coerceAtLeast(10), C.PlumSoft, spaced = true, bold = true)
        T("Viết tặng Ngọc Nho và các anh em yêu âm thanh", f(24), C.Gold, serif = true, bold = true, lines = 3)
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
        T("App được viết dựa trên ý tưởng tặng cho một chuyên gia âm thanh già rất mê âm thanh nhưng không có tiền để đầu tư thiết bị.", f(18), C.Text, lines = 5)
        T("Dùng để cứu vãn vòng đời của chiếc TV Sony Bravia 2015 hiện đã bị sọc màn 🙏🙏🙏🤣", f(18), C.Muted, lines = 3)
        Spacer(Modifier.height(2.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Bg).padding(horizontal = if (big) 20.dp else 12.dp, vertical = if (big) 14.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AboutRow("NHÀ PHÁT TRIỂN", "Phan Kim Phụng", big)
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
            AboutRow("GITHUB", "https://github.com/PhungP/grape2play", big)
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String, phone: Boolean = false) {
    // Xếp dọc (nhãn trên, giá trị dưới) để không bị cắt chữ; trên điện thoại dùng nét mảnh, cỡ nhỏ hơn cho đồng bộ với các trang khác.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        T(label, if (phone) 11 else 10, C.Muted, spaced = true)
        T(value, if (phone) 15 else 12, C.Text, bold = !phone, lines = 2)
    }
}
