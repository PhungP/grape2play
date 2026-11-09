@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.grape.play

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

object Playback {
    lateinit var exo: ExoPlayer
    val level = LevelProcessor()
    val dsp = DspProcessor()
    val queue = MutableStateFlow<List<Track>>(emptyList())
    val index = MutableStateFlow(0)
    val playing = MutableStateFlow(false)
    val shuffle = MutableStateFlow(false)
    val repeat = MutableStateFlow(Player.REPEAT_MODE_ALL)
    val position = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val info = MutableStateFlow<AudioInfo?>(null)
    val error = MutableStateFlow<String?>(null)
    val outName = MutableStateFlow("Mặc định hệ thống")
    private var probeJob: Job? = null
    /** Thiết bị stream không dây đang nhận nhạc (null = phát ra đầu ra của TV). */
    val remote = MutableStateFlow<Renderer?>(null)
    private var rc: RemoteController? = null
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var appCtx: Context

    private val retried = HashSet<String>()
    private var errCount = 0
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- Đo thời gian chuyển bài: từ lúc bấm đến lúc thật sự có tiếng; quá 3 giây thì báo đường truyền không ổn định ----
    const val SWITCH_LIMIT_MS = 3000L
    val slow = MutableStateFlow<String?>(null)
    @Volatile private var switchAt = 0L
    private fun markSwitch() { switchAt = SystemClock.elapsedRealtime() }
    private fun checkSwitch() {
        val s = switchAt; if (s == 0L) return
        switchAt = 0
        val el = SystemClock.elapsedRealtime() - s
        if (el > SWITCH_LIMIT_MS) {
            slow.value = "Tốc độ đường truyền không ổn định: chuyển bài mất %.1f giây (mong muốn dưới 3 giây)".format(el / 1000.0)
            main.launch { delay(8000); if (slow.value?.startsWith("Tốc độ") == true) slow.value = null }
        } else slow.value = null
    }

    private fun detail(e: Throwable): String {
        val sb = StringBuilder(); var t: Throwable? = e; var n = 0
        while (t != null && n < 3) { sb.append(t.javaClass.simpleName).append(": ").append((t.message ?: "").take(110)).append("  |  "); t = t.cause; n++ }
        return sb.toString()
    }
    private fun skipNext() { if (exo.hasNextMediaItem()) { exo.seekToNextMediaItem(); exo.prepare(); exo.play() } }

