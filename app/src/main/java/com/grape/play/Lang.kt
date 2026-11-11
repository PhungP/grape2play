package com.grape.play

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * Ngôn ngữ giao diện: Tiếng Việt (gốc) hoặc English. Mọi chữ hiển thị đều đi qua T() → Lang.tr().
 * Chuỗi cố định dịch theo bảng; chuỗi có số/tên chèn vào dịch theo mẫu (regex). Chuỗi không có trong bảng (ví dụ tên bài hát) giữ nguyên.
 */
/** Kích thước giao diện: 0 = tự động, 1 = FHD (canvas 1560×878), 2 = 4K (canvas 1760×990, lớn hơn bản 0.8.1 khoảng 9%). */
object DisplayPrefs {
    val mode = mutableStateOf(0)
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("display", Context.MODE_PRIVATE)
        mode.value = sp.getInt("mode", 0)
    }
    fun set(m: Int) { mode.value = m; if (::sp.isInitialized) sp.edit().putInt("mode", m).apply() }
    /** Chiều rộng canvas thiết kế (dp) theo chế độ và độ phân giải thật. */
    fun canvasWidth(heightPx: Int): Float = when (mode.value) { 1 -> 1560f; 2 -> 1640f; else -> if (heightPx <= 1200) 1560f else 1640f }
}

