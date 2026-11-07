package com.grape.play

val AUDIO_EXTS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "oga", "opus", "dsf", "dff")
fun isAudioName(n: String) = n.substringAfterLast('.', "").lowercase() in AUDIO_EXTS

data class Track(
    val path: String, val title: String, val artist: String, val album: String,
    val genre: String, val durationMs: Long, val no: Int = 0,
    val src: String = "local", val format: String = "", val art: String = "", val dir: String = "",
    val sr: Int = 0, val bits: Int = 0, val ch: Int = 0, val kbps: Int = 0,
) {
    val folder: String get() = if (dir.isNotEmpty()) "$src|$dir" else path.substringBeforeLast('/')
    val ext: String get() = if (format.isNotEmpty()) format else path.substringAfterLast('.', "").lowercase().let { if (it.length in 1..5) it else "" }
    val sourceName: String get() = if (src == "local") (if (path.startsWith("/storage/emulated") || path.startsWith("/sdcard")) (if (Device.tv) "Bộ nhớ TV" else "Bộ nhớ máy") else "USB") else SourceStore.name(src)
}

data class AudioInfo(val sampleRate: Int, val bits: Int, val channels: Int, val bitrateKbps: Int)
data class Group(val key: String, val title: String, val subtitle: String, val tracks: List<Track>)
enum class Tab(val label: String) { ALBUM("Album"), ARTIST("Nghệ sĩ"), GENRE("Thể loại"), FOLDER("Thư mục"), SONG("Bài hát"), FAV("Yêu thích") }
/** Thiết bị đang chạy: TV hay điện thoại/máy tính bảng (đặt ở MainActivity). */
object Device { @Volatile var tv = true }
enum class Screen { NOW, LIB, SOUND, SOURCES, OUTPUT }

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%02d:%02d".format(s / 60, s % 60) }
fun khz(sr: Int) = if (sr <= 0) "—" else if (sr % 1000 == 0) "${sr / 1000} kHz" else "%.1f kHz".format(sr / 1000.0)
/** Nhãn định dạng ngắn trên bìa album: "FLAC 24/96", "DSD64"... */
fun formatBadge(t: Track): String {
    val e = t.ext.uppercase()
    if (t.ext == "dsf" || t.ext == "dff") return if (t.sr > 0) "DSD" + (t.sr / 44100) else "DSD"
    if (t.bits > 0 && t.sr > 0 && t.ext in setOf("flac", "wav", "alac", "aif", "aiff")) return "$e ${t.bits}/${if (t.sr % 1000 == 0) t.sr / 1000 else "%.1f".format(t.sr / 1000.0)}"
    if (t.kbps > 0 && t.ext == "mp3") return "$e ${t.kbps}"
    return e
}
fun channelName(n: Int) = when (n) { 0 -> "—"; 1 -> "Mono"; 2 -> "Stereo 2.0"; else -> "$n kênh" }
fun quality(t: Track, i: AudioInfo?): String {
    if (t.ext == "dsf" || t.ext == "dff") return "DSD"
    val lossless = t.ext in setOf("flac", "wav", "aif", "aiff", "alac")
    return when {
        lossless && i != null && (i.bits > 16 || i.sampleRate > 48000) -> "HI-RES LOSSLESS"
        lossless -> "LOSSLESS"
        else -> "NÉN (LOSSY)"
    }
}

/** Gốc của một file cục bộ: /storage/emulated/0 (bộ nhớ TV) hoặc /storage/XXXX-XXXX (USB / ổ cứng). */
fun localRoot(p: String): String? {
    val s = p.split('/')
    return when {
        p.startsWith("/storage/emulated/") && s.size >= 4 -> s.take(4).joinToString("/")
        p.startsWith("/storage/") && s.size >= 3 -> s.take(3).joinToString("/")
        p.startsWith("/sdcard") -> "/sdcard"
        else -> null
    }
}
fun rootLabelOf(r: String) = if (r.startsWith("/storage/emulated") || r.startsWith("/sdcard")) (if (Device.tv) "Bộ nhớ TV" else "Bộ nhớ máy") else "USB " + r.substringAfterLast('/')
fun dirLabel(dir: String): String {
    if (dir.startsWith("smb://") || dir.startsWith("dav://")) {
        val rest = dir.substringAfter("://")
        return if (!rest.contains('/')) SourceStore.name(rest) else rest.substringAfterLast('/')
    }
    val r = localRoot(dir)
    return if (r != null && dir == r) rootLabelOf(r) else dir.substringAfterLast('/')
}
/** Các cấp thư mục hiển thị của một bài: [tên ổ/nguồn, thư mục con..., tên file]. */
fun segs(t: Track): List<String> {
    val p = t.path
    return when {
        p.startsWith("/") -> {
            val r = localRoot(p) ?: "/"
            val head = if (r == "/") "Thiết bị" else rootLabelOf(r)
            if (t.dir.isNotEmpty()) listOf(head) + t.dir.split('/') + p.substringAfterLast('/')
            else listOf(head) + p.removePrefix(r).trim('/').split('/')
        }
        p.startsWith("smb://") || p.startsWith("dav://") -> listOf(SourceStore.name(t.src)) + p.substringAfter("://").substringAfter('/').split('/')
        else -> { val n = SourceStore.name(t.src); listOf(n) + t.dir.split('/').filter { it.isNotEmpty() && it != n } + t.title }
    }
}
