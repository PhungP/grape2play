package com.grape.play

import android.net.Uri
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class CloudFile(val path: String, val name: String, val dir: String)
data class CloudItem(val id: String, val name: String, val folder: Boolean, val mime: String = "")

/** Lỗi OAuth do máy chủ trả về (error + error_description). */
class AuthException(val code: String, msg: String) : IOException(msg)

/**
 * Google Drive ("gdrive") và OneDrive ("onedrive", cá nhân + doanh nghiệp).
 * Source: pass = refresh token, user = email tài khoản, root = id thư mục gốc đã chọn ("" = toàn bộ), host = "personal"/"work".
 * Đường dẫn bài hát: gd://{sourceId}/{fileId}  hoặc  od://{sourceId}/{itemId}.
 */
object Cloud {
    const val GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
    const val MS_TOKEN = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    const val MS_DEVICE = "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode"
    const val MS_SCOPE = "offline_access Files.Read User.Read"
    const val GOOGLE_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

    private class Tok(val value: String, val exp: Long)
    private val cache = HashMap<String, Tok>()

    fun isCloud(type: String) = type == "gdrive" || type == "onedrive"
    fun configured(type: String) = if (type == "gdrive") OAuthConfig.GOOGLE_CLIENT_ID.isNotBlank() && OAuthConfig.GOOGLE_CLIENT_SECRET.isNotBlank() else OAuthConfig.MS_CLIENT_ID.isNotBlank()
    private fun title(type: String) = if (type == "gdrive") "Google Drive" else "OneDrive"

