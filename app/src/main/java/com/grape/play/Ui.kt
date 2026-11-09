package com.grape.play

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/*
 * Bộ thành phần dùng chung để mọi trang (Nguồn nhạc, Đầu ra, Cài đặt…) có cùng một ngôn ngữ thiết kế:
 * tiêu đề serif + dòng phụ mờ, nhãn mục chữ hoa giãn chữ, thẻ lựa chọn viền mảnh (viền vàng khi chọn), bảng chi tiết bo góc lớn,
 * nút chính nền vàng / nút phụ viền mảnh.
 */

/** Tiêu đề trang: chữ serif lớn + dòng mô tả mờ bên dưới. */
@Composable
fun PageHeader(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        T(title, if (LocalCompact.current) 30 else 40, serif = true, lines = 2)
        if (subtitle.isNotEmpty()) T(subtitle, if (LocalCompact.current) 15 else 18, C.Muted, lines = 2)
    }
}

/** Nhãn đầu mỗi nhóm: chữ hoa nhỏ, giãn chữ, màu mờ. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) = T(text, 13, C.Muted, modifier, spaced = true, bold = true)

/** Thẻ lựa chọn có biểu tượng, tiêu đề, mô tả và nhãn trạng thái (ĐANG CHỌN / ĐÃ THÊM…). Viền vàng khi đang chọn. */
@Composable
fun OptionCard(
    icon: ImageVector, title: String, subtitle: String, selected: Boolean, tag: String = "",
    modifier: Modifier = Modifier, requester: FocusRequester? = null, onFocus: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.fillMaxWidth().clip(shape).border(if (selected) 1.5.dp else 1.dp, if (selected) C.Gold else C.Line, shape)) {
        Focusable(onClick, Modifier.fillMaxWidth(), shape, selected = selected, requester = requester, onFocus = onFocus) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(icon, null, tint = if (selected) C.Gold else C.Muted, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T(title, 20, if (selected) C.Gold else C.Text, lines = 1)
                    if (subtitle.isNotEmpty()) T(subtitle, 14, C.Muted, lines = 1)
                }
                if (tag.isNotEmpty()) T(tag, 12, if (selected) C.Gold else C.Muted, spaced = true, bold = true)
            }
        }
    }
}

/** Bảng chi tiết bên phải: nền tối, viền mảnh, bo góc lớn. */
@Composable
fun DetailPanel(modifier: Modifier = Modifier, scroll: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(C.Panel).border(1.dp, C.Line, shape)
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(if (LocalCompact.current) 16.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
    )
}

/** Nút chính: nền vàng, chữ tối. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, requester: FocusRequester? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    // Khi nhận focus nền chuyển sang tối (C.Sel) nên chữ đổi sang màu vàng để vẫn đọc được.
    Focusable(onClick, modifier.clip(shape).background(C.Gold), shape, requester = requester, ring = C.Text) { f ->
        T(text, 19, if (f) C.Gold else C.Bg, Modifier.padding(26.dp, 12.dp), bold = true)
    }
}

/** Nút phụ: viền mảnh, chữ sáng. */
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = C.Text, requester: FocusRequester? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.clip(shape).border(1.dp, C.Dim, shape)) {
        Focusable(onClick, Modifier, shape, requester = requester) { T(text, 19, color, Modifier.padding(24.dp, 12.dp)) }
    }
}

/** Thẻ nhãn nhỏ viền mảnh, ví dụ "PCM 32-bit / 384 kHz". */
@Composable
fun Tag(text: String, color: Color = C.Gold) =
    Box(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(5.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        T(text, 13, color, bold = true)
    }

/** Chip các bước (1 ĐĂNG NHẬP · 2 CHỌN THƯ MỤC · 3 QUÉT THƯ VIỆN). */
@Composable
fun StepChip(text: String, active: Boolean) =
    Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (active) C.Gold else C.Line, RoundedCornerShape(50)).background(if (active) C.Sel else Color.Transparent).padding(horizontal = 16.dp, vertical = 7.dp)) {
        T(text, 14, if (active) C.Gold else C.Muted, bold = active)
    }

/** Một dòng thông số: nhãn mờ bên trái, giá trị đậm bên phải, kẻ mảnh phía dưới. */
@Composable
fun SpecRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T(label, 17, C.Muted)
            T(value, 18, C.Text, bold = true)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
    }
}

/** Khung ghi chú nền tối (đường tín hiệu, cảnh báo…). */
@Composable
fun NoteBox(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Bg).padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)

/** Chấm trạng thái tròn nhỏ. */
@Composable
fun Dot(color: Color = C.Gold) = Box(Modifier.size(10.dp).clip(CircleShape).background(color))
