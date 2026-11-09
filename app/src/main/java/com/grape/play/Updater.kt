package com.grape.play

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cập nhật ứng dụng qua GitHub Releases.
 * Quy ước: mỗi bản phát hành có tag dạng "v0.8.2" (hoặc "0.8.2") và đính kèm đúng một file .apk.
 * App hỏi https://api.github.com/repos/REPO/releases/latest, so sánh tag với versionName đang chạy,
 * tải APK vào bộ nhớ đệm rồi mở trình cài đặt của Android. Repo phải để PUBLIC.
 * APK mới phải ký cùng khoá với bản đang cài, nếu không Android sẽ từ chối cài đè.
 */
object Updater {
    const val REPO = "PhungP/grape2play"

    enum class St { IDLE, CHECKING, UPTODATE, AVAILABLE, DOWNLOADING, READY, ERROR }
    class Release(val version: String, val url: String, val size: Long, val notes: String)

    val state = mutableStateOf(St.IDLE)
    val release = mutableStateOf<Release?>(null)
    val progress = mutableStateOf(0)
    val error = mutableStateOf("")
    private var apk: File? = null
    private var autoDone = false
    /** true = hiện cửa sổ thông báo có bản mới (đặt khi tự kiểm tra thấy bản mới; tắt khi người dùng chọn "Để sau"). */
    val prompt = mutableStateOf(false)

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** true nếu remote mới hơn local (so từng số: 0.8.10 > 0.8.9). */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().trimStart { !it.isDigit() }.split('.', '-', '+')
            .map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Trả về bản mới nhất, hoặc null nếu repo chưa có release / release chưa có file .apk. */
    private fun fetchLatest(): Release? {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "GRAPEPlay-Updater").build()
        Http.client.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val j = JSONObject(r.body?.string().orEmpty())
            val tag = j.optString("tag_name").trim().trimStart { !it.isDigit() }   // chịu được "v0.8.3", "v.0.8.3", "V0.8.3"...
            val assets = j.optJSONArray("assets")
            var url = ""; var size = 0L
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) { url = a.optString("browser_download_url"); size = a.optLong("size"); break }
            }
            if (tag.isEmpty() || url.isEmpty()) return null
            return Release(tag, url, size, j.optString("body").trim())
        }
    }

    suspend fun check(ctx: Context) {
        if (state.value == St.CHECKING || state.value == St.DOWNLOADING) return
        state.value = St.CHECKING; error.value = ""
        try {
            val rel = withContext(Dispatchers.IO) { fetchLatest() }
            release.value = rel
            state.value = if (rel != null && isNewer(rel.version, currentVersion(ctx))) St.AVAILABLE else St.UPTODATE
        } catch (e: Exception) {
            error.value = e.message ?: e.javaClass.simpleName; state.value = St.ERROR
        }
    }

    /** Tự kiểm tra một lần mỗi lần mở app (im lặng nếu lỗi mạng). */
    suspend fun autoCheck(ctx: Context) {
        if (autoDone || state.value != St.IDLE) return
        autoDone = true
        check(ctx)
        if (state.value == St.ERROR) { state.value = St.IDLE; error.value = "" }
        if (state.value == St.AVAILABLE) prompt.value = true
    }

    private fun download(ctx: Context, rel: Release): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "GRAPEPlay-${rel.version}.apk")
        val c = Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        c.newCall(Request.Builder().url(rel.url).header("User-Agent", "GRAPEPlay-Updater").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: rel.size
            body.byteStream().use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        os.write(buf, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; progress.value = p } }
                    }
                }
            }
        }
        if (rel.size > 0 && out.length() != rel.size) { out.delete(); throw IOException("File tải về bị thiếu dữ liệu") }
        val info = ctx.packageManager.getPackageArchiveInfo(out.path, 0)
        if (info == null || info.packageName != ctx.packageName) { out.delete(); throw IOException("File tải về không phải bản cài của GRAPEPlay") }
        return out
    }

    /** Tải APK rồi mở trình cài đặt. Trả về thông báo lỗi/hướng dẫn nếu cần, null nếu ổn. */
    suspend fun downloadAndInstall(ctx: Context): String? {
        val rel = release.value ?: return null
        state.value = St.DOWNLOADING; progress.value = 0; error.value = ""
        return try {
            apk = withContext(Dispatchers.IO) { download(ctx, rel) }
            state.value = St.READY
            install(ctx)
        } catch (e: Exception) {
            error.value = e.message ?: e.javaClass.simpleName; state.value = St.ERROR; null
        }
    }

    fun install(ctx: Context): String? {
        val f = apk?.takeIf { it.exists() } ?: return "Chưa có file cập nhật, hãy tải lại."
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try { ctx.startActivity(i); "Hãy bật “Cho phép từ nguồn này” cho GRAPEPlay, quay lại rồi bấm Cài đặt ngay." }
            catch (e: Exception) { "TV chưa cho phép cài từ nguồn ngoài. Vào Cài đặt TV ▸ Bảo mật và hạn chế ▸ Nguồn không xác định, bật cho GRAPEPlay." }
        }
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: Exception) { "Không mở được trình cài đặt: ${e.message}" }
    }
}

