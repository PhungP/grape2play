package com.grape.play

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Quyền chạy ngầm: miễn tối ưu pin + quyền thông báo (cho dịch vụ nền). Android không cho xin quyền lúc cài đặt nên app hỏi ngay ở lần mở đầu tiên. */
object BackgroundAccess {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) { if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("background", Context.MODE_PRIVATE) }

    /** Đã hỏi người dùng ở lần mở đầu chưa. */
    var asked: Boolean
        get() = ::sp.isInitialized && sp.getBoolean("asked", false)
        set(v) { if (::sp.isInitialized) sp.edit().putBoolean("asked", v).apply() }

    /** Người dùng bật "giữ app chạy ngầm": dịch vụ nền luôn chạy (kể cả khi không phát nhạc). */
    var keep: Boolean
        get() = ::sp.isInitialized && sp.getBoolean("keep", false)
        set(v) { if (::sp.isInitialized) sp.edit().putBoolean("keep", v).apply() }

    fun ignoringBattery(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    fun needsNotifPerm(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /** Mở hộp thoại hệ thống "cho phép chạy ngầm"; nếu TV không có thì thử trang cài đặt tương ứng. */
    @SuppressLint("BatteryLife")
    fun requestBattery(a: Activity): Boolean {
        val pkg = a.packageName
        val tries = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        )
        for (i in tries) if (runCatching { a.startActivity(i) }.isSuccess) return true
        return false
    }
}

/** Dịch vụ nền (foreground, loại mediaPlayback): giữ app sống khi thoát ra màn hình chính để nhạc không bị ngắt. */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BackgroundAccess.keep = false
            runCatching { if (Playback.remote.value == null) Playback.exo.pause() else Playback.toggle() }
            stopForeground(true); stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification(this))
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_STOP = "com.grape.play.STOP"
        const val CHANNEL = "playback"
        const val NOTIF_ID = 7001

        @Suppress("DEPRECATION")
        fun notification(ctx: Context): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Đang phát nhạc", NotificationManager.IMPORTANCE_LOW))
            val t = Playback.queue.value.getOrNull(Playback.index.value)
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(ctx, 1, Intent(ctx, PlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(t?.title ?: "GRAPEPlay")
                .setContentText(t?.artist ?: "Đang chạy ngầm")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_media_pause, "Dừng", stop)
                .build()
        }
    }
}

/** Bật dịch vụ nền khi đang phát; tắt sau 10 phút tạm dừng. Cập nhật thông báo khi đổi bài. */
object Keepalive {
    private var stopJob: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /** Phát tới thiết bị từ xa (UPnP/Cast): TV/box là bộ điều khiển + máy chủ phát nhạc nên phải giữ CPU và Wi-Fi thức, nếu không khi tắt màn hình hệ thống sẽ làm chậm/ngắt giữa chừng. */
    @Suppress("DEPRECATION")
    private fun holdLocks(app: Context, on: Boolean) {
        runCatching {
            if (on) {
                if (wake == null) wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grapeplay:remote").also { it.setReferenceCounted(false) }
                if (wifiLock == null) wifiLock = (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "grapeplay:wifi").also { it.setReferenceCounted(false) }
                if (wake?.isHeld == false) wake?.acquire()
                if (wifiLock?.isHeld == false) wifiLock?.acquire()
            } else {
                if (wake?.isHeld == true) wake?.release()
                if (wifiLock?.isHeld == true) wifiLock?.release()
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        if (BackgroundAccess.keep) start(app)
        scope.launch {
            Playback.playing.collect { p ->
                if (p) { stopJob?.cancel(); start(app) }
                else {
                    stopJob?.cancel()
                    if (!BackgroundAccess.keep)
                        stopJob = scope.launch { delay(10 * 60_000L); if (!Playback.playing.value && !BackgroundAccess.keep) app.stopService(Intent(app, PlaybackService::class.java)) }
                }
            }
        }
        scope.launch { Playback.index.collect { if (Playback.playing.value) start(app) } }
        scope.launch {
            Playback.playing.combine(Playback.remote) { p, r -> p && r != null }.collect { holdLocks(app, it) }
        }
    }

    /** Bật dịch vụ nền ngay (dùng khi người dùng chọn "giữ app chạy ngầm"). */
    fun ensure(ctx: Context) = start(ctx.applicationContext)

    /** Tắt "giữ chạy ngầm": dịch vụ tắt nếu không đang phát (đang phát thì vẫn giữ đến khi dừng). */
    fun release(ctx: Context) {
        val app = ctx.applicationContext
        if (!Playback.playing.value) app.stopService(Intent(app, PlaybackService::class.java))
    }

    private fun start(app: Context) {
        // Android 12+ có thể từ chối khởi động dịch vụ nền khi app không ở foreground: bỏ qua lỗi, nhạc vẫn phát bình thường.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java)) }
    }
}

@Composable
fun BackgroundDialog(onAllow: () -> Unit, onLater: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onLater, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.dlgW(1000).dlgH(900).clip(RoundedCornerShape(16.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).dlgPad(48, 36),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            T("Cho phép GRAPEPlay chạy ngầm", 38, serif = true)
            if (LocalCompact.current) T("Để nhạc không bị ngắt khi bạn tắt màn hình, chuyển sang app khác hoặc hệ thống dọn bộ nhớ, app cần quyền chạy ngầm. Hệ thống sẽ hiện hộp thoại xác nhận — hãy chọn “Cho phép” (Allow).", 20, C.Muted, lines = 6)
            else T("Để nhạc không bị ngắt khi bạn thoát ra màn hình chính, chuyển sang app khác hoặc khi TV dọn bộ nhớ, app cần quyền chạy ngầm. TV sẽ hiện hộp thoại xác nhận — hãy chọn “Cho phép” (Allow).", 20, C.Muted, lines = 4)
            T("Có thể đổi lại bất cứ lúc nào ở Cài đặt ▸ Chạy ngầm.", 16, C.Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                Focusable(onLater) { T("Để sau", 20, C.Muted, Modifier.padding(26.dp, 12.dp)) }
                Focusable(onAllow, requester = first) { T("Cho phép", 20, C.Gold, Modifier.padding(26.dp, 12.dp), bold = true) }
            }
        }
    }
}
