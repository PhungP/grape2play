package com.grape.play

import androidx.compose.ui.graphics.Color

enum class Grade(val color: Color) {
    GREEN(Color(0xFF5BD67A)),   // bit-perfect
    YELLOW(Color(0xFFE6C34A)),  // gần đạt
    RED(Color(0xFFE5575A)),     // nén mất dữ liệu
}

/** Bài DSD64 đang được gửi tới thiết bị UPnP dưới dạng DoP (theo tuỳ chọn trong Cài đặt → Phát DSD). */
fun dopActive(t: Track, i: AudioInfo?, remote: Renderer?): Boolean =
    remote != null && remote.kind == "upnp" && AudioSettings.dopRemote && (t.ext == "dsf" || t.ext == "dff") && (i?.sampleRate ?: t.sr) == DopPcm.DSD64

/** Đánh giá chuỗi tín hiệu của app: (mức, nhãn ngắn). Chỉ đánh giá phần app kiểm soát; hệ điều hành/TV vẫn có thể đổi tần số lấy mẫu ở tầng dưới. */
fun signalGrade(t: Track, i: AudioInfo?, pcmFmt: String, remote: Renderer?): Pair<Grade, String> {
    val lossy = t.ext in setOf("mp3", "aac", "ogg", "oga", "opus")
    if (lossy) return Grade.RED to "LOSSY · nén mất dữ liệu"
    if (t.ext == "dsf" || t.ext == "dff")
        return when {
            remote == null -> Grade.YELLOW to "GẦN BIT-PERFECT · DSD chuyển sang PCM"
            dopActive(t, i, remote) -> Grade.GREEN to "BIT-PERFECT · DoP (DSD64 trong PCM 24-bit/176,4 kHz)"
            else -> Grade.GREEN to "BIT-PERFECT · DSD gốc, không chuyển mã"   // phát tới thiết bị UPnP: gửi nguyên file DSF/DFF
        }
    if (remote != null) return if (t.ext == "m4a") Grade.YELLOW to "GẦN BIT-PERFECT · chưa rõ codec" else Grade.GREEN to "BIT-PERFECT"
    if (!DspParams.isFlat()) return Grade.YELLOW to "GẦN BIT-PERFECT · EQ đang bật"
    val srcBits = i?.bits ?: t.bits
    val out16 = pcmFmt.contains("16-bit")
    if (srcBits > 16 && out16) return Grade.YELLOW to "GẦN BIT-PERFECT · hạ $srcBits→16-bit"
    if (t.ext == "m4a") return Grade.YELLOW to "GẦN BIT-PERFECT · chưa rõ codec"
    return Grade.GREEN to "BIT-PERFECT"
}
