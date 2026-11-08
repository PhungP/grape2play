package com.grape.play

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay

/**
 * Chống lưu ảnh (burn-in) cho màn hình OLED:
 *  1. Dịch nhẹ toàn bộ giao diện vài điểm ảnh mỗi phút (đi vòng quanh 9 vị trí), nên các chi tiết cố định như thanh bên, logo, viền không nằm yên một chỗ.
 *  2. Sau vài phút không thao tác, tự giảm sáng giao diện; bấm phím hoặc chạm là sáng lại ngay.
 */
object BurnIn {
    val enabled = mutableStateOf(false)
    @Volatile var lastInput = System.currentTimeMillis()
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("burnin", Context.MODE_PRIVATE)
        enabled.value = sp.getBoolean("on", false)
    }
    fun set(on: Boolean) { enabled.value = on; touch(); if (::sp.isInitialized) sp.edit().putBoolean("on", on).apply() }
    /** Gọi mỗi khi người dùng bấm phím / chạm màn hình. */
    fun touch() { lastInput = System.currentTimeMillis() }

    const val SHIFT_EVERY_MS = 60_000L
    const val DIM_AFTER_MS = 5 * 60_000L
    const val SHIFT_DP = 6f                 // biên độ dịch (dp). Giao diện được phóng 1,4% để mép không lộ nền khi dịch.
    const val DIM_ALPHA = 0.55f
    private val PATH = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f, -1f to 1f, -1f to 0f, -1f to -1f, 0f to -1f, 1f to -1f)

    @Composable
    fun Guard(content: @Composable () -> Unit) {
        val on by enabled
        var step by remember { mutableStateOf(0) }
        var idle by remember { mutableStateOf(false) }
        LaunchedEffect(on) { while (on) { delay(SHIFT_EVERY_MS); step = (step + 1) % PATH.size } }
        LaunchedEffect(on) {
            idle = false
            while (on) { idle = System.currentTimeMillis() - lastInput > DIM_AFTER_MS; delay(2000) }
            idle = false
        }
        val px = SHIFT_DP * LocalDensity.current.density
        val (dx, dy) = PATH[step]
        val tx by animateFloatAsState(if (on) dx * px else 0f, tween(4000), label = "burnX")
        val ty by animateFloatAsState(if (on) dy * px else 0f, tween(4000), label = "burnY")
        val sc by animateFloatAsState(if (on) 1.014f else 1f, tween(600), label = "burnS")
        val dim by animateFloatAsState(if (on && idle) DIM_ALPHA else 0f, tween(2500), label = "burnDim")
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = sc; scaleY = sc; translationX = tx; translationY = ty }) { content() }
            if (dim > 0.001f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }
    }
}

/** Nguồn nhạc nào được hiện ở Thư viện (ẩn bớt nguồn không dùng cho đỡ rối). id "local" = bộ nhớ TV & USB. */
object SourceVisibility {
    val hidden = mutableStateOf<Set<String>>(emptySet())
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("srcvis", Context.MODE_PRIVATE)
        hidden.value = sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
    }
    fun isVisible(id: String) = id !in hidden.value
    fun toggle(id: String) {
        hidden.value = if (id in hidden.value) hidden.value - id else hidden.value + id
        if (::sp.isInitialized) sp.edit().putStringSet("hidden", hidden.value).apply()
    }
}