    private fun copyToCache(t: Track): File {
        val dir = File(appCtx.cacheDir, "nho_play").apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        val src = File(t.path)
        if (dir.usableSpace < src.length() + 64L * 1024 * 1024) throw IOException("Không đủ dung lượng bộ nhớ đệm")
        val out = File(dir, "${t.path.hashCode()}.${t.ext.ifEmpty { "bin" }}")
        src.inputStream().use { i -> out.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        return out
    }

    /** ExoPlayer cũng báo lỗi của bài đang nạp trước (bài kế tiếp); tìm đúng chỉ số bài bị lỗi. */
    private fun failingIndex(e: PlaybackException): Int? {
        val mid = (e as? ExoPlaybackException)?.mediaPeriodId ?: return null
        val tl = exo.currentTimeline
        val pi = tl.getIndexOfPeriod(mid.periodUid)
        if (pi < 0) return null
        return tl.getPeriod(pi, Timeline.Period()).windowIndex
    }

    /** Lỗi phát: hiện chi tiết; với file trên ổ USB/ổ cứng thì thử sao chép sang bộ nhớ đệm rồi phát lại; tránh vòng lặp lỗi vô tận. */
    private fun handleError(e: PlaybackException) {
        Log.e("GRAPEPlay", "Player error", e)
        val cur = exo.currentMediaItemIndex
        val fi = failingIndex(e) ?: cur
        val preload = fi != cur
        val t = queue.value.getOrNull(fi)
        val pos = exo.currentPosition
        val where = t?.let { "${it.path.takeLast(70)} @ ${if (preload) "bài kế tiếp" else fmt(pos)}" } ?: ""
        val head = "${e.errorCodeName}\n${detail(e)}\n$where"
        if (preload) { exo.prepare(); exo.play() } // tiếp tục bài đang nghe, bài lỗi xử lý ngầm
        val usb = t != null && t.path.startsWith("/storage/") && !t.path.startsWith("/storage/emulated")
        if (t != null && usb && retried.add(t.path)) {
            error.value = "Lỗi khi đọc từ ổ — đang sao chép file sang bộ nhớ đệm để thử lại…\n$head"
            ioScope.launch {
                val r = runCatching { copyToCache(t) }
                withContext(Dispatchers.Main) {
                    val f = r.getOrNull()
                    if (f != null && fi < exo.mediaItemCount) {
                        exo.replaceMediaItem(fi, MediaItem.fromUri(Uri.fromFile(f)))
                        if (!preload && exo.currentMediaItemIndex == fi) { exo.prepare(); exo.seekTo(fi, pos); exo.play() }
                        error.value = "Đã phát lại từ bản sao trong bộ nhớ đệm (lỗi gốc: ${e.errorCodeName})"
                    } else {
                        error.value = "Không sao chép được file từ ổ: ${r.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message } ?: "danh sách đã đổi"}\n$head"
                        if (!preload) skipNext()
                    }
                }
            }
            return
        }
        error.value = head
        if (preload) return
        errCount++
        if (errCount >= minOf(queue.value.size, 4)) { exo.stop(); error.value = head + "\nĐã dừng vì nhiều file liên tiếp bị lỗi."; return }
        skipNext()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (rc == null) { playing.value = isPlaying; if (isPlaying) checkSwitch() } }
        override fun onMediaItemTransition(m: MediaItem?, reason: Int) { sync() }
        override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY && exo.duration > 0) duration.value = exo.duration }
        override fun onShuffleModeEnabledChanged(b: Boolean) { shuffle.value = b }
        override fun onRepeatModeChanged(m: Int) { repeat.value = m }
        override fun onPlayerError(e: PlaybackException) { handleError(e) }
    }

    fun init(ctx: Context) {
        if (::exo.isInitialized) return
        appCtx = ctx.applicationContext
        AudioSettings.init(appCtx)
        AudioSettings.applyStartup()   // đầu ra mặc định khi mở app (nếu người dùng đã chọn)
        DspParams.init(appCtx)
        Lang.init(appCtx)
        DisplayPrefs.init(appCtx)
        Themes.init(appCtx)
        Covers.init(appCtx)
        Favorites.init(appCtx)
        Recents.init(appCtx)
        TvHome.init(appCtx)
        BackgroundAccess.init(appCtx)
        exo = build()
        // Thiết bị đầu ra cắm/rút (USB DAC, HDMI…): áp lại lựa chọn đầu ra ưu tiên.
        runCatching {
            (appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { reapplyDevice() }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { reapplyDevice() }
            }, Handler(Looper.getMainLooper()))
        }
        Keepalive.init(appCtx)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            while (true) { if (exo.isPlaying) { position.value = exo.currentPosition; if (position.value > 8000) errCount = 0; if (position.value > 4000 && error.value != null) error.value = null }; delay(250) }
        }
    }

    private fun build(): ExoPlayer {
        val rf = object : DefaultRenderersFactory(appCtx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(dsp, level))
                    .build()
        }
        rf.setEnableAudioFloatOutput(AudioSettings.hires)
        val p = ExoPlayer.Builder(appCtx, rf)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSourceFactoryHolder.factory(appCtx), DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .apply {
                if (AudioSettings.gapless)
                    setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(60_000, 90_000, 1_500, 3_000).setPrioritizeTimeOverSizeThresholds(true).build())
            }
            .build()
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.addListener(listener)
        AudioSettings.current(appCtx)?.let { runCatching { p.setPreferredAudioDevice(it) } }
        outName.value = AudioSettings.currentName(appCtx)
        return p
    }

    private fun reapplyDevice() {
        if (!::exo.isInitialized || rc != null) return
        runCatching { exo.setPreferredAudioDevice(AudioSettings.current(appCtx)) }
        outName.value = AudioSettings.currentName(appCtx)
    }

    /** Áp dụng lại cài đặt âm thanh: dựng lại bộ phát, giữ nguyên danh sách, bài và vị trí đang nghe. */
    fun applySettings() {
        val q = queue.value; val i = exo.currentMediaItemIndex; val pos = if (rc != null) position.value else exo.currentPosition
        val was = if (rc != null) playing.value else exo.isPlaying; val sh = exo.shuffleModeEnabled; val rep = exo.repeatMode
        exo.release()
        exo = build()
        exo.repeatMode = rep; exo.shuffleModeEnabled = sh
        if (q.isNotEmpty()) {
            exo.setMediaItems(q.map { MediaItem.fromUri(Remote.toUri(it)) }, i, pos)
            if (rc == null) { exo.prepare(); if (was) exo.play() }
            index.value = i
        }
        if (rc == null) playing.value = was
        shuffle.value = sh; repeat.value = rep
        remote.value?.let { outName.value = it.label }
    }

    private fun sync() {
        val i = exo.currentMediaItemIndex
        index.value = i; position.value = 0
        val t = queue.value.getOrNull(i) ?: return
        duration.value = t.durationMs; info.value = null
        Recents.add(t.path); TvHome.schedule(appCtx, 30_000)
        probeJob?.cancel()
        probeJob = CoroutineScope(Dispatchers.IO).launch { info.value = Probe.probe(t) }
    }

    fun play(tracks: List<Track>, start: Int, shuffleOn: Boolean? = null) {
        if (tracks.isEmpty()) return
        shuffleOn?.let { exo.shuffleModeEnabled = it }
        queue.value = tracks; retried.clear(); errCount = 0; markSwitch()
        exo.setMediaItems(tracks.map { MediaItem.fromUri(Remote.toUri(it)) }, start, 0L)
        if (rc != null) { sync(); loadRemote(start) } else { exo.prepare(); exo.play(); sync() }
    }
    fun playShuffled(tracks: List<Track>) = play(tracks, tracks.indices.random(), true)
    fun jump(i: Int) { markSwitch(); if (rc != null) loadRemote(i) else { exo.seekTo(i, 0L); exo.play() } }
    fun toggle() {
        val c = rc
        if (c != null) {
            if (playing.value) { playing.value = false; c.pause() }
            // Thiết bị vừa báo lỗi, hoặc chế độ DSD vừa đổi khi đang tạm dừng: nạp lại bài từ vị trí hiện tại (chỉ gửi lệnh Play thì thiết bị báo 701 "Transition not available")
            else if (error.value != null || remoteStale) loadRemote(index.value, position.value)
            else { playing.value = true; c.play() }
        }
        else if (exo.isPlaying) exo.pause() else exo.play()
    }
    fun next() {
        markSwitch()
        if (rc != null) { val n = exo.nextMediaItemIndex; if (n != C.INDEX_UNSET) loadRemote(n) } else exo.seekToNextMediaItem()
    }
    fun prev() {
        if (position.value <= 3000) markSwitch()
        if (rc != null) {
            if (position.value > 3000) { rc?.seek(0); position.value = 0 }
            else { val p = exo.previousMediaItemIndex; if (p != C.INDEX_UNSET) loadRemote(p) else { rc?.seek(0); position.value = 0 } }
        } else if (exo.currentPosition > 3000) exo.seekTo(0) else exo.seekToPreviousMediaItem()
    }
    /** Tua tới vị trí tuyệt đối (ms) — dùng cho thanh tua cảm ứng trên điện thoại/máy tính bảng. */
    fun seekTo(ms: Long) {
        val c = rc
        val d = if (duration.value > 0) duration.value else Long.MAX_VALUE
        val p = ms.coerceIn(0L, d)
        if (c != null) { position.value = p; c.seek(p) }
        else { exo.seekTo(p); position.value = p }
    }
    fun seekBy(ms: Long) {
        val c = rc
        if (c != null) {
            val d = if (duration.value > 0) duration.value else Long.MAX_VALUE
            val p = (position.value + ms).coerceIn(0L, d); position.value = p; c.seek(p)
            return
        }
        val d = if (exo.duration > 0) exo.duration else Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + ms).coerceIn(0L, d)); position.value = exo.currentPosition
    }

    // ---------- Stream không dây ----------
    /** Nạp bài thứ i lên thiết bị từ xa. ExoPlayer chỉ giữ danh sách/thứ tự (shuffle, repeat), không phát. */
    @Volatile private var remoteStale = false
    private fun loadRemote(i: Int, startMs: Long = 0L) {
        val c = rc ?: return
        remoteStale = false
        val t = queue.value.getOrNull(i) ?: return
        if (exo.currentMediaItemIndex != i) exo.seekTo(i, 0L)
        updateNext()
        index.value = i; position.value = startMs; duration.value = t.durationMs; error.value = null
        info.value = null; probeJob?.cancel()
        Recents.add(t.path); TvHome.schedule(appCtx, 30_000)
        probeJob = CoroutineScope(Dispatchers.IO).launch { info.value = Probe.probe(t) }
        playing.value = true; markSwitch()
        c.load(t, startMs)
    }

    /** Đổi cách gửi DSD khi đang phát tới thiết bị từ xa: nạp lại bài đang nghe ngay theo chế độ mới, giữ nguyên vị trí. */
    fun reloadRemoteDsd() {
        if (rc == null) return
        val i = index.value
        val t = queue.value.getOrNull(i) ?: return
        if (!StreamServer.isDsdFile(t)) return
        if (playing.value) loadRemote(i, position.value) else remoteStale = true
    }

    // ---- Gapless cho thiết bị UPnP: nạp sẵn bài kế tiếp (SetNextAVTransportURI); thiết bị tự qua bài, app chỉ cập nhật hiển thị ----
    @Volatile private var nextIdx = -1
    private fun updateNext() { nextIdx = if (rc == null) -1 else (exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: -1) }
    private fun advanceRemote(t: Track) {
        if (rc == null) return
        val q = queue.value
        val i = if (q.getOrNull(nextIdx)?.path == t.path) nextIdx else q.indexOfFirst { it.path == t.path }
        if (i < 0) return
        if (exo.currentMediaItemIndex != i) exo.seekTo(i, 0L)
        index.value = i; position.value = 0; duration.value = t.durationMs; error.value = null
        info.value = null; probeJob?.cancel()
        Recents.add(t.path); TvHome.schedule(appCtx, 30_000)
        probeJob = CoroutineScope(Dispatchers.IO).launch { info.value = Probe.probe(t) }
        playing.value = true
        updateNext()
    }

    private fun remoteEnded() {
        if (rc == null) return
        val cur = index.value
        when {
            exo.repeatMode == Player.REPEAT_MODE_ONE -> loadRemote(cur)
            exo.nextMediaItemIndex != C.INDEX_UNSET -> loadRemote(exo.nextMediaItemIndex)
            else -> { playing.value = false; position.value = 0 }
        }
    }

    /** Chọn thiết bị phát: null = quay lại đầu ra của TV. Bài đang nghe được chuyển sang, giữ nguyên vị trí. */
    fun setTarget(r: Renderer?) {
        val wasPlaying = playing.value; val pos = position.value; val i = index.value
        rc?.let { runCatching { it.stop() }; it.close() }
        rc = null
        if (r == null) {
            remote.value = null
            outName.value = AudioSettings.currentName(appCtx)
            if (queue.value.isNotEmpty()) {
                exo.prepare(); exo.seekTo(i, pos)
                if (wasPlaying) exo.play()
            }
            return
        }
        exo.stop()
        val c = if (r.kind == "cast") CastController(r) else UpnpController(r)
        c.onState = { p, ps, d -> playing.value = p; position.value = ps; if (d > 0) duration.value = d; if (p && ps > 0) main.launch { checkSwitch() } }
        c.onEnded = { main.launch { if (rc === c) remoteEnded() } }
        c.onError = { m -> main.launch { if (rc === c) { error.value = m; playing.value = false } } }
        rc = c; remote.value = r; outName.value = r.label
        if (c is UpnpController) {
            c.nextProvider = { queue.value.getOrNull(nextIdx) }
            c.onAdvanced = { t -> main.launch { if (rc === c) advanceRemote(t) } }
        }
        if (queue.value.isNotEmpty()) loadRemote(i, pos)
    }
    fun toggleShuffle() { exo.shuffleModeEnabled = !exo.shuffleModeEnabled; updateNext() }
    fun cycleRepeat() {
        exo.repeatMode = when (exo.repeatMode) { Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF; else -> Player.REPEAT_MODE_ALL }
        updateNext()
    }
}

object DataSourceFactoryHolder {
    fun factory(ctx: Context): DataSource.Factory {
        val base = DefaultDataSource.Factory(ctx, DataSource.Factory { NasDataSource() })
        return DataSource.Factory { DecodeDataSource(base.createDataSource()) }
    }
}
