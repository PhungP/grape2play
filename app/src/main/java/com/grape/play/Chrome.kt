package com.grape.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Trạng thái các hộp thoại toàn cục mở từ thanh bên trái (hiển thị ở MainActivity). */
object Overlay {
    val spectrum = mutableStateOf(false)
    val exit = mutableStateOf(false)
}

/**
 * Thanh điều hướng mỏng bên trái: logo + 6 mục (Đang phát, Thư viện, Nguồn nhạc, Đầu ra, Phổ tần, Cài đặt), biểu tượng đường nét mảnh;
 * mục đang mở có nền nhấn. Góc dưới: huy hiệu, Thoát và Quét lại. Nguồn nhạc / Đầu ra là trang riêng (Screen.SOURCES / Screen.OUTPUT).
 * Hai tham số cuối giữ lại để tương thích, không còn dùng.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun Rail(cur: Screen, onNav: (Screen) -> Unit, onSources: () -> Unit = {}, onOut: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scanning by Library.scanning.collectAsState()
    val count by Library.tracks.collectAsState()
    val remote by Playback.remote.collectAsState()
    Column(
        Modifier.width(132.dp).fillMaxHeight().background(C.Side).padding(horizontal = 8.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GrapeLogo(Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        RailItem(Icons.Outlined.MusicNote, "Đang phát", cur == Screen.NOW) { onNav(Screen.NOW) }
        RailItem(Icons.Outlined.GridView, "Thư viện", cur == Screen.LIB) { onNav(Screen.LIB) }
        RailItem(Icons.Outlined.CloudQueue, "Nguồn nhạc", cur == Screen.SOURCES) { onNav(Screen.SOURCES) }
        RailItem(Icons.Outlined.Cast, "Đầu ra", cur == Screen.OUTPUT, highlight = remote != null) { onNav(Screen.OUTPUT) }
        RailItem(Icons.Outlined.Equalizer, "Phổ tần", false) { Overlay.spectrum.value = true }
        RailItem(Icons.Outlined.Tune, "Cài đặt", cur == Screen.SOUND) { onNav(Screen.SOUND) }
        Spacer(Modifier.weight(1f))
        LimitedBadge()
        Spacer(Modifier.height(6.dp))
        if (scanning) T("Đang quét… ${count.size} bài", 12, C.Gold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Focusable({ Overlay.exit.value = true }, Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.PowerSettingsNew, null, tint = Color(0xFFE5575A), modifier = Modifier.size(24.dp)); T("Thoát", 12, C.Muted)
                }
            }
            if (scanning) Spacer(Modifier.weight(1f))
            else Focusable({ Library.rescanAllAsync(ctx.applicationContext) }, Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Refresh, null, tint = C.Muted, modifier = Modifier.size(24.dp)); T("Quét lại", 12, C.Muted)
                }
            }
        }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, highlight: Boolean = false, onClick: () -> Unit) =
    Focusable(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), selected = selected) {
        Column(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = if (selected || highlight) C.Gold else C.Muted, modifier = Modifier.size(30.dp))
            T(label, 13, if (selected) C.Gold else C.Muted, bold = selected)
        }
    }

/** Huy hiệu "Limited Edition" (giữ nguyên tiếng Anh ở mọi ngôn ngữ): chữ serif nghiêng, vàng chuyển sắc, khung vàng mảnh. */
@Composable
fun LimitedBadge() {
    val gold = Brush.horizontalGradient(listOf(Color(0xFFF3DFA2), Color(0xFFD4B36A), Color(0xFFA67C2E), Color(0xFFE9CF8A)))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Color(0x88D4B36A), RoundedCornerShape(10.dp)).background(Color(0x14D4B36A)).padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        androidx.compose.material3.Text("✦", style = TextStyle(brush = gold, fontSize = 14.sp))
        androidx.compose.material3.Text(
            "Limited\nEdition", textAlign = TextAlign.Center,
            style = TextStyle(brush = gold, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold, fontSize = 19.sp, letterSpacing = 0.5.sp, lineHeight = 23.sp),
        )
        androidx.compose.material3.Text("✦", style = TextStyle(brush = gold, fontSize = 14.sp))
    }
}
