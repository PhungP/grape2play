@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.grape.play

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Cầu nối RemoteReader (local/SMB/WebDAV/HTTP) → ByteSource thuần Java dùng cho các bộ giải mã. */
class ReaderSource(private val r: RemoteReader) : ByteSource {
    override fun length(): Long = r.size
    override fun readAt(pos: Long, b: ByteArray, off: Int, len: Int): Int = r.read(pos, b, off, len)
    override fun close() = r.close()
}

/**
 * Các định dạng mà Android 8.0 / ExoPlayer không tự giải mã được: ta giải mã bằng phần mềm thành luồng WAV PCM
 * rồi để ExoPlayer phát như file WAV. Nhận diện theo đuôi file (hoặc fragment "nho-flac" cho URL DLNA không có đuôi).
 */
object Decoders {
    const val FLAC_HINT = "nho-flac"
    private val KINDS = listOf("flac", "dsf", "dff")

    /** Gợi ý cho URL không có đuôi file (DLNA, đám mây): "nho-flac", "nho-dsf", "nho-dff". */
    fun hint(ext: String) = "nho-$ext"
    fun isDecoded(ext: String) = ext in KINDS

    fun kindOf(u: Uri): String? {
        val p = (u.path ?: "").lowercase()
        return KINDS.firstOrNull { u.fragment == hint(it) || p.endsWith(".$it") }
    }

    fun open(kind: String, src: ByteSource): PcmStream = when (kind) {
        "flac" -> FlacPcm(src)
        "dsf", "dff" -> DsdPcm(src, AudioSettings.effectiveDsdRate())
        else -> throw IOException("Không hỗ trợ định dạng $kind")
    }
}

/** Bọc DataSource gốc: file cần giải mã đi qua Decoders, còn lại giao nguyên cho DataSource gốc. */
class DecodeDataSource(private val base: DataSource) : DataSource {
    private var inner: DataSource? = null
    private var stream: PcmStream? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L

    override fun addTransferListener(transferListener: TransferListener) { base.addTransferListener(transferListener) }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val kind = Decoders.kindOf(dataSpec.uri)
        if (kind == null) { inner = base; return base.open(dataSpec) }
        val reader = Remote.reader(Remote.fromUri(dataSpec.uri))
        val s = try { Decoders.open(kind, ReaderSource(reader)) } catch (e: Exception) {
            runCatching { reader.close() }
            throw if (e is IOException) e else IOException("Giải mã $kind lỗi: ${e.message}", e)
        }
        stream = s
        val size = s.size()
        pos = dataSpec.position
        if (pos > size) { s.close(); stream = null; throw IOException("Vị trí tua vượt quá độ dài file") }
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, size - pos) else size - pos
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        inner?.let { return it.read(buffer, offset, length) }
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), remaining).toInt()
        val n = try { stream!!.read(pos, buffer, offset, want) } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("Giải mã lỗi: ${e.message}", e) }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n; remaining -= n
        return n
    }

    override fun getUri(): Uri? = inner?.uri ?: uri

    override fun close() {
        runCatching { inner?.close() }; inner = null
        runCatching { stream?.close() }; stream = null
        uri = null
    }
}
