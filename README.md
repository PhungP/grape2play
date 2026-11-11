# GRAPEPlay

Build: mở thư mục bằng Android Studio (Koala+) → Sync → Run / Build APK. Package: `com.grape.play` (đổi tên từ NHOPlay, cài như app mới).

## Bản 0.2.1 — sửa lỗi FLAC
Nguyên nhân: Android 8.0 không có decoder FLAC trong MediaCodec (chỉ có từ Android 8.1/10), nên ExoPlayer đọc được header FLAC nhưng không giải mã được.
Cách sửa: `FlacPcm.java` giải mã FLAC bằng phần mềm (thuần Java, hỗ trợ 8–32 bit, mono → đa kênh, mọi tần số), xuất ra luồng WAV PCM ảo để ExoPlayer phát.
- Áp dụng cho mọi nguồn: bộ nhớ TV, USB, SMB, WebDAV, DLNA.
- Tua được (lưu điểm đặt trong lúc giải mã).
- Đọc thẻ (tiêu đề, nghệ sĩ, album, thể loại, số bài), thông số thật và ảnh bìa nhúng trực tiếp từ header FLAC.
- Đã test bit-exact với ffmpeg: 16/44.1, 24/96, 24/192, mono, 5.1, nén mức 0/5/8, kèm 60 phép tua ngẫu nhiên mỗi file.
- Thư viện được quét lại một lần (đổi tên file cache sang library_v4.json) để nhận thẻ FLAC mới.

Giai đoạn 2 (NAS: SMB, WebDAV, UPnP/DLNA) giữ nguyên.

## Bản 0.2.2
Icon mới: chùm nho cách điệu kèm tam giác play (`drawable/ic_launcher.xml` dạng vector; banner TV `drawable-nodpi/banner.png` 640x360 có chữ GRAPEPlay).

## Bản 0.2.3
Sửa lỗi biên dịch: thiếu khai báo `Playback.outName` (tên thiết bị đầu ra hiển thị ở màn Đang phát).

## Bản 0.3.0 — Giai đoạn 3: Google Drive & OneDrive, sửa lỗi UPnP/DLNA

### Sửa lỗi UPnP/DLNA (WiiM Ultra "cứ báo đang quét")
Nguyên nhân khả dĩ nhất (chưa thử được trên máy thật): bản cũ quét đệ quy *toàn bộ* máy chủ trước khi hiện bài nào, đi cả vào Ảnh/Video và các view trùng lặp (Album/Artist/Genre…), mỗi lần Browse chờ tới 30 giây, một thư mục lỗi làm hỏng cả lần quét.
- Quét không đệ quy, bỏ Ảnh/Video, ưu tiên cây "Browse Folders" nếu có, chống lặp thư mục/bài, giới hạn 12 phút / 5000 thư mục / 30000 bài.
- Một thư mục lỗi chỉ bị bỏ qua và được báo ở cuối; Browse có timeout 20 giây, thử lại, tự hạ số lượng mỗi trang.
- Tiến độ hiện thật: "Đang quét WiiM Ultra: 120 bài · 14 thư mục · Tên thư mục".
- Nút **Dừng quét** (giữ lại những bài đã tìm được).
- **Duyệt trực tiếp**: bấm tên thiết bị DLNA ở thanh nguồn sẽ mở ngay cây thư mục (vào được từng thư mục, phát luôn, không cần quét). Lỗi (nếu có) hiện nguyên văn kèm nút Thử lại — gửi lại dòng lỗi đó nếu vẫn không mở được.

### Google Drive & OneDrive (cá nhân + doanh nghiệp)
Chọn tab **Google Drive** / **OneDrive** trong "Thêm nguồn" → quét mã QR bằng điện thoại → chọn thư mục → app quét và phát (FLAC giải mã bằng phần mềm như các nguồn khác; tua được).
Cần tạo Client ID của chính mày (miễn phí) rồi điền vào `OAuthConfig.kt`:

**OneDrive (Microsoft)** — dùng "device code": nhập mã trên điện thoại.
1. portal.azure.com ▸ Microsoft Entra ID ▸ App registrations ▸ New registration.
2. Supported account types: *Accounts in any organizational directory and personal Microsoft accounts*.
3. Authentication ▸ bật **Allow public client flows = Yes**.
4. API permissions: Microsoft Graph ▸ Delegated ▸ `Files.Read`, `User.Read` (offline_access tự thêm).
5. Copy *Application (client) ID* vào `MS_CLIENT_ID`.
Tài khoản trường/công ty: nếu tổ chức chặn app ngoài, cần quản trị viên cho phép.