    fun postForm(url: String, vararg kv: Pair<String, String>): JSONObject {
        val b = FormBody.Builder(); kv.forEach { b.add(it.first, it.second) }
        Http.client.newCall(Request.Builder().url(url).post(b.build()).build()).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            val j = runCatching { JSONObject(txt) }.getOrNull() ?: JSONObject()
            if (!r.isSuccessful) throw AuthException(j.optString("error", "http_${r.code}"), j.optString("error_description").ifEmpty { j.optString("error", "HTTP ${r.code}") }.lineSequence().first())
            return j
        }
    }

    @Synchronized fun prime(id: String, access: String, expiresInSec: Long) { cache[id] = Tok(access, System.currentTimeMillis() + expiresInSec * 1000) }
    @Synchronized fun forget(id: String) { cache.remove(id) }

    /** Access token còn hạn của nguồn; tự làm mới bằng refresh token (và lưu refresh token mới nếu máy chủ xoay vòng). */
    @Synchronized fun access(s0: Source): String {
        val s = SourceStore.get(s0.id) ?: s0
        cache[s.id]?.let { if (it.exp > System.currentTimeMillis() + 60_000) return it.value }
        val j = try {
            if (s.type == "gdrive") postForm(GOOGLE_TOKEN, "client_id" to OAuthConfig.GOOGLE_CLIENT_ID, "client_secret" to OAuthConfig.GOOGLE_CLIENT_SECRET,
                "refresh_token" to s.pass, "grant_type" to "refresh_token")
            else postForm(MS_TOKEN, "client_id" to OAuthConfig.MS_CLIENT_ID, "refresh_token" to s.pass, "grant_type" to "refresh_token", "scope" to MS_SCOPE)
        } catch (e: AuthException) {
            if (e.code == "invalid_grant" || e.code == "interaction_required") throw IOException("Phiên đăng nhập ${title(s.type)} đã hết hạn. Hãy xoá nguồn này rồi đăng nhập lại.")
            throw e
        }
        val at = j.getString("access_token")
        cache[s.id] = Tok(at, System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
        val nr = j.optString("refresh_token")
        if (nr.isNotEmpty() && nr != s.pass) SourceStore.update(s.copy(pass = nr))
        return at
    }
    fun bearer(s: Source) = "Bearer " + access(s)

    private fun getJson(s: Source, url: String): JSONObject {
        var last: IOException? = null
        repeat(3) { attempt ->
            Http.client.newCall(Request.Builder().url(url).header("Authorization", bearer(s)).build()).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> return JSONObject(txt)
                    r.code == 401 -> { forget(s.id); last = IOException("${title(s.type)}: token bị từ chối (401)") }
                    r.code == 429 || r.code >= 500 -> last = IOException("${title(s.type)} đang bận (${r.code})")
                    else -> throw IOException("${title(s.type)} lỗi ${r.code}: ${txt.take(140)}")
                }
                Unit
            }
            Thread.sleep(1200L * (attempt + 1))
        }
        throw last ?: IOException("${title(s.type)} không phản hồi")
    }

    /** Liệt kê con trực tiếp của một thư mục (gdrive: "" hoặc "root" = gốc; onedrive: "" = gốc). */
    fun list(s: Source, folderId: String): List<CloudItem> {
        val out = ArrayList<CloudItem>()
        if (s.type == "gdrive") {
            val q = "'${folderId.ifEmpty { "root" }}' in parents and trashed=false"
            val base = "https://www.googleapis.com/drive/v3/files?pageSize=1000&orderBy=folder,name&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                "&fields=" + Uri.encode("nextPageToken,files(id,name,mimeType)") + "&q=" + Uri.encode(q)
            var token: String? = null
            do {
                val j = getJson(s, if (token == null) base else base + "&pageToken=" + Uri.encode(token))
                val a = j.optJSONArray("files")
                for (i in 0 until (a?.length() ?: 0)) {
                    val o = a!!.getJSONObject(i); val m = o.optString("mimeType")
                    out += CloudItem(o.getString("id"), o.optString("name"), m == "application/vnd.google-apps.folder", m)
                }
                token = j.optString("nextPageToken").ifEmpty { null }
            } while (token != null)
        } else {
            val head = if (folderId.isEmpty()) "https://graph.microsoft.com/v1.0/me/drive/root/children" else "https://graph.microsoft.com/v1.0/me/drive/items/${Uri.encode(folderId)}/children"
            var url: String? = "$head?\$top=200&\$select=id,name,folder,file"
            while (url != null) {
                val j = getJson(s, url)
                val a = j.optJSONArray("value")
                for (i in 0 until (a?.length() ?: 0)) {
                    val o = a!!.getJSONObject(i)
                    out += CloudItem(o.getString("id"), o.optString("name"), o.has("folder"), o.optJSONObject("file")?.optString("mimeType").orEmpty())
                }
                url = j.optString("@odata.nextLink").ifEmpty { null }
            }
        }
        return out
    }

    private fun extFor(name: String, mime: String): String {
        val e = name.substringAfterLast('.', "").lowercase()
        if (e in AUDIO_EXTS) return e
        return when {
            mime.contains("flac") -> "flac"
            mime.contains("mpeg") -> "mp3"
            mime.contains("wav") -> "wav"
            mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
            mime.contains("ogg") -> "ogg"
            else -> ""
        }
    }
    fun extOf(name: String) = extFor(name, "")

    /** Quét toàn bộ cây thư mục (không đệ quy). Lỗi ở thư mục gốc được ném ra; lỗi ở thư mục con thì bỏ qua thư mục đó. */
    fun walk(s: Source, cancelled: () -> Boolean, progress: (folders: Int, files: Int) -> Unit, sink: (CloudFile) -> Unit): Int {
        val prefix = if (s.type == "gdrive") "gd" else "od"
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(s.root to "")
        var folders = 0; var files = 0; var failed = 0
        while (stack.isNotEmpty()) {
            if (cancelled() || folders > 8000) break
            val (id, dir) = stack.removeLast()
            val items = try { list(s, id) } catch (e: Exception) { if (folders == 0) throw e; failed++; continue }
            folders++
            for (x in items) {
                if (x.folder) stack.addLast(x.id to (if (dir.isEmpty()) x.name else "$dir/${x.name}"))
                else if (extFor(x.name, x.mime).isNotEmpty() && (isAudioName(x.name) || x.mime.startsWith("audio/"))) {
                    files++; sink(CloudFile("$prefix://${s.id}/${x.id}", x.name, dir))
                }
            }
            progress(folders, files)
        }
        return failed
    }

    /** Đọc nội dung file (hỗ trợ Range/tua), tự gắn token mới nhất cho mỗi lần mở kết nối. */
    fun reader(path: String): RemoteReader {
        val scheme = path.substringBefore("://")
        val rest = path.substringAfter("://")
        val sid = rest.substringBefore('/'); val fid = rest.substringAfter('/', "")
        val s = SourceStore.get(sid) ?: throw IOException("Nguồn đã bị xoá")
        val url = if (scheme == "gd") "https://www.googleapis.com/drive/v3/files/${Uri.encode(fid)}?alt=media&supportsAllDrives=true"
        else "https://graph.microsoft.com/v1.0/me/drive/items/${Uri.encode(fid)}/content"
        return HttpReader(url, null) { bearer(s) }
    }

    /** (nhãn tài khoản, loại "personal"/"work") */
    fun profile(type: String, access: String): Pair<String, String> {
        fun get(url: String): JSONObject = Http.client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $access").build()).execute().use {
            if (!it.isSuccessful) throw IOException("Không đọc được thông tin tài khoản (${it.code})")
            JSONObject(it.body!!.string())
        }
        if (type == "gdrive") {
            val u = get("https://www.googleapis.com/drive/v3/about?fields=" + Uri.encode("user(emailAddress,displayName)")).optJSONObject("user")
            val mail = u?.optString("emailAddress").orEmpty().ifEmpty { u?.optString("displayName").orEmpty() }
            return mail to (if (mail.endsWith("@gmail.com") || mail.endsWith("@googlemail.com")) "personal" else "work")
        }
        val me = get("https://graph.microsoft.com/v1.0/me?\$select=userPrincipalName,displayName,mail")
        val name = me.optString("mail").ifEmpty { me.optString("userPrincipalName") }.ifEmpty { me.optString("displayName") }
        val kind = runCatching { get("https://graph.microsoft.com/v1.0/me/drive?\$select=driveType").optString("driveType") }.getOrDefault("")
        return name to (if (kind == "personal") "personal" else "work")
    }
}
