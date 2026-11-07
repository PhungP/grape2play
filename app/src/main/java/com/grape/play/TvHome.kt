package com.grape.play

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateOf
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileNotFoundException

/**
 * Hàng "GRAPEPlay" trên màn hình chính Android TV (kênh xem trước / preview channel):
 * bài yêu thích + phát gần đây kèm ảnh bìa; bấm vào là phát ngay trong app.
 */
object TvHome {
    const val EXTRA_PATH = "grape_path"
    const val EXTRA_LIST = "grape_list"   // "fav" | "recent"
    const val AUTHORITY = "com.grape.play.covers"
    private const val MAX = 20

    val enabled = mutableStateOf(false)
    private lateinit var sp: SharedPreferences
    private var job: Job? = null

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("tvhome", Context.MODE_PRIVATE)
        enabled.value = sp.getBoolean("on", false)
    }

    private fun channelId(): Long = if (::sp.isInitialized) sp.getLong("chId", -1L) else -1L

    suspend fun enable(ctx: Context) {
        val app = ctx.applicationContext
        enabled.value = true
        sp.edit().putBoolean("on", true).apply()
        withContext(Dispatchers.IO) { runCatching { refresh(app) } }
    }

    suspend fun disable(ctx: Context) {
        val app = ctx.applicationContext
        enabled.value = false
        sp.edit().putBoolean("on", false).apply()
        withContext(Dispatchers.IO) {
            val id = channelId()
            if (id > 0) runCatching { app.contentResolver.delete(TvContractCompat.buildChannelUri(id), null, null) }
            sp.edit().remove("chId").apply()
        }
    }

    /** Hỏi người dùng có hiện kênh này trên màn hình chính không (hộp thoại của hệ thống). */
    fun requestBrowsable(a: android.app.Activity) {
        val id = channelId()
        if (id > 0) runCatching { TvContractCompat.requestChannelBrowsable(a, id) }
    }

    /** Cập nhật kênh sau `delayMs` (gộp nhiều lần gọi liên tiếp thành một). */
    fun schedule(ctx: Context, delayMs: Long) {
        if (!enabled.value) return
        val app = ctx.applicationContext
        job?.cancel()
        job = Library.scope.launch { delay(delayMs); runCatching { refresh(app) } }
    }

    private fun channelExists(ctx: Context, id: Long): Boolean = runCatching {
        ctx.contentResolver.query(TvContractCompat.buildChannelUri(id), arrayOf("_id"), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    private fun ensureChannel(ctx: Context): Long? {
        val saved = channelId()
        if (saved > 0 && channelExists(ctx, saved)) return saved
        val b = Channel.Builder()
        b.setType(TvContractCompat.Channels.TYPE_PREVIEW)
        b.setDisplayName("GRAPEPlay")
        b.setAppLinkIntent(Intent(ctx, MainActivity::class.java))
        val uri = ctx.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, b.build().toContentValues()) ?: return null
        val id = ContentUris.parseId(uri)
        sp.edit().putLong("chId", id).apply()
        runCatching { BitmapFactory.decodeResource(ctx.resources, R.drawable.banner)?.let { ChannelLogoUtils.storeChannelLogo(ctx, id, it) } }
        return id
    }

    /** Ghi ảnh bìa ra file nhỏ và trả về content:// cho launcher của TV đọc (qua CoverProvider). */
    private suspend fun posterUri(ctx: Context, t: Track): Uri? {
        val bmp = Covers.get(t) ?: return null
        val name = Integer.toHexString((t.src + "|" + t.folder + "|" + t.album).hashCode()) + ".jpg"
        val dir = File(ctx.filesDir, "tvcovers").apply { mkdirs() }
        val f = File(dir, name)
        if (!f.exists()) runCatching {
            val k = 480f / maxOf(bmp.width, bmp.height)
            val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
            f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }
        return if (f.exists()) Uri.parse("content://$AUTHORITY/$name") else null
    }

    suspend fun refresh(ctx: Context) {
        if (!enabled.value) return
        val cr = ctx.contentResolver
        val chId = ensureChannel(ctx) ?: return
        val all = Library.tracks.value
        val favs = Favorites.tracks(all)
        val recents = Recents.tracks(all)
        val items = ArrayList<Pair<Track, String>>()
        favs.take(12).forEach { items.add(it to "fav") }
        for (r in recents) { if (items.size >= MAX) break; if (items.none { it.first.path == r.path }) items.add(r to "recent") }
        cr.delete(TvContractCompat.buildPreviewProgramsUriForChannel(chId), null, null)
        for ((t, list) in items) {
            val intent = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_PATH, t.path).putExtra(EXTRA_LIST, list)
            val b = PreviewProgram.Builder()
            b.setChannelId(chId)
            b.setType(TvContractCompat.PreviewProgramColumns.TYPE_TRACK)
            b.setTitle(t.title)
            b.setDescription(if (list == "fav") t.artist + " · ♥" else t.artist)
            b.setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_1_1)
            b.setIntent(intent)
            posterUri(ctx, t)?.let { b.setPosterArtUri(it) }
            runCatching { cr.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, b.build().toContentValues()) }
        }
    }
}

/** Cho launcher của TV đọc ảnh bìa trong filesDir/tvcovers (chỉ file .jpg do app tạo ra). */
class CoverProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!Regex("[0-9a-f]{1,16}\\.jpg").matches(name)) throw FileNotFoundException()
        val f = File(File(context!!.filesDir, "tvcovers"), name)
        if (!f.exists()) throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