**Google Drive** — Google không cho "device flow" đọc file có sẵn trong Drive (chỉ cho quyền `drive.file`), nên app dùng ghép nối qua điện thoại: TV mở trang web nhỏ trong mạng LAN, điện thoại đăng nhập Google, trình duyệt dừng ở địa chỉ `127.0.0.1?code=…` (bình thường), dán địa chỉ đó vào trang của TV. Không cần máy chủ trung gian.
1. console.cloud.google.com ▸ tạo project ▸ APIs & Services ▸ bật **Google Drive API**.
2. OAuth consent screen: loại External, thêm scope `.../auth/drive.readonly`. **Đặt Publishing status = In production** (nếu để Testing, refresh token hết hạn sau 7 ngày). Google sẽ hiện cảnh báo "chưa xác minh" khi đăng nhập: Nâng cao ▸ Tiếp tục (app dùng cá nhân, dưới 100 người thì không cần xác minh).
3. Credentials ▸ Create OAuth client ID ▸ loại **Desktop app**. Copy Client ID và Client secret vào `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`.

Lưu ý: refresh token lưu trong SharedPreferences của app (giống mật khẩu NAS hiện tại).

## Bản 0.4.0 — Giai đoạn 4: Stream không dây + sửa giao diện
- **Stream tới WiiM Ultra / thiết bị UPnP (AVTransport) và Google Cast**: mở "Đầu ra âm thanh" ▸ mục STREAM KHÔNG DÂY ▸ "Tìm thiết bị stream" ▸ chọn thiết bị. Bài đang nghe được chuyển sang thiết bị, giữ vị trí; chọn "Phát ra đầu ra của TV" để quay lại.
- TV chạy máy chủ HTTP nhỏ (cổng 8899–8920) phát **file gốc** (không chuyển mã, có Range) cho thiết bị kéo về; nguồn USB/SMB/WebDAV/Drive/OneDrive đều đi qua đó. Bài đã là URL http (DLNA, ổ USB của WiiM) được đưa thẳng cho thiết bị.
- Tự chuyển bài, ngẫu nhiên, lặp lại vẫn do app điều khiển (ExoPlayer chỉ làm bộ sắp thứ tự). Chưa hỗ trợ gapless khi stream; âm lượng chỉnh ở thiết bị nhận.
- Cast: tự cài đặt giao thức CASTV2, dùng Default Media Receiver; Cast thường không phát được DSD/ALAC tuỳ thiết bị.
- Sửa: hàng chọn nguồn hiển thị đủ OneDrive (2 hàng); **độ sâu bit** và bitrate đọc từ header FLAC/WAV; logo con cáo + chùm nho trong app, icon launcher, banner TV; tên app **GRAPE2Play**.
- Chưa biên dịch trong môi trường soạn mã — nếu Gradle báo lỗi, gửi lại log.