private fun mb(n: Long) = "%.1f MB".format(n / 1048576.0)

@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state
    val rel by Updater.release
    val pct by Updater.progress
    val err by Updater.error
    var msg by remember { mutableStateOf("") }
    val cur = remember { Updater.currentVersion(ctx) }
    LaunchedEffect(Unit) { Updater.autoCheck(ctx) }

    @Composable fun Btn(text: String, onClick: () -> Unit) =
        Focusable(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) { T(text, 18, C.Gold, Modifier.padding(14.dp, 9.dp), bold = true) }

    Card {
        T("CẬP NHẬT ỨNG DỤNG", 13, C.Muted, spaced = true)
        T("Đang dùng bản: $cur", 18, C.Text)
        when (st) {
            Updater.St.IDLE -> T("Chưa kiểm tra cập nhật.", 15, C.Muted)
            Updater.St.CHECKING -> T("Đang kiểm tra cập nhật…", 15, C.Muted)
            Updater.St.UPTODATE -> {
                val r = rel
                if (r != null) {
                    T("✓ Bạn đang dùng bản mới nhất.", 17, Grade.GREEN.color)
                    T("Bản mới nhất trên GitHub: ${r.version}", 14, C.Muted)
                } else T("Chưa thấy bản phát hành hợp lệ trên GitHub (cần Release công khai, có đính kèm file .apk).", 15, C.Gold, lines = 3)
            }
            Updater.St.AVAILABLE -> {
                val r = rel
                if (r != null) {
                    T("Có bản mới: ${r.version}" + (if (r.size > 0) " · ${mb(r.size)}" else ""), 18, C.Gold, bold = true)
                    if (r.notes.isNotEmpty()) T(r.notes.lines().filter { it.isNotBlank() }.take(4).joinToString("\n"), 14, C.Muted, lines = 4)
                }
            }
            Updater.St.DOWNLOADING -> T("Đang tải… $pct%", 17, C.Gold)
            Updater.St.READY -> T("✓ Đã tải xong, sẵn sàng cài đặt.", 17, Grade.GREEN.color)
            Updater.St.ERROR -> T("⚠ Lỗi: $err", 15, C.Gold, lines = 3)
        }
        if (msg.isNotEmpty()) T(msg, 14, C.Gold, lines = 3)
        when (st) {
            Updater.St.AVAILABLE -> Btn("Tải và cài đặt") { msg = ""; scope.launch { msg = Updater.downloadAndInstall(ctx).orEmpty() } }
            Updater.St.READY -> Btn("Cài đặt ngay") { msg = Updater.install(ctx).orEmpty() }
            Updater.St.CHECKING, Updater.St.DOWNLOADING -> {}
            else -> Btn("Kiểm tra cập nhật") { msg = ""; scope.launch { Updater.check(ctx) } }
        }
        T("Cập nhật được tải từ trang phát hành GitHub của GRAPEPlay. Bản mới sẽ cài đè, không mất nguồn nhạc và cài đặt.", 13, C.Muted, lines = 3)
    }
}