object Lang {
    val en = mutableStateOf(false)
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("lang", Context.MODE_PRIVATE)
        en.value = sp.getString("ui", "vi") == "en"
    }
    fun set(english: Boolean) { en.value = english; if (::sp.isInitialized) sp.edit().putString("ui", if (english) "en" else "vi").apply() }

    private val marker = Regex("^((?:●|○|▶|⤮|🔍|📂|\\+|✓|⚠|◂|▸)\\s*)")

    fun tr(s: String): String {
        if (!en.value || s.isEmpty()) return s
        val m = marker.find(s)
        if (m != null) return m.value + tr1(s.substring(m.value.length))
        return tr1(s)
    }

    private fun tr1(s: String): String {
        MAP[s]?.let { return it }
        for ((rx, rep) in RULES) { val m = rx.matchEntire(s) ?: continue; return fill(rep, m) }
        // câu ghép "A · B": dịch từng phần
        if (s.contains(" · ")) { val parts = s.split(" · "); val t = parts.map { MAP[it] ?: it }; if (t != parts) return t.joinToString(" · ") }
        return s
    }
    private fun fill(rep: String, m: MatchResult): String {
        var r = rep
        for (i in m.groupValues.size - 1 downTo 1) r = r.replace("$" + i, m.groupValues[i])
        return r
    }

    private val MAP: Map<String, String> = mapOf(
        // v0.9: nhật ký lỗi, thông báo cập nhật, màn Đang phát mới
        "NHẬT KÝ LỖI" to "ERROR LOG", "Xem / gửi nhật ký" to "View / send log", "Nhật ký sử dụng ứng dụng" to "App usage log",
        "Ghi lại các lỗi phát nhạc, kết nối loa/NAS, cập nhật trong lúc dùng. Khi gặp sự cố, gửi nhật ký này cho nhà phát triển để kiểm tra." to "Records playback errors, speaker/NAS connection problems and update issues while you use the app. If something goes wrong, send this log to the developer.",
        "Chưa có dòng nhật ký nào." to "No log entries yet.", "Gửi" to "Send", "Đã xoá nhật ký." to "Log cleared.",
        "Để gửi cho nhà phát triển: trên điện thoại/máy tính cùng mạng, mở trình duyệt và nhập địa chỉ bên dưới, rồi lưu hoặc sao chép nội dung gửi đi:" to "To send it to the developer: on a phone/computer on the same network open a browser, type the address below, then save or copy the content and send it:",
        "TV chưa kết nối mạng nên chưa thể mở nhật ký qua trình duyệt." to "The TV is not on the network, so the log cannot be opened in a browser.",
        "TÍNH NĂNG / THAY ĐỔI TRONG BẢN NÀY" to "WHAT'S NEW IN THIS VERSION", "Chưa có mô tả thay đổi cho bản này." to "No release notes for this version.",
        "Cập nhật ngay" to "Update now",
        "Tần số" to "Frequency", "THIẾT BỊ NHẬN" to "RECEIVER", "KẾT NỐI & PHÁT LIÊN TỤC" to "LINK & CONTINUOUS PLAY",
        "Thiết bị tự giải mã luồng nhận được" to "The device decodes the stream itself", "Thiết bị tự kéo nhạc về và giải mã" to "The device pulls the music and decodes it itself",
        "⚠ EQ đang chỉnh — có thể méo nếu tăng cao" to "⚠ EQ is active — may distort if boosted high",
        "⚠ Máy khác đang điều khiển thiết bị này" to "⚠ Another device is controlling this receiver",
        // chung
        "Đóng" to "Close", "Huỷ" to "Cancel", "Bấm Back để đóng" to "Press Back to close", "Xoá" to "Delete", "Quét lại" to "Rescan",
        "Tất cả" to "All", "Bộ nhớ TV & USB" to "TV storage & USB", "Bộ nhớ TV" to "TV storage", "USB" to "USB",
        // rail
        "Đang phát" to "Now Playing", "Thư viện" to "Library", "Nguồn nhạc" to "Sources", "Đầu ra" to "Output", "Cài đặt" to "Settings",
        "Phổ tần" to "Spectrum", "Thoát" to "Exit",
        "Bộ nhớ máy" to "Device storage", "Thư viện" to "Library", "Đang phát" to "Now playing", "Cài đặt" to "Settings",
        "Danh sách" to "Queue", "Yêu thích" to "Favorite", "Đầu ra" to "Output", "Thoát ứng dụng" to "Quit app",
        "Chưa có bài nào đang phát. Mở Thư viện để chọn nhạc." to "Nothing is playing. Open the Library to pick music.",
        "Danh sách phát" to "Play queue", "Phát" to "Play", "Ngẫu nhiên" to "Shuffle",
        "Phổ tần số (Spectrogram)" to "Spectrogram", "Back để đóng" to "Press Back to close", "Dải động" to "Dynamic range",
        "Chưa có bài nào đang phát" to "Nothing is playing", "Hãy phát một bài rồi mở lại bảng này để xem phổ tần số." to "Play a track, then reopen this panel to see its spectrogram.",
        "Đang mở file…" to "Opening file…", "Không phân tích được file này" to "Could not analyze this file",
        "File DSD không có phổ tần kiểu Spek" to "DSD files have no Spek-style spectrogram",
        "• DSD là luồng 1-bit tốc độ hàng triệu mẫu/giây (DSD64 = 2,8224 MHz), không phải PCM nên không thể FFT trực tiếp như FLAC/WAV.\n• Nhiễu lượng tử của DSD được đẩy dồn lên dải siêu âm, nên biểu đồ sẽ chỉ thấy toàn nhiễu và không phản ánh chất lượng nhạc.\n• Khi phát, app chuyển DSD sang PCM qua bộ lọc; phổ lúc đó là kết quả của bộ lọc chứ không phải của file gốc."
            to "• DSD is a 1-bit stream at millions of samples per second (DSD64 = 2.8224 MHz), not PCM, so it cannot be FFT-analyzed directly like FLAC/WAV.\n• DSD quantization noise is pushed into the ultrasonic range, so the chart would show mostly noise and say nothing about the music.\n• During playback the app converts DSD to PCM through a filter; any spectrum would reflect that filter, not the original file.",
        "LIMITED EDITION" to "LIMITED EDITION", "NHÀ PHÁT TRIỂN" to "DEVELOPER",
        "Viết tặng Ngọc Nho và các anh em yêu âm thanh" to "Dedicated to Ngoc Nho and fellow audio lovers",
        "Dùng để cứu vãn vòng đời của chiếc TV Sony Bravia 2015 hiện đã bị sọc màn 🙏🙏🙏🤣" to "Built to extend the life of a 2015 Sony Bravia TV that now has a striped screen 🙏🙏🙏🤣",
        "Phân tích phổ tần số" to "Spectrum analyzer", "Dải hiển thị" to "Range", "Toàn dải" to "Full range", "Giữ đỉnh" to "Peak hold",
        "Chưa có bài nào đang phát — hãy phát một bài để xem phổ." to "Nothing is playing — start a track to see the spectrum.",
        "Đang stream ra thiết bị khác nên app không có dữ liệu âm thanh để phân tích." to "Streaming to another device, so the app has no audio data to analyze.",
        "Thoát GRAPEPlay?" to "Exit GRAPEPlay?", "Nhạc đang phát sẽ dừng và app sẽ đóng." to "Playback will stop and the app will close.", "Ở lại" to "Stay",
        "ĐANG PHÁT" to "NOW PLAYING", "NGUỒN NHẠC" to "SOURCES",
        // thư viện
        "Album" to "Albums", "Nghệ sĩ" to "Artists", "Thể loại" to "Genres", "Thư mục" to "Folders", "Bài hát" to "Songs",
        "+ Thêm nguồn" to "+ Add source", "Đầu ra âm thanh" to "Audio output", "Dừng quét" to "Stop scan", "Đang quét…" to "Scanning…",
        "Xem thư viện đã quét" to "View scanned library", "Duyệt thiết bị" to "Browse device",
        "GRAPEPlay cần quyền đọc bộ nhớ để tìm nhạc trên TV/USB." to "GRAPE2Play needs storage permission to find music on the TV/USB.",
        "Cấp quyền" to "Grant permission", "Hoặc bấm “+ Thêm nguồn” để dùng nhạc từ NAS." to "Or press “+ Add source” to use music from a NAS.",
        "Đang quét nhạc…" to "Scanning music…",
        "Không tìm thấy nhạc. Cắm USB rồi bấm “Quét lại”, hoặc thêm nguồn NAS." to "No music found. Plug in a USB drive and press “Rescan”, or add a NAS source.",
        "Tạm dừng" to "Paused", "Mở màn hình Đang phát ▸" to "Open Now Playing ▸", "Phát tuần tự" to "Play in order", "Phát ngẫu nhiên" to "Shuffle play",
        "Nhiều nghệ sĩ" to "Various artists", "Không rõ nghệ sĩ" to "Unknown artist", "Không rõ thể loại" to "Unknown genre",
        // đang phát
        "Chưa có bài nào đang phát" to "Nothing is playing", "Vào Thư viện để chọn nhạc" to "Open the Library to pick music",
        "Định dạng" to "Format", "Độ sâu bit" to "Bit depth", "Tần số lấy mẫu" to "Sample rate", "Kênh" to "Channels", "Nguồn" to "Source",
        "Ngẫu nhiên" to "Shuffle", "Tuần tự" to "In order", "Lặp một bài" to "Repeat one", "Lặp toàn bộ danh sách" to "Repeat all", "Không lặp" to "No repeat",
        "Danh sách phát" to "Queue", "Danh sách đang trống" to "The queue is empty", "Bộ nhớ đệm" to "Cache",
        "NÉN (LOSSY)" to "LOSSY", "Thiết bị" to "Device", "Người dùng" to "Users", "Thùng rác Windows" to "Windows Recycle Bin",
        // nguồn
        "Thêm nguồn nhạc" to "Add music source", "NGUỒN ĐÃ THÊM" to "ADDED SOURCES", "Chưa có NAS nào." to "No sources yet.",
        "Đang tìm thiết bị trên mạng…" to "Searching the network…", "Không tìm thấy thiết bị nào." to "No devices found.",
        "🔍 Tìm thiết bị trên mạng" to "🔍 Find devices on the network", "Tìm thiết bị trên mạng" to "Find devices on the network",
        "Tên hiển thị" to "Display name", "Địa chỉ máy chủ (IP hoặc tên)" to "Server address (IP or name)", "Thư mục chia sẻ (share)" to "Shared folder (share)",
        "Thư mục con (tuỳ chọn)" to "Subfolder (optional)", "Tài khoản (trống = khách)" to "Account (empty = guest)", "Tài khoản" to "Account", "Mật khẩu" to "Password",
        "Địa chỉ WebDAV, vd http://192.168.1.20:5005/music" to "WebDAV address, e.g. http://192.168.1.20:5005/music",
        "Bấm “Tìm thiết bị trên mạng” rồi chọn một máy chủ DLNA." to "Press “Find devices on the network” and pick a DLNA server.",
        "Đang kết nối…" to "Connecting…", "Đang xử lý…" to "Working…", "Kết nối & quét nhạc" to "Connect & scan music",
        // đám mây
        "Thử lại" to "Try again", "Bắt đầu đăng nhập bằng điện thoại" to "Sign in with your phone", "Đang chuẩn bị đăng nhập…" to "Preparing sign-in…",
        "Mã của bạn" to "Your code", "2. Nhập mã của bạn (hiện ở phía trên)" to "2. Enter your code (shown above)", "Đang chờ xác nhận trên điện thoại…" to "Waiting for confirmation on your phone…",
        "Chọn thư mục chứa nhạc" to "Choose the music folder", "Dùng thư mục này & quét nhạc" to "Use this folder & scan music", "◂ Lên một cấp" to "◂ Up one level",
        "Đang tải thư mục…" to "Loading folder…", "Lên một cấp" to "Up one level", "Dùng thư mục này & quét nhạc " to "Use this folder & scan music",
        "Dùng được cả tài khoản Gmail cá nhân và Google Workspace. Việc đăng nhập thực hiện trên điện thoại, mật khẩu không nhập trên TV." to
            "Works with personal Gmail and Google Workspace accounts. Sign-in happens on your phone; no password is typed on the TV.",
        "Dùng được cả OneDrive cá nhân và OneDrive doanh nghiệp / trường học (Microsoft 365). Việc đăng nhập thực hiện trên điện thoại, mật khẩu không nhập trên TV." to
            "Works with personal OneDrive and work/school OneDrive (Microsoft 365). Sign-in happens on your phone; no password is typed on the TV.",
        "Có thể thêm nhiều tài khoản — mỗi lần đăng nhập là một nguồn riêng." to "You can add several accounts — each sign-in becomes its own source.",
        // đầu ra
        "CHẾ ĐỘ PCM" to "PCM MODE", "Tương thích (PCM 16-bit)" to "Compatible (PCM 16-bit)",
        "Ổn định nhất — mọi TV đều ra tiếng. Hạ 24-bit xuống 16-bit." to "Most stable — works on every TV. Reduces 24-bit to 16-bit.",
        "Hi-res (PCM float 32-bit)" to "Hi-res (PCM float 32-bit)",
        "Giữ độ phân giải 24-bit. Nếu file hi-res im lặng thì quay lại chế độ Tương thích." to "Keeps 24-bit resolution. If hi-res files are silent, go back to Compatible mode.",
        "THIẾT BỊ ĐẦU RA" to "OUTPUT DEVICE", "Mặc định hệ thống (theo cài đặt âm thanh của TV)" to "System default (follows the TV's sound settings)",
        "Loa TV" to "TV speaker", "Tai nghe có dây" to "Wired headphones", "Digital / Quang (S/PDIF)" to "Digital / Optical (S/PDIF)",
        "STREAM KHÔNG DÂY" to "WIRELESS STREAMING", "Phát ra đầu ra của TV (quang / analogue / HDMI)" to "Play through the TV output (optical / analogue / HDMI)",
        "Đang tìm thiết bị…" to "Searching for devices…", "🔍 Tìm thiết bị stream (UPnP · Google Cast)" to "🔍 Find streaming devices (UPnP · Google Cast)",
        "Không thấy thiết bị nào. Kiểm tra TV và thiết bị cùng mạng Wi-Fi/LAN, rồi quét lại." to "No devices found. Make sure the TV and the device are on the same Wi-Fi/LAN, then scan again.",
        "Khi stream, TV gửi file gốc (không chuyển mã) cho thiết bị; âm lượng chỉnh trên thiết bị nhận. Thanh tăng/giảm trên TV không ảnh hưởng." to
            "When streaming, the TV sends the original file (no transcoding); volume is set on the receiving device. The TV's volume keys have no effect.",
        "Nếu TV nối ampli qua cổng quang/analogue, hãy chọn “Mặc định hệ thống” (chọn “Loa TV” sẽ ép ra loa TV). Lưu ý: trên TV Sony, việc chuyển giữa loa / cổng quang / headphone thường do mục Cài đặt ▸ Âm thanh ▸ Đầu ra âm thanh của TV quyết định. Danh sách trên là các đầu ra Android báo cho app." to
            "If the TV feeds an amplifier via optical/analogue, choose “System default” (choosing “TV speaker” forces the TV speakers). Note: on Sony TVs, switching between speakers / optical / headphones is usually decided by Settings ▸ Sound ▸ Audio output on the TV itself. The list above shows the outputs Android reports to the app.",
        // âm thanh / cài đặt
        "Âm thanh và hệ thống" to "Sound & system", "DSD, đầu ra và tinh chỉnh chỉ dùng cho Sony Bravia" to "DSD, output and tuning for your Sony Bravia",
        "PHÁT DSD" to "DSD PLAYBACK", "Chuyển DSD sang PCM" to "Convert DSD to PCM",
        "TV Android 8.0 không xuất DSD gốc: app giải mã DSF/DFF thành PCM 24-bit chất lượng cao rồi đưa ra cổng quang/analogue." to
            "Android 8.0 TVs cannot output native DSD: the app decodes DSF/DFF to high-quality 24-bit PCM and sends it to optical/analogue.",
        "BỘ CÂN BẰNG (EQ)" to "EQUALIZER (EQ)", "Bật bộ cân bằng" to "Enable equalizer", "Phẳng" to "Flat", "Ấm" to "Warm", "Giọng hát" to "Vocal", "Trầm+" to "Bass+",
        "Đặt lại" to "Reset", "Cân bằng trái / phải" to "Left / right balance", "Preamp (dB)" to "Preamp (dB)",
        "Dùng ◂ ▸ để chọn băng, ▴ ▾ để chỉnh ±0,5 dB." to "Use ◂ ▸ to pick a band, ▴ ▾ to adjust ±0.5 dB.",
        "Khi EQ phẳng và cân bằng ở giữa, âm thanh đi thẳng không qua xử lý (bit-perfect)." to "When the EQ is flat and balance is centred, audio passes through untouched (bit-perfect).",
        "NGÔN NGỮ" to "LANGUAGE", "Ngôn ngữ" to "Language",
        "Về ứng dụng" to "About", "Về ứng dụng (About)" to "About this app", "VỀ ỨNG DỤNG" to "ABOUT",
        "Áp dụng từ bài kế tiếp." to "Applies from the next track.", "khuyên dùng" to "recommended",
        "App được viết dựa trên ý tưởng tặng cho một chuyên gia âm thanh già rất mê âm thanh nhưng không có tiền để đầu tư thiết bị.\nDùng để cứu vãn vòng đời của chiếc TV Sony Bravia 2015 hiện đã bị sọc màn 🙏🙏🙏🤣" to
            "This app was written as a gift for an elderly audio expert who loves sound but has no money to invest in equipment.\nIt is meant to rescue the life of a 2015 Sony Bravia TV whose screen now has stripes 🙏🙏🙏🤣",
        "Trình phát nhạc hi-end cho Android TV" to "Hi-end music player for Android TV",
        "BIT-PERFECT" to "BIT-PERFECT", "✓ BIT-PERFECT" to "✓ BIT-PERFECT", "GẦN BIT-PERFECT" to "NEAR BIT-PERFECT", "BIT-PERFECT · DSD gốc, không chuyển mã" to "BIT-PERFECT · native DSD, not transcoded", "LOSSY" to "LOSSY",
        "nén mất dữ liệu" to "lossy compression", "EQ đang bật" to "EQ active", "DSD chuyển sang PCM" to "DSD converted to PCM", "chưa rõ codec" to "codec unknown",
        "KHI PHÁT TỚI THIẾT BỊ UPnP (WiiM, Network Player…)" to "WHEN PLAYING TO A UPnP DEVICE (WiiM, network player…)",
        "Gửi file DSD gốc" to "Send the original DSD file", "DoP (DSD64 → PCM 24-bit/176,4 kHz)" to "DoP (DSD64 → 24-bit/176.4 kHz PCM)",
        "DoP gói nguyên bit DSD64 vào PCM để thiết bị chuyển tiếp cho DAC ngoài tự giải mã DSD (WiiM Ultra không giải mã DSD gốc, chỉ chuyển sang PCM). Cần DAC hỗ trợ DoP, tắt EQ và để âm lượng thiết bị 100%. DAC không hỗ trợ DoP sẽ phát tiếng nhiễu rất to: hạ âm lượng trước khi thử. Chỉ áp dụng cho DSD64; DSD128 trở lên vẫn gửi file gốc. Áp dụng từ bài kế tiếp." to
            "DoP packs the DSD64 bits unchanged into PCM so the device can pass them to an external DAC that decodes DSD itself (the WiiM Ultra cannot decode native DSD; it only converts to PCM). Needs a DoP-capable DAC, EQ off and device volume at 100%. A DAC without DoP support will play loud noise: turn the volume down before trying. DSD64 only; DSD128 and above are still sent as the original file. Applies from the next track.",
        "BIT-PERFECT · DoP (DSD64 trong PCM 24-bit/176,4 kHz)" to "BIT-PERFECT · DoP (DSD64 in 24-bit/176.4 kHz PCM)",
        "Chọn nơi chứa nhạc của bạn" to "Choose where your music lives", "Bộ nhớ máy & USB" to "Device storage & USB", "Tự động thêm" to "Added automatically", "Synology, QNAP, thư mục chia sẻ Windows" to "Synology, QNAP, Windows shared folders", "Nextcloud, Synology WebDAV, máy chủ web" to "Nextcloud, Synology WebDAV, web servers", "Minimserver, Plex, Jellyfin…" to "MinimServer, Plex, Jellyfin…", "Cá nhân hoặc Workspace" to "Personal or Workspace", "Cá nhân hoặc Microsoft 365" to "Personal or Microsoft 365", "ĐANG CHỌN" to "SELECTED", "ĐÃ THÊM" to "ADDED", "ĐANG DÙNG" to "IN USE", "Nhạc trong bộ nhớ TV và USB được quét tự động mỗi khi mở app. Cắm USB vào là phát được ngay." to "Music on the TV storage and USB drives is scanned automatically whenever the app opens. Plug in a USB drive and play right away.", "Nhạc trong bộ nhớ máy và USB được quét tự động mỗi khi mở app." to "Music on the device storage and USB drives is scanned automatically whenever the app opens.", "Tìm thiết bị trên mạng" to "Find devices on the network", "Đang tìm…" to "Searching…", "Đang xử lý…" to "Working…", "1 ĐĂNG NHẬP" to "1 SIGN IN", "2 CHỌN THƯ MỤC" to "2 CHOOSE FOLDER", "3 QUÉT THƯ VIỆN" to "3 SCAN LIBRARY", "Mã ghép nối" to "Pairing code", "Tạo mã mới" to "New code", "TRÊN TV NÀY" to "ON THIS TV", "TRÊN MÁY NÀY" to "ON THIS DEVICE", "KHÔNG DÂY — NETWORK PLAYER" to "WIRELESS — NETWORK PLAYER", "Theo cài đặt âm thanh của TV" to "Follows the TV sound settings", "Theo cài đặt âm thanh của máy" to "Follows the device sound settings", "Đầu ra do Android báo" to "Output reported by Android", "Phát trên TV hoặc stream tới Network Player" to "Play on the TV or stream to a network player", "Phát trên máy hoặc stream tới Network Player" to "Play on this device or stream to a network player", "THIẾT BỊ ĐANG CHỌN" to "SELECTED DEVICE", "Giao thức" to "Protocol", "Địa chỉ" to "Address", "Âm lượng" to "Volume", "Chỉnh trên thiết bị nhận" to "Adjust on the receiving device", "Không hỗ trợ" to "Not supported", "DSD64 qua DoP (24-bit/176,4 kHz)" to "DSD64 via DoP (24-bit/176.4 kHz)", "Đường tín hiệu" to "Signal path", "KHI MỞ APP" to "WHEN THE APP OPENS", "Giữ lựa chọn lần dùng trước" to "Keep the last used output", "Quét lại thiết bị" to "Rescan devices", "Đang quét…" to "Scanning…", "Dùng đầu ra này" to "Use this output", "Đang dùng đầu ra này" to "Using this output", "Đang tìm thiết bị…" to "Searching for devices…", "Cổ điển" to "Classic", "Toàn ảnh" to "Full art", "Nền màu" to "Color", "Cổ điển, Toàn ảnh và Nền màu lấy cảm hứng từ Symfonium, dùng được trên điện thoại, máy tính bảng và TV. TV: OK = phát/dừng · Menu = yêu thích · Back = về Thư viện." to "Classic, Full art and Color are inspired by Symfonium and work on phones, tablets and TVs. TV: OK = play/pause · Menu = favorite · Back = Library.", "Chưa có bài nào đang phát" to "Nothing is playing", "Vào Thư viện để chọn nhạc" to "Open the Library to pick music", "Danh sách" to "Queue", "File gốc được gửi nguyên bản (không chuyển mã) qua Wi-Fi/LAN; thiết bị nhận tự giải mã. Thanh âm lượng của TV không ảnh hưởng." to "The original file is sent unchanged (no transcoding) over Wi-Fi/LAN; the receiving device decodes it. The TV volume has no effect.", "File gốc được gửi nguyên bản (không chuyển mã) qua Wi-Fi/LAN; thiết bị nhận tự giải mã. Thanh âm lượng của máy không ảnh hưởng." to "The original file is sent unchanged (no transcoding) over Wi-Fi/LAN; the receiving device decodes it. This device's volume has no effect.", "Tương thích (PCM 16-bit)" to "Compatible (16-bit PCM)", "Hi-res (PCM float 32-bit)" to "Hi-res (32-bit float PCM)", "Ổn định nhất — mọi thiết bị đều ra tiếng. Hạ 24-bit xuống 16-bit." to "Most stable — every device plays. Reduces 24-bit to 16-bit.", "Giữ độ phân giải 24-bit. Nếu file hi-res im lặng thì quay lại Tương thích." to "Keeps 24-bit resolution. If hi-res files are silent, go back to Compatible.", "Nếu thiết bị chưa cắm, app tạm dùng mặc định hệ thống và tự chuyển khi cắm." to "If the device is not plugged in, the app temporarily uses the system default and switches when it is plugged in.",
        "HIỂN THỊ" to "DISPLAY", "Tự động" to "Auto", "Chữ và giao diện hơi nhỏ trên TV Full HD? Chọn FHD để phóng to." to "Text too small on a Full HD TV? Choose FHD to enlarge.",
        "Đây là phiên bản giới hạn, sản xuất riêng cho Ngọc Nho và các anh em yêu âm thanh." to "This is a limited edition made especially for Ngọc Nho and fellow audio lovers.",
        "Phiên bản giới hạn" to "Limited Edition", "Limited Edition" to "Limited Edition",
        "Limited Edition, viết tặng cho Ngọc Nho và các anh em yêu âm thanh" to "Limited Edition, written as a gift for Ngọc Nho and fellow audio lovers",
        "Nhà phát triển: Phan Kim Phụng" to "Developer: Phan Kim Phụng", "Email: phankimphung@gmail.com" to "Email: phankimphung@gmail.com",
        "Phiên bản" to "Version", "HI-END PLAYER" to "HI-END PLAYER",
        // thông báo trạng thái
        "Đang quét bộ nhớ TV & USB…" to "Scanning TV storage & USB…",
        "chưa phát" to "not playing", "Đã dừng vì nhiều file liên tiếp bị lỗi." to "Stopped because several files in a row failed.",
        // bố cục Đang phát
        "BỐ CỤC MÀN ĐANG PHÁT" to "NOW PLAYING LAYOUT", "Mặc định" to "Default", "Tối giản" to "Minimal", "Đĩa than" to "Vinyl", "Dữ liệu" to "Data-rich", "Tranh" to "Gallery", "Rạp tối" to "Noir theatre",
        "Bố cục toàn màn hình: Back = về Thư viện · OK = phát/dừng · ◂ ▸ = bài trước/sau (Rạp tối: tua ±10 giây) · Menu = yêu thích · Tranh: ▴ đổi khung." to "Full-screen layouts: Back = Library · OK = play/pause · ◂ ▸ = previous/next (Noir theatre: seek ±10 s) · Menu = favorite · Gallery: ▴ changes the frame.",
        "ĐANG PHÁT" to "NOW PLAYING", "● ĐANG PHÁT" to "● PLAYING", "❚❚ TẠM DỪNG" to "❚❚ PAUSED", "MẶT A · 33⅓ RPM" to "SIDE A · 33⅓ RPM",
        "ĐƯỜNG TÍN HIỆU" to "SIGNAL PATH", "THÔNG TIN" to "INFO", "TIẾP THEO" to "UP NEXT", "▴ ĐỔI KHUNG TRANH" to "▴ CHANGE FRAME",
        "HÀNG ĐỢI · ◂ ▸ TUA ±10 GIÂY · ▾ CHỌN BÀI" to "QUEUE · ◂ ▸ SEEK ±10 S · ▾ PICK TRACK",
        "Chưa có bài nào đang phát" to "Nothing is playing",
        // chạy ngầm / ảnh bìa mạng
        "✓ Đang bật chạy ngầm" to "✓ Background running is on", "Chưa bật chạy ngầm" to "Background running is off",
        "Giữ app chạy nền bằng dịch vụ phát nhạc (có biểu tượng thông báo) để TV không ngắt nhạc khi bạn thoát ra màn hình chính hoặc dùng app khác." to "Keeps the app alive with a music service (shows a notification icon) so the TV does not cut the music when you go home or use another app.",
        "Đã bật dịch vụ chạy nền. TV này không có màn hình cấp quyền tiết kiệm pin để mở — không sao, không bắt buộc." to "Background service is on. This TV has no battery-optimization screen to open — that is fine, it is not required.",
        "Đã bật dịch vụ chạy nền." to "Background service is on.", "Đã tắt chạy ngầm." to "Background running is off.",
        "✓ Đã miễn tối ưu pin cho app" to "✓ Battery optimization is exempted for the app", "Miễn tối ưu pin: chưa cấp (không bắt buộc trên TV)" to "Battery optimization exemption: not granted (optional on TV)",
        "ẢNH BÌA TRÊN MẠNG" to "ONLINE COVER ART",
        "Album/bài hát không có ảnh sẽ được tìm trên Deezer và iTunes theo tên nghệ sĩ + album (chỉ gửi tên, không gửi file nhạc). Ảnh tìm được lưu lại trong máy, không tải lại." to "Albums/tracks without artwork are looked up on Deezer and iTunes by artist + album name (only names are sent, never your music files). Found images are saved on the device.",
        // yêu thích
        "Yêu thích" to "Favorites", "Chưa có bài hát yêu thích" to "No favorite tracks yet",
        "Giữ nút OK trên một bài hát (hoặc bấm ♥ ở màn Đang phát) để thêm vào danh sách yêu thích." to "Hold OK on a track (or press ♥ on the Now Playing screen) to add it to your favorites.",
        // giao diện
        "GIAO DIỆN" to "THEME", "Nho tím" to "Grape", "Xanh đêm" to "Midnight", "Rừng xanh" to "Forest", "Hoàng hôn" to "Sunset", "Đen OLED" to "OLED black", "Sáng ngà" to "Ivory",
        "Đổi màu toàn bộ ứng dụng ngay lập tức." to "Changes the colors of the whole app instantly.",
        // chạy ngầm
        "CHẠY NGẦM" to "BACKGROUND", "✓ Đã cho phép chạy ngầm" to "✓ Background playback allowed", "Chưa cho phép chạy ngầm" to "Background playback not allowed",
        "Cho phép để nhạc không bị TV ngắt khi bạn thoát ra màn hình chính hoặc dùng app khác." to "Allow so the TV does not cut the music when you go to the home screen or use another app.",
        "Cấp quyền chạy ngầm" to "Allow background playback", "Cho phép GRAPEPlay chạy ngầm" to "Allow GRAPEPlay to run in the background",
        "Để nhạc không bị ngắt khi bạn thoát ra màn hình chính, chuyển sang app khác hoặc khi TV dọn bộ nhớ, app cần quyền chạy ngầm. TV sẽ hiện hộp thoại xác nhận — hãy chọn “Cho phép” (Allow)." to "To keep the music going when you leave to the home screen, switch apps or the TV frees memory, the app needs background permission. The TV will show a confirmation dialog — choose “Allow”.",
        "Có thể đổi lại bất cứ lúc nào ở Cài đặt ▸ Chạy ngầm." to "You can change this any time in Settings ▸ Background.",
        "Để sau" to "Later", "Cho phép" to "Allow",
        // trang chủ TV
        "TRANG CHỦ TV" to "TV HOME", "Hiện nhanh trên màn hình chính của TV" to "Quick view on the TV home screen",
        "Thêm hàng “GRAPEPlay” lên màn hình chính Android TV: bài yêu thích và nhạc phát gần đây kèm ảnh bìa, bấm vào là phát ngay." to "Adds a “GRAPEPlay” row to the Android TV home screen: favorite tracks and recently played music with cover art; select one to play right away.",
        "Bật" to "On", "Tắt" to "Off",
        "Nếu chưa thấy hàng này, vào Tuỳ chỉnh kênh trên màn hình chính của TV rồi bật “GRAPEPlay”." to "If you do not see the row, open Customize channels on the TV home screen and turn on “GRAPEPlay”.",
        // đầu ra mặc định
        "ĐẦU RA MẶC ĐỊNH KHI MỞ APP" to "DEFAULT OUTPUT AT APP START", "Giữ lựa chọn lần dùng trước" to "Keep the last used output",
        "Luôn bắt đầu bằng Mặc định hệ thống" to "Always start with System default",
        "Mỗi lần mở app sẽ tự dùng đầu ra này. Nếu thiết bị chưa cắm, app tạm dùng mặc định hệ thống và tự chuyển sang khi thiết bị được cắm." to "This output is used every time the app starts. If the device is not connected, the app uses the system default and switches over when the device is plugged in.",
        // cảnh báo EQ
        "⚠ EQ đang chỉnh — âm thanh có thể bị méo nếu tăng quá cao" to "⚠ EQ is active — sound may distort if boosted too high",
        "⚠ Nguy cơ méo tiếng (cắt đỉnh / clipping)" to "⚠ Risk of distortion (clipping)",
        "⚠ Chỉnh EQ làm thay đổi tín hiệu gốc" to "⚠ EQ changes the original signal",
        "Không còn bit-perfect. Tăng nhiều ở dải trầm/cao có thể làm méo tiếng — nên chỉnh nhẹ và để Preamp âm." to "No longer bit-perfect. Large boosts in the bass/treble can distort — adjust gently and keep Preamp negative.",
        "Lưu ý: chỉnh EQ có thể làm méo tiếng, nhất là khi tăng cao." to "Note: adjusting the EQ can cause distortion, especially with large boosts.",
        "Đang chạy ngầm" to "Running in the background", "Dừng" to "Stop", "Đang phát nhạc" to "Playing music",
        // thư viện / chống lưu ảnh
        "NGUỒN HIỂN THỊ Ở THƯ VIỆN" to "SOURCES SHOWN IN THE LIBRARY", "⚙ Nguồn hiển thị" to "⚙ Visible sources", "Nguồn hiển thị" to "Visible sources",
        "Bấm vào từng nguồn để hiện hoặc ẩn. Nguồn bị ẩn không xuất hiện trong Thư viện và không lẫn vào mục “Tất cả”." to "Tap a source to show or hide it. Hidden sources do not appear in the Library or in “All”.",
        "● Hiện" to "● Shown", "○ Ẩn" to "○ Hidden", "Tất cả ổ & nguồn" to "All drives & sources",
        "CHỐNG LƯU ẢNH MÀN HÌNH OLED" to "OLED BURN-IN PROTECTION",
        "Dịch nhẹ toàn bộ giao diện vài điểm ảnh mỗi phút để các chi tiết cố định (thanh bên, logo, viền) không nằm yên một chỗ, và tự giảm sáng sau 5 phút không thao tác. Bấm phím hoặc chạm màn hình là sáng lại." to "Shifts the whole interface by a few pixels every minute so fixed details (sidebar, logo, borders) never sit still, and dims after 5 minutes without input. Press a key or touch the screen to wake it.",
        "Đổi chế độ khi đang phát: bài hiện tại tự nạp lại ngay theo chế độ mới." to "Changing the mode while playing reloads the current track right away.",
        "Chưa thấy bản phát hành hợp lệ trên GitHub (cần Release công khai, có đính kèm file .apk)." to "No valid release found on GitHub (it must be a public Release with an attached .apk file).",
        // cập nhật ứng dụng
        "CẬP NHẬT ỨNG DỤNG" to "APP UPDATE", "Chưa kiểm tra cập nhật." to "Not checked for updates yet.",
        "Đang kiểm tra cập nhật…" to "Checking for updates…", "Bạn đang dùng bản mới nhất." to "You are on the latest version.",
        "Đã tải xong, sẵn sàng cài đặt." to "Downloaded, ready to install.", "Kiểm tra cập nhật" to "Check for updates",
        "Tải và cài đặt" to "Download and install", "Cài đặt ngay" to "Install now",
        "Chưa có file cập nhật, hãy tải lại." to "No update file yet, please download again.",
        "Hãy bật “Cho phép từ nguồn này” cho GRAPEPlay, quay lại rồi bấm Cài đặt ngay." to "Turn on “Allow from this source” for GRAPEPlay, come back and tap Install now.",
        "TV chưa cho phép cài từ nguồn ngoài. Vào Cài đặt TV ▸ Bảo mật và hạn chế ▸ Nguồn không xác định, bật cho GRAPEPlay." to "The TV does not allow installs from outside sources. Go to TV Settings ▸ Security & restrictions ▸ Unknown sources and enable GRAPEPlay.",
        "Cập nhật được tải từ trang phát hành GitHub của GRAPEPlay. Bản mới sẽ cài đè, không mất nguồn nhạc và cài đặt." to "Updates are downloaded from the GRAPEPlay GitHub releases page. The new version installs over the old one and keeps your sources and settings.",
    )

    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("Có phiên bản mới (.+)") to "New version $1 available",
        Regex("Bạn đang dùng bản (.+?) · dung lượng (.+)") to "You are using version $1 · size $2",
        Regex("Bạn đang dùng bản (.+)") to "You are using version $1",
        Regex("•  (.+)") to "•  $1",
        Regex("Email nhà phát triển: (.+)") to "Developer email: $1",
        Regex("Phản hồi: (.+)") to "Response: $1",
        Regex("Trạng thái: (.+)") to "Status: $1",
        Regex("Bài kế: (.+)") to "Next: $1",
        Regex("Chuyển bài: (\\d+) s trước") to "Last switch: $1 s ago",
        Regex("Chuyển bài: (.+)") to "Last switch: $1",
        Regex("Nguồn: trực tiếp") to "Source: direct",
        Regex("Nguồn: kéo qua TV") to "Source: pulled via TV",
        Regex("Mất kết nối: (\\d+) lần") to "Dropouts: $1",
        Regex("Đầu ra (.+) không nhận PCM (.+) — đã tự hạ xuống (.+) và phát lại\\.") to "Output $1 does not accept PCM $2 — automatically lowered to $3 and restarted.",
        Regex("Đầu ra (.+) không nhận được tín hiệu của bài này\\..*") to "Output $1 cannot take this track's signal. Choose another track or output (the “Output” button at the top) — playback restarts right away.",
        Regex("(.+) đang được một thiết bị khác điều khiển\\..*") to "$1 is being controlled by another device. To avoid fighting over it, this app stopped sending commands. Press Play to take control again from here.",
        Regex("Đang dùng bản: (.+)") to "Current version: $1",
        Regex("Có bản mới: (.+)") to "New version available: $1",
        Regex("Bản mới nhất trên GitHub: (.+)") to "Latest version on GitHub: $1",
        Regex("Đang tải… (\\d+)%") to "Downloading… $1%",
        Regex("Lỗi: (.+)") to "Error: $1",
        Regex("Không mở được trình cài đặt: (.+)") to "Could not open the installer: $1",
        Regex("(\\d+) bài") to "$1 tracks",
        Regex("(\\d+) bài · (.+)") to "$1 tracks · $2",
        Regex("(\\d+) album · (\\d+) bài") to "$1 albums · $2 tracks",
        Regex("(\\d+) thiết bị") to "$1 devices",
        Regex("Đầu ra · (.+?)\\s+▾") to "Output · $1  ▾",
        Regex("Album: (.+)") to "Album: $1",
        Regex("Phiên bản (.+)") to "Version $1",
        Regex("GẦN BIT-PERFECT · hạ (\\d+)→16-bit") to "NEAR BIT-PERFECT · reduced $1→16-bit",
        Regex("Liên hệ: (.+)") to "Contact: $1",
        Regex("Tín hiệu: (.+)") to "Signal: $1",
        Regex("Đang đưa ra: (.+)") to "Now outputting: $1",
        Regex("Đang stream tới: (.+)") to "Streaming to: $1",
        Regex("Đăng nhập (.+)") to "Sign in to $1",
        Regex("Vị trí: (.+)") to "Location: $1",
        Regex("Toàn bộ Drive của tôi") to "All of My Drive",
        Regex("Toàn bộ OneDrive") to "All of OneDrive",
        Regex("Loại tài khoản: cá nhân") to "Account type: personal",
        Regex("Loại tài khoản: (.+)") to "Account type: work / other",
        Regex("Đã đăng nhập: (.+)") to "Signed in: $1",
        Regex("Máy chủ: (.+)") to "Server: $1",
        Regex("Tìm thấy (\\d+) thiết bị — chọn một thiết bị để điền sẵn\\.") to "Found $1 devices — pick one to pre-fill.",
        Regex("Không kết nối được: (.+)") to "Could not connect: $1",
        Regex("Đang quét… (\\d+) bài") to "Scanning… $1 tracks",
        Regex("Đang quét (.+?)…") to "Scanning $1…",
        Regex("Đã quét: (.+)") to "Scanned: $1",
        Regex("Đã quét (.+?): (\\d+) bài(.*)") to "Scanned $1: $2 tracks$3",
        Regex("Đang liệt kê (.+?): (\\d+) file nhạc · (\\d+) thư mục") to "Listing $1: $2 music files · $3 folders",
        Regex("Đọc thẻ nhạc (.+?): (.+)") to "Reading tags $1: $2",
        Regex("Lỗi quét (.+?): (.+)") to "Scan error $1: $2",
        Regex("⚠ Chưa cấu hình Client ID cho (.+)\\. Mở OAuthConfig\\.kt và làm theo README \\(mục “Đăng nhập đám mây”\\)\\.") to "⚠ No Client ID configured for $1. Open OAuthConfig.kt and follow the README (section “Cloud sign-in”).",
        Regex("Thiết bị loại (\\d+)") to "Device type $1",
        Regex("(\\d+) kênh") to "$1 channels",
        Regex("(\\d+) bài yêu thích") to "$1 favorite tracks",
        Regex("(\\d+) bài yêu thích · giữ OK để bỏ yêu thích") to "$1 favorite tracks · hold OK to remove",
        Regex("Kết nối (.+)") to "Connect $1",
        Regex("Đăng nhập (.+)") to "Sign in to $1",
        Regex("(\\d+) bài trong thư viện") to "$1 tracks in the library",
        Regex("PCM tối đa (.+)") to "PCM up to $1",
        Regex("Luôn bắt đầu bằng (.+)") to "Always start with $1",
        Regex("(.+) \\(chưa kết nối\\)") to "$1 (not connected)",
        Regex("Đỉnh EQ đang tăng (.+) dB — giảm Preamp xuống ≤ (.+) dB hoặc giảm các băng đang tăng\\.") to "EQ peak boost is $1 dB — lower Preamp to ≤ $2 dB or reduce the boosted bands.",
    )
}