## Bản 0.5.0 — Giai đoạn 5 + hoàn thiện
- **Giao diện mới** theo bản thiết kế: thanh điều hướng icon mỏng bên trái (Đang phát · Thư viện · Nguồn nhạc · Đầu ra · Âm thanh), nền tím than, lưới album có nhãn định dạng (FLAC 24/96, DSD64…), bìa tạo sẵn khi file không có ảnh bìa, ô thông số có viền.
- **Tiếng Việt / English**: Cài đặt ▸ NGÔN NGỮ. Chữ cố định và các thông báo thường gặp được dịch; tên bài/nghệ sĩ và thông báo lỗi kỹ thuật giữ nguyên.
- **Nhiều tài khoản đám mây**: thêm bao nhiêu tài khoản Google Drive / OneDrive tuỳ ý (Thêm nguồn ▸ đăng nhập lần nữa bằng tài khoản khác). Mỗi tài khoản là một nguồn riêng, hiện thành một nút lọc trong Thư viện và quét/duyệt độc lập. Đăng nhập lại cùng một tài khoản sẽ cập nhật nguồn cũ. Google luôn hiện màn hình chọn tài khoản.
- **4K**: giao diện co giãn theo màn hình thật (720p / 1080p / 4K) từ canvas 1920×1080; ảnh bìa giải mã tới 720 px.
- **DSD (DSF / DFF)**: giải mã phần mềm → PCM 24-bit (44.1 / 88.2 / 176.4 kHz, chọn ở Cài đặt ▸ PHÁT DSD). Lọc FIR Blackman, giảm mẫu theo bảng tra. Không có DoP / DSD gốc vì Android 8 không hỗ trợ ổn định; DFF nén DST chưa hỗ trợ.
- **EQ 10 băng + cân bằng trái/phải + preamp** (Âm thanh). Khi EQ phẳng và cân bằng ở giữa, âm thanh đi thẳng không xử lý. Điều khiển bằng remote: ◂ ▸ chọn băng, bấm OK rồi ▴ ▾ chỉnh.
- Chọn **đầu ra quang / analogue / HDMI**: Đầu ra âm thanh (đã có từ 0.2). Cổng quang S/PDIF thường chỉ nhận ≤ 48 kHz — nếu mất tiếng hãy chọn DSD 44.1 kHz và chế độ PCM Tương thích.
- **Về ứng dụng** (Cài đặt ▸ Về ứng dụng).
- Microsoft Application (client) ID đã điền sẵn trong OAuthConfig.kt. Trong Azure cần bật “Allow public client flows” và loại tài khoản “cá nhân + cơ quan/trường học”; quyền Files.Read, User.Read, offline_access.
- Chưa có: gapless, ReplayGain, DoP. Chưa biên dịch trong môi trường soạn mã — nếu Gradle báo lỗi, gửi lại log.

## Bản 0.5.1
- Cài đặt ▸ HIỂN THỊ: Tự động / FHD (chữ to hơn, mặc định cho TV Full HD) / 4K (gọn, nhiều nội dung).
- Màn Đang phát: dòng tín hiệu có nhãn BIT-PERFECT — xanh lá = đạt (lossless, không EQ, không hạ bit), vàng = gần đạt (EQ bật, hạ 24→16-bit, DSD→PCM, chưa rõ codec), đỏ = nén mất dữ liệu (MP3/AAC/OGG/Opus). Chỉ đánh giá phần app kiểm soát; TV/hệ điều hành vẫn có thể đổi tần số lấy mẫu ở tầng dưới.
- About: thêm dòng phiên bản giới hạn cho Ngọc Nho và liên hệ phankimphung@gmail.com.

## Bản 0.5.2
- FHD: canvas 1560×878 (chữ to hơn ~23% so với 1080p gốc); ô thông số bài hát cho phép 2 dòng, không còn bị cắt "Stereo 2…" / "Google…".
- About: bố cục 2 cột, đủ lời giới thiệu, dòng "Phiên bản giới hạn, viết tặng cho Ngọc Nho và các anh em yêu âm thanh", nhà phát triển Phan Kim Phụng và email.
- Dấu "Phiên bản giới hạn" (chữ serif nghiêng vàng chuyển sắc) ở góc dưới thanh điều hướng trái.

## Bản 0.6.2
- **Chạy ngầm**: nút Bật/Tắt ở Cài đặt ▸ Chạy ngầm có hiệu lực ngay (giữ dịch vụ nền luôn chạy, trạng thái sáng "✓ Đang bật chạy ngầm"); xin thêm quyền thông báo / miễn tối ưu pin nếu TV có màn hình đó, nếu không thì báo rõ.
- Huy hiệu **Limited Edition** giữ nguyên tiếng Anh ở cả tiếng Việt và English; menu "Âm thanh" đổi thành **Cài đặt**.
- **Ảnh bìa trên mạng**: album/bài không có ảnh tự tìm trên Deezer (dự phòng iTunes) theo nghệ sĩ + album, lưu trong filesDir/webcovers; tắt được ở Cài đặt ▸ Ảnh bìa trên mạng.

## Bản 0.6.1 — 5 bố cục màn Đang phát (theo 5 concept thiết kế)
Cài đặt ▸ Bố cục màn Đang phát: Mặc định · Tối giản (Purist) · Đĩa than (Vinyl) · Dữ liệu (Roon) · Tranh (Gallery) · Rạp tối (Noir Theatre). Toàn màn hình; Back = về Thư viện, OK = phát/dừng, ◂ ▸ = bài trước/sau (Rạp tối: tua ±10 giây), Menu = yêu thích; Tranh: ▴ đổi khung (trắng / gỗ / không viền), thông tin tự ẩn sau 5 giây. Code: NowStyles.kt. Chưa có: lời bài hát đồng bộ, credits, DR (cần dữ liệu ngoài).

