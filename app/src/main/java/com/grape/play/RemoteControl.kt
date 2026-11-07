package com.grape.play

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Điều khiển một thiết bị phát từ xa. Mọi lệnh chạy bất đồng bộ trên một luồng riêng (tuần tự), nên gọi từ giao diện không bị chặn.
 * Thiết bị báo trạng thái về qua onState (mỗi ~1 giây) và onEnded khi bài hát phát hết.
 */
abstract class RemoteController {
    var onState: (playing: Boolean, posMs: Long, durMs: Long) -> Unit = { _, _, _ -> }
    var onEnded: () -> Unit = {}
    var onError: (String) -> Unit = {}

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "grape-remote").also { it.isDaemon = true } }
    protected val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    abstract fun load(t: Track, startMs: Long)
    abstract fun play()
    abstract fun pause()
    abstract fun seek(ms: Long)
    abstract fun stop()
    protected open fun onClose() {}
    /** Đóng sau khi các lệnh đang xếp hàng (ví dụ Stop) đã chạy xong. */
    fun close() { scope.launch { try { onClose() } finally { scope.cancel(); executor.shutdown() } } }

    protected fun hms(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }
    protected fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