/** Cửa sổ thông báo khi có bản mới: tự hiện sau khi mở app. Nút "Để sau" đóng cửa sổ (lần mở app sau sẽ nhắc lại). */
@Composable
fun UpdateDialog() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prompt by Updater.prompt
    val st by Updater.state
    val rel by Updater.release
    val pct by Updater.progress
    val err by Updater.error
    var msg by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val cur = remember { Updater.currentVersion(ctx) }
    LaunchedEffect(Unit) { Updater.autoCheck(ctx) }
    val r = rel
    if (!prompt || r == null || st == Updater.St.UPTODATE || st == Updater.St.IDLE || st == Updater.St.CHECKING) return
    val compact = LocalCompact.current
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val big = compact
    fun f(n: Int) = if (big) n else (n * 0.62f).toInt().coerceAtLeast(10)
    Dialog(onDismissRequest = { if (st != Updater.St.DOWNLOADING) Updater.prompt.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            (if (compact) Modifier.dlgW(520) else Modifier.dlgFrac(0.4f)).clip(RoundedCornerShape(14.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(14.dp)).dlgPad(26, 18),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            T("CẬP NHẬT ỨNG DỤNG", f(13), C.Muted, spaced = true)
            T("Có bản mới ${r.version}", f(30), serif = true, bold = true)
            T("Bạn đang dùng bản $cur" + (if (r.size > 0) " · dung lượng ${mb(r.size)}" else ""), f(17), C.Muted, lines = 2)
            if (r.notes.isNotEmpty()) T(r.notes.lines().filter { it.isNotBlank() }.take(5).joinToString("\n"), f(16), C.Text, lines = 5)
            when (st) {
                Updater.St.DOWNLOADING -> T("Đang tải… $pct%", f(18), C.Gold, bold = true)
                Updater.St.READY -> T("✓ Đã tải xong, sẵn sàng cài đặt.", f(17), Grade.GREEN.color)
                Updater.St.ERROR -> T("⚠ Lỗi: $err", f(15), C.Gold, lines = 3)
                else -> {}
            }
            if (msg.isNotEmpty()) T(msg, f(15), C.Gold, lines = 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End)) {
                if (st != Updater.St.DOWNLOADING) Focusable({ Updater.prompt.value = false }) { T("Để sau", f(18), C.Muted, Modifier.padding(if (big) 22.dp else 14.dp, if (big) 10.dp else 6.dp), bold = true) }
                if (st != Updater.St.DOWNLOADING) Focusable({
                    msg = ""
                    if (st == Updater.St.READY) msg = Updater.install(ctx).orEmpty()
                    else scope.launch { msg = Updater.downloadAndInstall(ctx).orEmpty() }
                }, requester = first) {
                    T(if (st == Updater.St.READY) "Cài đặt ngay" else if (st == Updater.St.ERROR) "Thử lại" else "Cập nhật ngay", f(18), C.Gold, Modifier.padding(if (big) 22.dp else 14.dp, if (big) 10.dp else 6.dp), bold = true)
                }
            }
        }
    }
}

/**
 * Cửa sổ "Đã cập nhật lên bản X": hiện một lần đầu tiên mở app sau khi cài bản mới (lần đầu cài thì không hiện).
 * Kèm ghi chú phát hành nếu lấy được từ GitHub.
 */
@Composable
fun UpdatedDialog() {
    val ctx = LocalContext.current
    val cur = remember { Updater.currentVersion(ctx) }
    val sp = remember { ctx.getSharedPreferences("updater", Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(false) }
    val rel by Updater.release
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        val seen = sp.getString("seen", null)
        if (seen == null) sp.edit().putString("seen", cur).apply() else if (seen != cur) show = true
    }
    if (!show) return
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val notes = rel?.takeIf { Updater.currentVersion(ctx).trim() == it.version.trim() }?.notes.orEmpty()
    val big = LocalCompact.current
    fun f(n: Int) = if (big) n else (n * 0.62f).toInt().coerceAtLeast(10)
    fun close() { sp.edit().putString("seen", cur).apply(); show = false }
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            (if (big) Modifier.dlgW(520) else Modifier.dlgFrac(0.4f)).clip(RoundedCornerShape(14.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(14.dp)).dlgPad(26, 18),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            T("CẬP NHẬT ỨNG DỤNG", f(13), C.Muted, spaced = true)
            T("✓ Đã cập nhật lên bản $cur", f(28), serif = true, bold = true, lines = 2)
            if (notes.isNotEmpty()) T(notes.lines().filter { it.isNotBlank() }.take(6).joinToString("\n"), f(16), C.Text, lines = 6)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Focusable({ close() }, requester = first) { T("Đóng", f(18), C.Gold, Modifier.padding(if (big) 22.dp else 14.dp, if (big) 10.dp else 6.dp), bold = true) }
            }
        }
    }
}