## Bản 0.6.0
- **Yêu thích**: bấm ♥ ở màn Đang phát, hoặc giữ nút OK (hay nút Menu) trên bất kỳ bài nào trong danh sách để thêm/bỏ. Tab **Yêu thích** trong Thư viện: phát tuần tự / ngẫu nhiên, bài mới thêm lên đầu. Bài yêu thích có icon ♥ trong mọi danh sách.
- **Đầu ra mặc định khi mở app**: Đầu ra ▸ “ĐẦU RA MẶC ĐỊNH KHI MỞ APP” (giữ lần trước / mặc định hệ thống / một thiết bị cụ thể). Thiết bị chưa cắm thì tạm dùng mặc định và tự chuyển khi cắm (USB DAC, HDMI…).
- **Chạy ngầm**: Android không cho xin quyền ngay lúc cài, nên app hỏi ngay lần mở đầu tiên (miễn tối ưu pin + thông báo). Có dịch vụ nền (foreground, mediaPlayback) + wake lock để nhạc không bị ngắt khi thoát ra màn hình chính. Xin lại ở Cài đặt ▸ Chạy ngầm.
- **Trang chủ TV**: Cài đặt ▸ Trang chủ TV ▸ Bật → hàng “GRAPEPlay” (yêu thích + phát gần đây, có ảnh bìa); bấm bài là phát luôn. Có thể phải bật kênh trong “Tuỳ chỉnh kênh” của màn hình chính. Cần thư viện tvprovider và provider ảnh bìa `com.grape.play.covers`.
- **Cảnh báo méo tiếng khi chỉnh EQ**: khung cảnh báo trong bảng EQ (xám/vàng/đỏ; đỏ khi đỉnh tăng ròng > 0 dB kèm gợi ý mức Preamp) và dòng cảnh báo ở màn Đang phát.
- **Giao diện**: Cài đặt ▸ Giao diện: Nho tím (mặc định), Xanh đêm, Rừng xanh, Hoàng hôn, Đen OLED, Sáng ngà. Thêm giao diện mới: thêm một `Palette` vào `Themes.ALL` (Themes.kt).
- Chưa biên dịch trong môi trường soạn mã — nếu Gradle báo lỗi, gửi lại log.

## Bản 0.5.4
- Sửa hộp thoại đăng nhập OneDrive/Google: mã đăng nhập đưa lên đầu, QR nhỏ lại, hộp thoại Thêm nguồn cuộn được nên không bị cắt trên TV FHD.

## Bản 0.5.3
- Đang phát: tên bài 44sp (hiện đủ 2 dòng), nút điều khiển to hơn (Play 124, các nút 96), 6 ô thông số cùng kích thước (Nguồn chỉ hiện tên dịch vụ), danh sách phát chữ nhỏ hơn nên thấy được nhiều bài hơn.

## Bản 0.6.3
- Nút **Thoát** ở góc dưới bên trái thanh điều hướng (có hộp thoại xác nhận): dừng nhạc, tắt dịch vụ nền / chạy ngầm, đóng app.
- Nút Cài đặt đổi sang biểu tượng **bánh răng**.
- Nút **Phổ tần**: hiện phổ tần số cố định của cả bài đang phát như ảnh Spek (trục x thời gian, trục y 0 → Nyquist, màu = cường độ dBFS, nhạc stereo vẽ kênh L trên / R dưới). Giải mã toàn bộ bài (FLAC, WAV, MP3/AAC/M4A/OGG/Opus qua MediaCodec) rồi FFT 2048 điểm, vẽ dần trong lúc phân tích; kết quả được nhớ cho 3 bài gần nhất. File DSD hiện thông báo lý do không có phổ. Code: `Spectrogram.kt`, `SpectrumDialog.kt`.

## Bản 0.6.4
- Phổ tần chuyển từ dạng thời gian thực sang spectrogram tĩnh kiểu Spek (bỏ bộ ghi mẫu trong `LevelProcessor`).
- Hộp thoại "Về ứng dụng" thiết kế lại gọn hơn: cột thương hiệu bên trái, nội dung và thẻ thông tin nhà phát triển bên phải, chữ nhỏ hơn.

