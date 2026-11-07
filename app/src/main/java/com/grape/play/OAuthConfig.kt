package com.grape.play

/**
 * Các khoá đăng nhập đám mây KHÔNG còn nằm trong mã nguồn.
 * Điền vào file local.properties (đã bị .gitignore) ở thư mục gốc dự án:
 *
 *   GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com
 *   GOOGLE_CLIENT_SECRET=GOCSPX-xxxx
 *   MS_CLIENT_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx   (tuỳ chọn, mặc định dùng ID có sẵn)
 *
 * Gradle đọc các dòng này và sinh ra BuildConfig khi build. Để trống thì app vẫn chạy,
 * chỉ là bấm Google Drive / OneDrive sẽ báo "chưa cấu hình".
 * (Có thể đặt cùng tên làm biến môi trường khi build trên CI.)
 */
object OAuthConfig {
    /** Google Cloud Console ▸ Credentials ▸ OAuth client ID, loại "Desktop app". */
    val GOOGLE_CLIENT_ID: String = BuildConfig.GOOGLE_CLIENT_ID
    /** Client secret của OAuth client loại Desktop (Google vẫn yêu cầu gửi, dù không thực sự bí mật). */
    val GOOGLE_CLIENT_SECRET: String = BuildConfig.GOOGLE_CLIENT_SECRET
    /** Azure portal ▸ App registrations ▸ Application (client) ID. */
    val MS_CLIENT_ID: String = BuildConfig.MS_CLIENT_ID
}
