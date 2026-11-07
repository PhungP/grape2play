package com.grape.play

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

class Tokens(val access: String, val refresh: String, val expiresIn: Long)

/** Một phiên đăng nhập đang chờ người dùng thao tác trên điện thoại. */
abstract class LoginSession {
    /** Mã người dùng phải nhập (OneDrive). Rỗng nếu không cần. */
    abstract val code: String
    /** Địa chỉ để mở/quét trên điện thoại. */
    abstract val url: String
    /** Hướng dẫn ngắn (đã dịch). */
    abstract val steps: List<String>
    abstract suspend fun await(): Tokens
    open fun cancel() {}
}

object CloudAuth {
    suspend fun start(type: String): LoginSession = withContext(Dispatchers.IO) {
        if (!Cloud.configured(type)) throw IOException("Chưa cấu hình Client ID (xem README, mục Đăng nhập đám mây)")
        if (type == "gdrive") GoogleSession.create() else MsSession.create()
    }

    fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.interfaceAddresses }?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
    }.getOrNull()
}

/** OneDrive (cá nhân + doanh nghiệp): "device code flow" của Microsoft — mở trang trên điện thoại, nhập mã. */
private class MsSession(override val code: String, override val url: String, private val deviceCode: String, private val interval: Int, private val expiresIn: Int) : LoginSession() {
    override val steps = listOf("1. Mở địa chỉ bên cạnh trên điện thoại (hoặc quét mã QR)", "2. Nhập mã của bạn (hiện ở phía trên)", "3. Đăng nhập bằng tài khoản cá nhân hoặc tài khoản trường/công ty rồi bấm Cho phép")

    override suspend fun await(): Tokens {
        var wait = interval.coerceAtLeast(3)
        val end = System.currentTimeMillis() + expiresIn * 1000L
        while (System.currentTimeMillis() < end) {
            repeat(wait) { delay(1000) }
            currentCoroutineContext().ensureActive()
            try {
                val j = withContext(Dispatchers.IO) {
                    Cloud.postForm(Cloud.MS_TOKEN, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to OAuthConfig.MS_CLIENT_ID, "device_code" to deviceCode)
                }
                return Tokens(j.getString("access_token"), j.getString("refresh_token"), j.optLong("expires_in", 3600))
            } catch (e: AuthException) {
                when (e.code) {
                    "authorization_pending" -> {}
                    "slow_down" -> wait += 5
                    "authorization_declined" -> throw IOException("Bạn đã từ chối cấp quyền")
                    "expired_token" -> throw IOException("Mã đã hết hạn, hãy thử lại")
                    else -> throw IOException(e.message ?: e.code)
                }
            }
        }
        throw IOException("Mã đã hết hạn, hãy thử lại")
    }

    companion object {
        fun create(): MsSession {
            val j = Cloud.postForm(Cloud.MS_DEVICE, "client_id" to OAuthConfig.MS_CLIENT_ID, "scope" to Cloud.MS_SCOPE)
            return MsSession(j.getString("user_code"), j.optString("verification_uri", "https://microsoft.com/devicelogin"), j.getString("device_code"), j.optInt("interval", 5), j.optInt("expires_in", 900))
        }
    }
}

/**
 * Google Drive: "device flow" của Google chỉ cho quyền drive.file nên không đọc được nhạc có sẵn → dùng luồng "ghép nối qua điện thoại":
 * TV mở một trang web nhỏ trong mạng LAN; điện thoại đăng nhập Google, trình duyệt dừng ở địa chỉ 127.0.0.1?code=… (bình thường),
 * người dùng dán địa chỉ đó vào trang của TV. TV tự đổi mã lấy token — không cần máy chủ trung gian.
 */
private class GoogleSession(private val port: Int, private val server: PairServer, private val done: CompletableDeferred<Tokens>, override val url: String) : LoginSession() {
    override val code = ""
    override val steps = listOf("1. Mở địa chỉ bên cạnh trên điện thoại (cùng Wi-Fi với TV) hoặc quét mã QR", "2. Bấm “Đăng nhập Google” và cho phép. Nếu có cảnh báo “chưa xác minh”: Nâng cao ▸ Tiếp tục", "3. Trình duyệt sẽ báo không mở được 127.0.0.1 — bình thường. Sao chép địa chỉ đó, quay lại trang này, dán và bấm Gửi")
    override suspend fun await(): Tokens = done.await()
    override fun cancel() { server.stop(); done.cancel() }