## Bản 0.7.0 — chạy trên điện thoại và máy tính bảng
- Một APK chạy cả TV, điện thoại và máy tính bảng Android 8.0+ (không còn bắt buộc leanback, không khoá xoay ngang). App tự nhận diện: TV giữ nguyên giao diện điều khiển bằng remote; điện thoại / máy tính bảng dùng giao diện cảm ứng mới (`MobileApp.kt`).
- Giao diện cảm ứng: thanh điều hướng dưới (dọc) hoặc bên trái (ngang), mini-player, Thư viện (lọc nguồn, Album/Nghệ sĩ/Thể loại/Thư mục/Bài hát/Yêu thích), Đang phát (bìa, thanh tua kéo được, phát/dừng/trước/sau, ngẫu nhiên, lặp, yêu thích, đầu ra, phổ tần, danh sách phát), Cài đặt (giao diện màu, DSD, EQ 10 băng bằng thanh trượt, chạy ngầm, ảnh bìa mạng, ngôn ngữ, About, Thoát).
- Các hộp thoại (thêm nguồn, đầu ra, phổ tần, About, chạy ngầm, thoát) tự co theo màn hình và xếp dọc trên điện thoại.
- Android 13+: xin quyền READ_MEDIA_AUDIO thay cho READ_EXTERNAL_STORAGE.

## Bản 0.7.1
- Phổ tần: dùng bảng màu như Spek (tím/đen → xanh dương → xanh lơ → xanh lá → vàng → cam → đỏ), thang mặc định -20 → -120 dB (chọn thêm 0 → -120 dB).
- Hai kênh L/R được gộp thành một bảng duy nhất (trung bình công suất), thay vì hai bảng xếp chồng.

## Bản 0.7.2
- Biểu đồ phổ bố cục y hệt Spek: tiêu đề codec nằm trong khung và cắt "…" khi dài ("FLAC (Free Lossless Audio Codec), 44100 Hz, 16 bits, 2 channels"), trục tần số "0 / 5 / 10 / 15 / 20 kHz" kèm mép trên bằng Nyquist ("22 kHz"), trục thời gian có mốc cuối bài ("4:45"), khung viền trắng, thang màu "-20 dB … -120 dB" bên phải.

## Bản 0.7.3
- **Phổ tần**: khung phổ tự co vừa hộp thoại (TV không còn cuộn), nên luôn thấy tiêu đề, trục tần số tới mép trên **Nyquist (22 kHz với file 44,1 kHz)**, trục thời gian và nút điều khiển; không còn mất đoạn trên 20 kHz.
- **Đăng nhập OneDrive/Google**: mã đăng nhập hiển thị đầy đủ ở hàng riêng (chữ lớn, không bị "…"); nhãn đổi thành "Mã của bạn".

## Bản 0.7.4
- Sửa nhãn tín hiệu khi phát tới thiết bị UPnP: DSD được gửi nguyên file DSF/DFF (không chuyển PCM trên TV) nên hiển thị "DSD gốc, không chuyển mã"; chuỗi tín hiệu không còn ghi "→ PCM" / "→ EQ" trong chế độ này.

## Bản 0.7.5
- **DoP khi phát tới thiết bị UPnP** (Cài đặt → Phát DSD → "Khi phát tới thiết bị UPnP"): bài DSD64 (DSF/DFF, ≤ 2 kênh) được gói nguyên bit thành WAV PCM 24-bit/176,4 kHz theo chuẩn DoP 1.1 (nhãn 0x05/0xFA luân phiên) rồi gửi cho thiết bị, để WiiM chuyển tiếp cho DAC ngoài hỗ trợ DoP. Mặc định tắt (vẫn gửi file DSD gốc). Cần tắt EQ, âm lượng thiết bị 100%. DSD128 trở lên và Google Cast vẫn gửi file gốc.

## Bản 0.8.0
- **Điện thoại**: thanh điều hướng Thư viện · Đang phát · Cài đặt chuyển lên đầu màn hình (dạng 3 phân đoạn), tránh bấm nhầm với thanh điều hướng hệ thống; ô điều khiển ở màn Đang phát không còn bị che.
- **Bố cục Đang phát**: bỏ 5 bố cục cũ (Tối giản, Đĩa than, Dữ liệu, Tranh, Rạp tối). Giữ **Mặc định** và thêm 3 bố cục kiểu Symfonium dùng chung cho điện thoại, máy tính bảng và TV: **Cổ điển**, **Toàn ảnh**, **Nền màu** (nền chuyển sắc theo màu ảnh bìa). Chọn ở Cài đặt ▸ Bố cục màn Đang phát (lựa chọn cũ được đặt lại về Mặc định).
- **Nguồn nhạc** và **Đầu ra âm thanh** thành trang riêng (không còn hộp thoại): danh sách thẻ bên trái, bảng chi tiết bên phải; thêm bộ thành phần giao diện dùng chung (`Ui.kt`: PageHeader, OptionCard, DetailPanel, nút chính/phụ, thẻ nhãn, dòng thông số…). Đăng nhập Google Drive/OneDrive có chip các bước, mã QR và mã ghép nối cỡ lớn. Thanh bên trái (rail) dùng biểu tượng đường nét mảnh, gọn hơn.


