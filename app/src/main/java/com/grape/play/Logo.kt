package com.grape.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale

private fun poly(vararg p: Float): Path = Path().apply {
    moveTo(p[0], p[1]); var i = 2
    while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
    close()
}

/** Logo GRAPE2Play: con cáo hình học, mõm là chùm nho, chiếc lá trên trán. Vẽ theo hệ toạ độ 512×512 (giống bản thiết kế). */
@Composable
fun GrapeLogo(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val k = size.minDimension / 512f
        scale(k, k, pivot = Offset.Zero) {
            val fox = Brush.linearGradient(listOf(Color(0xFFF2A45B), Color(0xFFD9702B)), Offset(0f, 0f), Offset(512f, 512f))
            // lá + cuống
            drawPath(Path().apply { moveTo(256f, 196f); lineTo(256f, 138f) }, Color(0xFF8FB050), style = Stroke(10f, cap = StrokeCap.Round))
            drawPath(Path().apply {
                moveTo(256f, 140f); cubicTo(230f, 108f, 196f, 108f, 180f, 120f); cubicTo(192f, 152f, 228f, 160f, 256f, 140f); close()
            }, Color(0xFF8FB050))
            // tai + đầu + má
            drawPath(poly(96f, 60f, 214f, 180f, 92f, 262f), fox)
            drawPath(poly(416f, 60f, 298f, 180f, 420f, 262f), fox)
            drawPath(poly(122f, 116f, 190f, 184f, 114f, 226f), Color(0xFF3A1D4A))
            drawPath(poly(390f, 116f, 322f, 184f, 398f, 226f), Color(0xFF3A1D4A))
            drawPath(poly(92f, 262f, 214f, 180f, 256f, 196f, 298f, 180f, 420f, 262f, 388f, 330f, 256f, 392f, 124f, 330f), fox)
            drawPath(poly(92f, 262f, 196f, 292f, 256f, 392f, 124f, 330f), Color(0xFFFBE7C8))
            drawPath(poly(420f, 262f, 316f, 292f, 256f, 392f, 388f, 330f), Color(0xFFFBE7C8))
            // mắt
            drawPath(poly(178f, 262f, 214f, 276f, 200f, 292f), Color(0xFF2B1636))
            drawPath(poly(334f, 262f, 298f, 276f, 312f, 292f), Color(0xFF2B1636))
            // chùm nho
            val r = 36f
            listOf(188f to 338f, 256f to 338f, 324f to 338f, 222f to 392f, 290f to 392f, 256f to 446f).forEach { (cx, cy) ->
                val b = Brush.radialGradient(listOf(Color(0xFFC58BE0), Color(0xFF5B2A78)), center = Offset(cx - 0.3f * r, cy - 0.4f * r), radius = r * 1.6f)
                drawCircle(b, r, Offset(cx, cy))
                drawCircle(Color(0xFF1A0F22), r, Offset(cx, cy), style = Stroke(4f))
            }
        }
    }
}

/** Chữ "GRAPE2Play": GRAPE và Play màu kem, số 2 màu vàng đồng. */
@Composable
fun Wordmark(size: Int, modifier: Modifier = Modifier) {
    Row(modifier) {
        T("GRAPE", size, serif = true, bold = true)
        T("2", size, C.Gold, serif = true, bold = true)
        T("Play", size, serif = true, bold = true)
    }
}
