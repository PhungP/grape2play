@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.grape.play

import android.Manifest
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var perm by mutableStateOf(false)
    private var permDone by mutableStateOf(false)   // đã xử lý xong hộp thoại quyền bộ nhớ (cho phép hoặc từ chối)
    private var openNow by mutableStateOf(false)    // mở màn Đang phát (khi bấm bài từ trang chủ TV)
    private val isTv: Boolean by lazy {
        (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    /** Android 13+ tách quyền đọc nhạc thành READ_MEDIA_AUDIO. */
    private val storagePerm: String get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private val req = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        perm = ok; permDone = true
        if (ok) lifecycleScope.launch { Library.load(applicationContext) }
    }
    private val reqNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { BackgroundAccess.requestBattery(this) }

    /** Bật chạy ngầm: giữ dịch vụ nền (có hiệu lực ngay) rồi xin thêm quyền thông báo / miễn tối ưu pin.
     *  Trả về false nếu TV không có màn hình cấp quyền pin nào để mở (dịch vụ nền vẫn đã bật). */
    fun askBackground(): Boolean {
        BackgroundAccess.asked = true
        BackgroundAccess.keep = true
        Keepalive.ensure(this)
        if (BackgroundAccess.needsNotifPerm(this)) { reqNotif.launch(Manifest.permission.POST_NOTIFICATIONS); return true }
        return BackgroundAccess.requestBattery(this)
    }

    /** Thoát hẳn: dừng nhạc, tắt dịch vụ nền / chạy ngầm, đóng app và gỡ khỏi danh sách gần đây. */
    fun exitApp() {
        BackgroundAccess.keep = false
        runCatching { if (Playback.remote.value == null) Playback.exo.pause() else if (Playback.playing.value) Playback.toggle() }
        stopService(Intent(this, PlaybackService::class.java))
        finishAndRemoveTask()
    }

    /** Bấm một bài trên hàng GRAPEPlay ở trang chủ TV: phát bài đó (trong danh sách yêu thích / phát gần đây). */
    private fun handleLaunch(i: Intent?) {
        val path = i?.getStringExtra(TvHome.EXTRA_PATH) ?: return
        val list = i.getStringExtra(TvHome.EXTRA_LIST) ?: ""
        i.removeExtra(TvHome.EXTRA_PATH)
        lifecycleScope.launch {
            var waited = 0
            while (Library.tracks.value.isEmpty() && waited < 20_000) { delay(250); waited += 250 }
            val all = Library.tracks.value
            val q = when (list) { "fav" -> Favorites.tracks(all); "recent" -> Recents.tracks(all); else -> emptyList() }
            val k = q.indexOfFirst { it.path == path }
            if (k >= 0) { Playback.play(q, k, false); openNow = true }
            else all.firstOrNull { it.path == path }?.let { Playback.play(listOf(it), 0, false); openNow = true }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Device.tv = isTv
        SourceStore.init(applicationContext)
        BurnIn.init(applicationContext)
        SourceVisibility.init(applicationContext)
        Playback.init(applicationContext)
        perm = ContextCompat.checkSelfPermission(this, storagePerm) == PackageManager.PERMISSION_GRANTED
        permDone = perm
        if (isTv) TvHome.schedule(applicationContext, 15_000)   // làm mới hàng trên trang chủ TV sau khi thư viện nạp xong
        handleLaunch(intent)
        if (perm) { if (Library.tracks.value.isEmpty()) lifecycleScope.launch { Library.load(applicationContext) } }
        else req.launch(storagePerm)
        val dm = resources.displayMetrics
        val tv = isTv
        setContent {
            val cfg = LocalConfiguration.current
            // TV: 1dp = 1px của canvas thiết kế (16:9). Canvas rộng 1560 (FHD, chữ to) hoặc 1920 (4K, gọn); tự co giãn theo màn hình thật 720p / 1080p / 4K.
            // Điện thoại / máy tính bảng: dùng mật độ thật của máy, chữ co nhỏ hơn một chút trên màn hình hẹp.
            val cw = DisplayPrefs.canvasWidth(dm.heightPixels)
            val scale = minOf(dm.widthPixels / cw, dm.heightPixels / (cw * 9f / 16f)).coerceAtLeast(0.4f)
            val density = if (tv) Density(scale, 1f) else LocalDensity.current
            val textScale = if (tv || cfg.screenWidthDp >= 600) 1f else 0.8f
            CompositionLocalProvider(LocalDensity provides density, LocalCompact provides !tv, LocalTextScale provides textScale, LocalCanvasW provides (if (tv) cw else 1560f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    var screen by remember { mutableStateOf(Screen.LIB) }
                    var askBg by remember { mutableStateOf(!BackgroundAccess.asked && !BackgroundAccess.ignoringBattery(applicationContext)) }
                    LaunchedEffect(openNow) { if (openNow) { screen = Screen.NOW; openNow = false } }
                    Box(Modifier.fillMaxSize().background(C.Bg)) { BurnIn.Guard {
                        if (!tv) MobileApp(perm, { req.launch(storagePerm) }, screen, { screen = it }, { screen = Screen.SOURCES }, { screen = Screen.OUTPUT })
                        else when (screen) {
                            Screen.NOW -> if (Themes.style.value == 0) NowPlaying({ screen = it }, { screen = Screen.SOURCES }, { screen = Screen.OUTPUT })
                                else SymNow(Themes.style.value, { screen = it }) { screen = Screen.OUTPUT }
                            Screen.LIB -> LibraryScreen(perm, { req.launch(storagePerm) }, { screen = it }, { screen = Screen.SOURCES }, { screen = Screen.OUTPUT })
                            Screen.SOUND -> SoundScreen({ screen = it }, { screen = Screen.SOURCES }, { screen = Screen.OUTPUT })
                            Screen.SOURCES -> SourcesPage { screen = it }
                            Screen.OUTPUT -> OutputPage { screen = it }
                        }
                        if (Overlay.spectrum.value) SpectrumDialog { Overlay.spectrum.value = false }
                        if (Overlay.exit.value) ExitDialog(onStay = { Overlay.exit.value = false }, onExit = { Overlay.exit.value = false; exitApp() })
                        if (permDone && askBg) BackgroundDialog(onAllow = { askBg = false; askBackground() }, onLater = { askBg = false; BackgroundAccess.asked = true })
                    } }
                }
            }
        }
    }

    // Ghi nhận lần thao tác cuối (cho chế độ tự giảm sáng chống lưu ảnh OLED)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean { BurnIn.touch(); return super.dispatchKeyEvent(event) }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { BurnIn.touch(); return super.dispatchTouchEvent(ev) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { Playback.toggle(); true }
        KeyEvent.KEYCODE_MEDIA_PLAY -> { if (!Playback.playing.value) Playback.toggle(); true }
        KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (Playback.playing.value) Playback.toggle(); true }
        KeyEvent.KEYCODE_MEDIA_NEXT -> { Playback.next(); true }
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { Playback.prev(); true }
        else -> super.onKeyDown(keyCode, event)
    }
}