## Bản 0.8.1 — Cập nhật ứng dụng + tách khoá bí mật khỏi mã nguồn

### Khoá đăng nhập đám mây (đổi chỗ)
`OAuthConfig.kt` không còn chứa Client ID / Secret. Điền vào `local.properties` (xem `local.properties.example`):
```
GOOGLE_CLIENT_ID=...apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=GOCSPX-...
```
Gradle đọc file này (hoặc biến môi trường cùng tên) và sinh `BuildConfig`. `local.properties` đã nằm trong `.gitignore`, nên không bị GitHub chặn (Push Protection) khi push. Mọi chỗ trong README cũ nói "điền vào OAuthConfig.kt" nay hiểu là "điền vào local.properties".

### Cập nhật ứng dụng (Cài đặt ▸ CẬP NHẬT ỨNG DỤNG)
Nguồn: GitHub Releases của `PhungP/grape2play` (repo phải **public**). App tự kiểm tra một lần khi mở Cài đặt; có nút "Kiểm tra cập nhật". Khi có bản mới: "Tải và cài đặt" → tải APK vào bộ nhớ đệm, kiểm tra đúng package `com.grape.play`, rồi mở trình cài đặt của Android. Lần đầu Android sẽ hỏi cho phép cài từ nguồn không xác định.

Phát hành một bản mới:
1. Tăng `versionCode` và `versionName` trong `app/build.gradle.kts` (ví dụ 23 / "0.8.2").
2. Build APK release, **ký bằng cùng một keystore** với các bản trước (khác khoá thì Android từ chối cài đè).
3. Trên GitHub: Releases ▸ Draft a new release ▸ tag `v0.8.2` (phải trùng versionName, có thể có tiền tố `v`) ▸ đính kèm đúng một file `.apk` ▸ Publish. Phần mô tả release hiện 4 dòng đầu trong hộp cập nhật.

Lưu ý: bản 0.8.1 phải cài thủ công một lần; từ đó trở đi các bản sau tự cập nhật được.


## Bản 0.8.2 — Dò loa UPnP kỹ hơn (JBL L75ms…) và phát DSD qua PCM 24-bit

### Dò thiết bị UPnP
- Gửi SSDP M-SEARCH ra **từng card mạng** (Wi-Fi/Ethernet) với 4 thẻ tìm kiếm (`MediaRenderer:1`, `AVTransport:1`, `upnp:rootdevice`, `ssdp:all`), lặp 3 lần.
- Loa nào Google Cast đã thấy nhưng SSDP đa hướng chưa thấy thì app **hỏi thẳng IP đó** (unicast, cổng 1900).
- Dưới danh sách ở trang "Đầu ra âm thanh" có dòng ghi chú lần quét (số phản hồi SSDP, số thiết bị UPnP, số Cast) để chẩn đoán khi không thấy loa.
- Một loa có cả UPnP lẫn Google Cast sẽ hiện hai mục; chọn mục **UPnP** để phát DSD (Cast không hỗ trợ DSD).

### Phát DSD tới thiết bị UPnP — 3 chế độ (Cài đặt ▸ PHÁT DSD)
1. **Gửi file DSD gốc** (mặc định): thiết bị tự xử lý. Nếu thiết bị từ chối / không phát trong ~15 giây, app **tự chuyển sang PCM 24-bit** cho bài đó và các bài sau cho tới khi đổi thiết bị.
2. **DoP** (chỉ DSD64): dành cho DAC ngoài hỗ trợ DoP.
3. **PCM 24-bit**: app giải mã DSF/DFF thành WAV 24-bit (tốc độ theo mục "Chuyển DSD sang PCM", nên chọn 176.4 kHz) rồi gửi qua HTTP, tua được. Phù hợp JBL L75ms (hãng ghi "DSD (chuyển PCM)", PCM tới 192 kHz).