    companion object {
        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

        fun create(): GoogleSession {
            val ip = CloudAuth.lanIp() ?: throw IOException("TV chưa kết nối mạng LAN/Wi-Fi")
            val verifier = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
            val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val done = CompletableDeferred<Tokens>()
            var server: PairServer? = null
            var port = 0
            var lastErr: Exception? = null
            for (p in 8765..8785) {
                val redirect = "http://127.0.0.1:$p"
                val authUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + URLEncoder.encode(OAuthConfig.GOOGLE_CLIENT_ID, "UTF-8") +
                    "&redirect_uri=" + URLEncoder.encode(redirect, "UTF-8") + "&response_type=code&scope=" + URLEncoder.encode(Cloud.GOOGLE_SCOPE, "UTF-8") +
                    "&access_type=offline&prompt=select_account%20consent&code_challenge=$challenge&code_challenge_method=S256"
                val srv = PairServer(p) { method, path, body ->
                    if (method == "POST" && path.startsWith("/code")) {
                        val raw = URLDecoder.decode(body.substringAfter("u=", "").substringBefore('&'), "UTF-8").trim()
                        val c = Regex("[?&]code=([^&\\s]+)").find(raw)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: raw
                        if (c.length < 10) 200 to page(authUrl, "Chưa thấy mã trong nội dung đã dán. Hãy sao chép toàn bộ địa chỉ trên thanh địa chỉ.")
                        else try {
                            val j = Cloud.postForm(Cloud.GOOGLE_TOKEN, "code" to c, "client_id" to OAuthConfig.GOOGLE_CLIENT_ID, "client_secret" to OAuthConfig.GOOGLE_CLIENT_SECRET,
                                "redirect_uri" to redirect, "grant_type" to "authorization_code", "code_verifier" to verifier)
                            val rt = j.optString("refresh_token")
                            if (rt.isEmpty()) 200 to page(authUrl, "Google không trả về refresh token. Vào myaccount.google.com/permissions gỡ quyền của ứng dụng rồi thử lại.")
                            else { done.complete(Tokens(j.getString("access_token"), rt, j.optLong("expires_in", 3600))); 200 to doneHtml() }
                        } catch (e: Exception) { 200 to page(authUrl, "Đổi mã thất bại: ${e.message}. Mã chỉ dùng được một lần — bấm “Đăng nhập Google” làm lại.") }
                    } else 200 to page(authUrl, null)
                }
                try { srv.start(); server = srv; port = p; break } catch (e: Exception) { lastErr = e }
            }
            val s = server ?: throw IOException("Không mở được cổng ghép nối: ${lastErr?.message}")
            return GoogleSession(port, s, done, "http://$ip:$port")
        }

        private fun doneHtml() = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><body style='font-family:sans-serif;padding:24px'><h2>✓ Xong / Done</h2><p>Quay lại màn hình TV. / Return to the TV.</p>"

        private fun page(authUrl: String, err: String?): String = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<body style='font-family:sans-serif;padding:20px;max-width:640px;margin:auto;line-height:1.5'>" +
            "<h2>GRAPE2Play · Google Drive</h2>" + (if (err != null) "<p style='color:#b00020'><b>${esc(err)}</b></p>" else "") +
            "<p><b>1.</b> <a target=_blank rel=noopener href=\"${esc(authUrl)}\" style='display:inline-block;padding:12px 20px;background:#1a73e8;color:#fff;border-radius:8px;text-decoration:none'>Đăng nhập Google / Sign in with Google</a></p>" +
            "<p style='color:#444'>Nếu thấy “Google chưa xác minh ứng dụng này”: Nâng cao ▸ Tiếp tục. / If you see “Google hasn’t verified this app”: Advanced ▸ Continue.</p>" +
            "<p><b>2.</b> Sau khi cho phép, trình duyệt báo không mở được <code>127.0.0.1</code> — bình thường. Sao chép <b>toàn bộ địa chỉ</b> trên thanh địa chỉ rồi dán vào ô dưới. / The browser will fail to open <code>127.0.0.1</code> — that is expected. Copy the <b>full address</b> from the address bar and paste it below.</p>" +
            "<form method=post action=/code><textarea name=u rows=5 style='width:100%;font-size:16px' placeholder='http://127.0.0.1:8765/?code=…'></textarea><p><button style='font-size:18px;padding:10px 24px'>Gửi / Send</button></p></form>"
    }
}

/** Máy chủ HTTP tối giản (chỉ dùng trong lúc ghép nối, trong mạng LAN). */
class PairServer(private val port: Int, private val handler: (method: String, path: String, body: String) -> Pair<Int, String>) {
    private var ss: ServerSocket? = null
    @Volatile private var closed = false

    fun start() {
        val s = ServerSocket(port); s.reuseAddress = true; ss = s
        thread(isDaemon = true, name = "grape-pair") {
            while (!closed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { serve(c) }
            }
        }
    }
    fun stop() { closed = true; runCatching { ss?.close() } }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun serve(c: Socket) {
        try {
            c.use {
                it.soTimeout = 10_000
                val inp = it.getInputStream().buffered()
                val parts = (readLine(inp) ?: return).split(' ')
                var len = 0
                while (true) { val h = readLine(inp) ?: break; if (h.isEmpty()) break; if (h.startsWith("content-length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0 }
                val buf = ByteArray(minOf(len, 65536)); var got = 0
                while (got < buf.size) { val n = inp.read(buf, got, buf.size - got); if (n < 0) break; got += n }
                val (code, html) = handler(parts[0], parts.getOrElse(1) { "/" }, String(buf, 0, got, Charsets.UTF_8))
                val bytes = html.toByteArray(Charsets.UTF_8)
                val out = it.getOutputStream()
                out.write("HTTP/1.1 $code OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(bytes); out.flush()
            }
        } catch (e: Exception) { }
    }
}
