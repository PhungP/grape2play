package com.grape.play

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** Thùng rác Windows đổi tên thư mục/file thành $R+mã; tên gốc nằm trong file $I+mã cùng thư mục. */
object Recycle {
    private val rx = Regex("^[$]R([A-Za-z0-9]{6,7})(\\..+)?$")
    private val cache = ConcurrentHashMap<String, String>()

    fun display(parent: String, seg: String): String = when {
        seg.equals("\$RECYCLE.BIN", true) -> "Thùng rác Windows"
        seg.startsWith("S-1-5-") -> "Người dùng"
        else -> cache.getOrPut("$parent/$seg") { original(parent, seg) }
    }

    private fun original(parent: String, seg: String): String {
        val m = rx.find(seg) ?: return seg
        return try {
            val b = File(parent, "\$I" + m.groupValues[1] + m.groupValues[2]).readBytes()
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val ver = bb.getLong(0)
            val s = if (ver == 2L) { val n = bb.getInt(24); String(b, 28, (n * 2).coerceAtMost(b.size - 28), Charsets.UTF_16LE) } else String(b, 24, b.size - 24, Charsets.UTF_16LE)
            s.trim('\u0000').substringAfterLast('\\').ifEmpty { seg }
        } catch (e: Exception) { seg }
    }

    /** Đường dẫn thư mục hiển thị (đã khôi phục tên gốc) cho file trong thùng rác; rỗng nếu file không nằm trong thùng rác. */
    fun displayDir(path: String): String {
        if (!path.contains("/\$R") && !path.contains("/\$RECYCLE")) return ""
        val root = localRoot(path) ?: return ""
        val segs = path.removePrefix(root).trim('/').split('/').dropLast(1)
        var cur = root
        val out = ArrayList<String>()
        for (s in segs) { out += display(cur, s); cur = "$cur/$s" }
        return out.joinToString("/")
    }
}