## Bản 0.8.3

- **Phát DSD tới thiết bị UPnP (JBL L75ms…)**: bỏ chế độ "PCM 24-bit" (còn "Gửi file DSD gốc" và "DoP"). Trước khi nạp bài mới app đưa thiết bị về trạng thái nghỉ (Stop, chờ STOPPED), chờ hết TRANSITIONING rồi mới Play và tự thử lại khi thiết bị báo bận (701/705). Đổi chế độ khi đang phát thì bài hiện tại nạp lại ngay, giữ nguyên vị trí; đang tạm dừng thì nạp lại ở lần bấm Phát kế tiếp. Bấm Phát sau khi thiết bị báo lỗi sẽ nạp lại cả bài (không chỉ gửi lệnh Play) nên không còn kẹt "không phát được" sau khi đổi chế độ.
- **Cài đặt**: bỏ kiểu "Toàn ảnh"; thêm mục **Chống lưu ảnh màn hình OLED** (bật/tắt: dịch nhẹ giao diện mỗi phút + tự giảm sáng sau 5 phút không thao tác); trang Về ứng dụng chữ to hơn, thay email bằng link GitHub, nút "Đóng" ở giữa phía dưới.
- **Đang phát (4K)**: canvas 4K nhỏ lại 1760 → 1640 dp (toàn giao diện to hơn ~7%); riêng màn Đang phát được phóng đúng tỷ lệ bản FHD (ảnh bìa lớn, khoảng trống giữa hộp tín hiệu và sóng âm gọn lại); hộp tín hiệu có viền/khối giống các hộp Định dạng, Độ sâu bit…
- **Thư viện**: mục Thư mục hiển thị dạng ô thư mục có ảnh bìa (nếu có); nút **⚙ Nguồn hiển thị** để hiện/ẩn từng nguồn (nguồn ẩn không lẫn vào "Tất cả"). Có trên cả TV và điện thoại.
- **Phổ tần**: tiêu đề nhỏ lại (30 → 24) cho đồng bộ với các trang cài đặt.


## Bản 0.8.4

- Cập nhật ứng dụng: chịu được tag có ký tự lạ ở đầu (`v.0.8.3`, `V0.8.3`…); mọi ký tự không phải số ở đầu tag đều được bỏ trước khi so sánh phiên bản. Thẻ cập nhật hiện thêm dòng "Bản mới nhất trên GitHub" và báo rõ khi GitHub chưa có Release hợp lệ.
- Khuyến nghị đặt tag đúng dạng `v0.8.4` (không có dấu chấm sau chữ v).

## Bản 0.8.5
- Quét đầu ra: không phụ thuộc DSD gốc/DoP. Google Cast tìm bằng NsdManager + mDNS tự viết (hợp hai kết quả), UPnP bằng SSDP trên mọi card mạng + hỏi thẳng IP; tự quét lượt 2 nếu thiếu thiết bị đã biết; nhớ thiết bị từng thấy và kiểm tra bằng kết nối TCP nên loa bận/rơi gói UDP không biến mất.
- Chống lưu ảnh: thu nhỏ nhẹ thay vì phóng to, nên khi dịch hình menu không bị đẩy sát viền.
- Cửa sổ Ứng dụng, Phổ tần số, Thoát thu nhỏ (~1/4 màn hình), chữ giảm đồng bộ.
- "Quét lại" nằm dưới "Cài đặt", "Thoát" ở cuối thanh trái; cả hai cùng cỡ với các nút khác.

## Bản 0.8.6
- Cửa sổ "Về ứng dụng" và "Nguồn hiển thị" thu nhỏ còn ~1/4 màn hình (kích thước theo tỉ lệ màn hình); sửa chữ "Nhà phát triển" bị cắt.
- Các trang cuộn (Đầu ra, Nguồn nhạc, Cài đặt) có dải cuối nhận tiêu điểm D-pad để cuộn hết, không mất chữ ở mép dưới.
- Phát UPnP (Bravia…): TV tự phục vụ ảnh bìa (`/a/…jpg`) và gửi `albumArtURI` + DLNA protocolInfo; nếu TV từ chối metadata thì thử lại không metadata; chọn đúng IP Wi-Fi/LAN trên điện thoại (tránh card 4G).
- Điều khiển TV từ điện thoại: TV mở máy chủ điều khiển (PIN 6 số ở Cài đặt ▸ Điều khiển từ điện thoại); điện thoại bấm biểu tượng TV ở Thư viện để dò TV, nhập PIN, điều khiển phát/tạm dừng/bài trước-sau/tua/xáo/lặp và tìm bài trong thư viện TV để phát.
- Cửa sổ thông báo có bản cập nhật mới (tự hiện sau khi mở app): Cập nhật ngay / Để sau.

