package com.grape.play

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/** Bảng màu của một giao diện. `accent` là màu nhấn (vàng ở giao diện mặc định). */
class Palette(
    val id: String, val name: String,
    val bg: Color, val panel: Color, val side: Color, val line: Color, val dim: Color, val sel: Color,
    val accent: Color, val text: Color, val muted: Color, val plum: Color, val plumSoft: Color,
)

/** Các giao diện có sẵn. Toàn bộ app đọc màu qua `C.*` nên đổi giao diện là đổi tức thì. */
object Themes {
    val ALL: List<Palette> = listOf(
        Palette("grape", "Nho tím",
            Color(0xFF0C0911), Color(0xFF16101D), Color(0xFF110C17), Color(0xFF2B2236), Color(0xFF433752), Color(0xFF241A31),
            Color(0xFFD4B36A), Color(0xFFECE7DC), Color(0xFF9C94A8), Color(0xFF7A3EBE), Color(0xFFC58BF2)),
        Palette("midnight", "Xanh đêm",
            Color(0xFF080D14), Color(0xFF0F1823), Color(0xFF0B121B), Color(0xFF1F2E40), Color(0xFF33485F), Color(0xFF172636),
            Color(0xFF6FB8E8), Color(0xFFE6EDF4), Color(0xFF8FA1B3), Color(0xFF2E7DBE), Color(0xFF8CCBF2)),
        Palette("forest", "Rừng xanh",
            Color(0xFF0A100C), Color(0xFF111A14), Color(0xFF0D1510), Color(0xFF22332A), Color(0xFF38503F), Color(0xFF182A1E),
            Color(0xFFA6D17A), Color(0xFFE6EEE4), Color(0xFF94A89A), Color(0xFF3E9B5E), Color(0xFF8FDCA6)),
        Palette("sunset", "Hoàng hôn",
            Color(0xFF120B08), Color(0xFF1C110C), Color(0xFF160E0A), Color(0xFF3A2519), Color(0xFF593A27), Color(0xFF2A1A11),
            Color(0xFFF2A45B), Color(0xFFF3E8DC), Color(0xFFB39B88), Color(0xFFC4572B), Color(0xFFF29A6B)),
        Palette("oled", "Đen OLED",
            Color(0xFF000000), Color(0xFF0C0C0C), Color(0xFF050505), Color(0xFF222222), Color(0xFF3A3A3A), Color(0xFF1A1A1A),
            Color(0xFFD4B36A), Color(0xFFEDEDED), Color(0xFF9A9A9A), Color(0xFF7A3EBE), Color(0xFFC58BF2)),
        Palette("ivory", "Sáng ngà",
            Color(0xFFF5F0E6), Color(0xFFFFFBF2), Color(0xFFEBE4D6), Color(0xFFD6CCB8), Color(0xFFB8AC94), Color(0xFFE8DFCB),
            Color(0xFF9A6B14), Color(0xFF2A241B), Color(0xFF6F6657), Color(0xFF6B3FA0), Color(0xFF7A4DB0)),
    )

    val cur = mutableStateOf(ALL[0])
    /** Bố cục màn Đang phát: 0 mặc định, 1 Cổ điển, 2 Toàn ảnh (đã bỏ), 3 Nền màu (kiểu Symfonium), 4 Nhôm phay xước (chỉ TV/máy tính bảng ngang; điện thoại dùng bố cục mặc định). */
    val style = mutableStateOf(0)
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE)
        val id = sp.getString("id", "grape")
        cur.value = ALL.firstOrNull { it.id == id } ?: ALL[0]
        style.value = sp.getInt("nowStyle", 0).coerceIn(0, 4).let { if (it == 2) 0 else it }   // kiểu "Toàn ảnh" đã bỏ
    }
    fun setStyle(i: Int) { style.value = i; if (::sp.isInitialized) sp.edit().putInt("nowStyle", i).apply() }
    fun set(id: String) {
        cur.value = ALL.firstOrNull { it.id == id } ?: ALL[0]
        if (::sp.isInitialized) sp.edit().putString("id", cur.value.id).apply()
    }
}