## Bản 0.8.7
- Bỏ tính năng điều khiển app trên TV bằng điện thoại.
- Cài đặt: thêm lựa chọn Gapless (bộ đệm nạp trước 90 giây để nối bài liền mạch khi phát trên chính TV/máy).
- Đầu ra âm thanh: rê con trỏ tới thiết bị nào thì khung bên phải hiện thông tin thiết bị đó; bấm vào thiết bị hiện hộp thoại Chọn / Huỷ; bỏ nút "Dùng đầu ra này".
- Đo thời gian chuyển bài (từ lúc bấm đến lúc có tiếng); quá 3 giây hiện thông báo "Tốc độ đường truyền không ổn định".
- Về ứng dụng: nhãn "Nhà phát triển" xếp dọc, chữ nhỏ và mảnh hơn trên điện thoại để hiện đủ chữ.

## Bản 0.8.8
- Phát tới thiết bị từ xa (UPnP/Cast): app giữ khoá thức CPU + Wi-Fi hiệu suất cao khi đang phát, tránh bị hệ thống làm chậm/ngắt khi tắt màn hình (không cứu được TV đã vào standby thật).
- UPnP gapless: nạp sẵn bài kế tiếp bằng SetNextAVTransportURI; thiết bị hỗ trợ sẽ tự qua bài, app nhận biết (TrackURI đổi) và cập nhật hiển thị. Thiết bị không hỗ trợ tự quay về cách cũ (app nạp bài khi hết bài).
- Cửa sổ "Đã cập nhật lên bản X" hiện một lần sau khi cài bản mới, kèm ghi chú phát hành nếu có.

## Bản 0.8.9
- UPnP gapless: thêm cách nhận biết dự phòng khi loa tự qua bài nhưng viết lại URL (bài trước gần hết, vị trí về gần 0 và đang phát) để app không nạp lặp lại bài.

## Bản 0.9.0
- **Màn Đang phát mới** (giao diện mặc định): ảnh bìa lớn, đáy thẳng hàng hộp "Đường tín hiệu"; dải sóng âm thấp (46 dp); phát trên TV có 2 đồng hồ VU kim (-40…+3 dB); phát tới UPnP/Cast có hộp "Thiết bị nhận" và "Kết nối & phát liên tục" (độ trễ, trạng thái, bài kế đã nạp sẵn, lần chuyển bài gần nhất, số lần mất kết nối).
- **Nhật ký lỗi** (Cài đặt → Nhật ký lỗi): ghi lỗi phát nhạc, loa/NAS, cập nhật; bắt cả lỗi làm app dừng. Điện thoại: nút Gửi (email/chia sẻ). TV: hiện địa chỉ `http://IP:cổng/log` để mở bằng trình duyệt máy khác. Đã lọc mật khẩu/token trong URL.
- **Thông báo bản mới trên TV** kèm danh sách tính năng (lấy từ ghi chú Release trên GitHub); tự thử lại 5 s/20 s/60 s nếu lúc mở app mạng chưa sẵn sàng. Bỏ thông báo "đã cập nhật".
- **Sửa kẹt khi phát DSD ra đầu ra không tương thích** (cổng quang, TV): tự hạ tốc độ DSD→PCM cho riêng đầu ra đó (176,4 → 88,2 → 44,1 kHz) rồi phát lại; sau lỗi, chọn bài/thư mục khác, bấm Phát hoặc đổi đầu ra đều nạp lại bộ phát ngay và xoá thông báo lỗi cũ.
- **Nhiều máy cùng một loa UPnP**: hỏi trạng thái thưa hơn và lệch pha, giãn dần khi loa chậm, timeout ngắn; phát hiện máy khác giành quyền (URL lạ 3 lần liên tiếp) thì ngừng gửi lệnh, không tự chuyển bài, báo rõ và chờ bấm Phát để giành lại.
- Dọn mã: gom 3 đoạn trùng lặp trong Playback (`startProbe`), bỏ hàm `Vu` không dùng, bỏ `UpdatedDialog`.
