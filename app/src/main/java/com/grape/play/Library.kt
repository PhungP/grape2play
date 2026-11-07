package com.grape.play

import android.content.Context
import android.media.MediaMetadataRetriever as MMR
import android.os.Environment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object Library {
    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val scanning = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lists = HashMap<String, List<Track>>()
    private val active = AtomicInteger(0)
    private val localBusy = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)
    /** Dừng lần quét NAS/DLNA/đám mây đang chạy (giữ lại những gì đã tìm được). */
    fun cancelScan() { cancelFlag.set(true) }

    private fun begin() { active.incrementAndGet(); scanning.value = true }
    private fun end() { if (active.decrementAndGet() <= 0) scanning.value = false }
    @Synchronized private fun publish(id: String, l: List<Track>) { lists[id] = l; tracks.value = lists.values.flatten() }
    @Synchronized private fun drop(id: String) { lists.remove(id); tracks.value = lists.values.flatten() }
    @Synchronized private fun cached(id: String): List<Track> = lists[id].orEmpty()
    private fun cacheFile(ctx: Context, id: String) = File(ctx.filesDir, if (id == "local") "library_v4.json" else "src_$id.json")

    /** Dò mọi ổ đọc được: bộ nhớ trong, thư mục app trên từng ổ (cách chuẩn để thấy USB), và thư mục gốc của từng ổ trong thư mục storage. */
    private fun roots(ctx: Context): List<File> {
        val r = LinkedHashMap<String, File>()
        fun add(f: File?) { if (f != null) r.putIfAbsent(f.path, f) }
        add(Environment.getExternalStorageDirectory())
        runCatching {
            ctx.getExternalFilesDirs(null).filterNotNull().forEach { d ->
                val i = d.path.indexOf("/Android/"); if (i > 0) add(File(d.path.substring(0, i)))
            }
        }
        File("/storage").listFiles()?.forEach { if (it.name != "self" && it.name != "emulated" && it.isDirectory) add(it) }
        return r.values.toList()
    }
    private fun rootLabel(f: File) = if (f.path.startsWith("/storage/emulated") || f.path.startsWith("/sdcard")) "TV" else "USB ${f.name}"

    /** Nạp cache (nhanh) rồi quét lại bộ nhớ TV/USB. Nguồn NAS chỉ quét khi người dùng bấm. */
    suspend fun load(ctx: Context) {
        withContext(Dispatchers.IO) {
            for (id in listOf("local") + SourceStore.list.value.map { it.id })
                cacheFile(ctx, id).takeIf { it.exists() }?.let { f -> runCatching { publish(id, fromJson(f.readText())) } }
        }
        rescanLocal(ctx)
    }

    private val localPending = AtomicBoolean(false)

    suspend fun rescanLocal(ctx: Context) = withContext(Dispatchers.IO) {
        if (!localBusy.compareAndSet(false, true)) { localPending.set(true); return@withContext } // yêu cầu quét lại được xếp hàng
        try {
            do { localPending.set(false); scanLocalOnce(ctx) } while (localPending.get())
        } finally { localBusy.set(false) }
    }

    private fun scanLocalOnce(ctx: Context) {
        begin()
        try {
            status.value = "Đang quét bộ nhớ TV & USB…"
            val old = cached("local").associateBy { it.path }
            val out = ArrayList<Track>()
            val report = ArrayList<String>()
            for (root in roots(ctx)) {
                val readable = root.listFiles() != null
                val files = if (!readable) emptyList() else root.walkTopDown()
                    .onEnter { d -> !d.name.startsWith(".") && d.name != "Android" && d.name != "LOST.DIR" }
                    .filter { it.isFile && isAudioName(it.name) }.toList()
                report += "${rootLabel(root)}: " + if (!readable) "không đọc được" else "${files.size}"
                for (f in files) {
                    val disp = Recycle.displayDir(f.path)
                    out += old[f.path] ?: readMeta(f.path, "local", disp).let { if (disp.isNotEmpty()) it.copy(dir = disp) else it }
                    if (out.size % 40 == 0) publish("local", out.toList())
                }
            }
            val unique = out.distinctBy { it.path }
            publish("local", unique)
            runCatching { cacheFile(ctx, "local").writeText(toJson(unique)) }
            status.value = "Đã quét: " + report.joinToString(" · ")
        } catch (e: Exception) {
            status.value = "Lỗi quét bộ nhớ: ${e.message ?: e.javaClass.simpleName}"
        } finally { end() }
    }

    suspend fun scanSource(ctx: Context, s: Source) = withContext(Dispatchers.IO) {
        begin()
        cancelFlag.set(false)
        try {
            status.value = "Đang quét ${s.name}…"
            val old = cached(s.id).associateBy { it.path }
            var note = ""
            val result: List<Track> = if (s.type == "dlna") {
                val acc = ArrayList<Track>()
                note = Upnp.crawl(s, { cancelFlag.get() }, { f, t, w -> status.value = "Đang quét ${s.name}: $t bài · $f thư mục · $w" }) {
                    acc += it; if (acc.size % 20 == 0) publish(s.id, acc.toList())
                }
                acc
            } else if (Cloud.isCloud(s.type)) {
                val files = ArrayList<CloudFile>()
                val failed = Cloud.walk(s, { cancelFlag.get() }, { f, n -> status.value = "Đang liệt kê ${s.name}: $n file nhạc · $f thư mục" }) { files += it }
                val acc = Collections.synchronizedList(ArrayList<Track>())
                val sem = Semaphore(4); val done = AtomicInteger(0)
                coroutineScope {
                    files.map { cf ->
                        async {
                            sem.withPermit {
                                if (!cancelFlag.get()) {
                                    acc.add(old[cf.path] ?: readCloudMeta(cf, s.id))
                                    val d = done.incrementAndGet()
                                    if (d % 20 == 0) { status.value = "Đọc thẻ nhạc ${s.name}: $d/${files.size}"; publish(s.id, synchronized(acc) { acc.toList() }) }
                                }
                            }
                        }
                    }.awaitAll()
                }
                if (cancelFlag.get()) note = "đã dừng"
                if (failed > 0) note += (if (note.isEmpty()) "" else " · ") + "$failed thư mục lỗi"
                synchronized(acc) { acc.toList() }
            } else {
                val paths = ArrayList<String>()
                if (s.type == "smb") Remote.walkSmb(s) { paths += "smb://${s.id}/$it" } else Remote.walkDav(s) { paths += "dav://${s.id}/$it" }
                val acc = Collections.synchronizedList(ArrayList<Track>())
                val sem = Semaphore(4)
                coroutineScope {
                    paths.map { p ->
                        async {
                            sem.withPermit {
                                acc.add(old[p] ?: readMeta(p, s.id))
                                if (acc.size % 40 == 0) publish(s.id, synchronized(acc) { acc.toList() })
                            }
                        }
                    }.awaitAll()
                }
                synchronized(acc) { acc.toList() }
            }
            publish(s.id, result)
            cacheFile(ctx, s.id).writeText(toJson(result))
            status.value = "Đã quét ${s.name}: ${result.size} bài" + (if (note.isNotEmpty()) " · $note" else "")
        } catch (e: Exception) {
            status.value = "Lỗi quét ${s.name}: ${e.message ?: e.javaClass.simpleName}"
        } finally { end() }
    }

    fun scanSourceAsync(ctx: Context, s: Source) { scope.launch { scanSource(ctx.applicationContext, s) } }
    fun rescanAllAsync(ctx: Context) { scope.launch { rescanLocal(ctx.applicationContext); SourceStore.list.value.forEach { scanSource(ctx.applicationContext, it) } } }
    fun removeSource(ctx: Context, id: String) { SourceStore.remove(id); drop(id); cacheFile(ctx, id).delete(); Smb.reset(id) }

    /** FLAC: đọc thẻ + thông số thẳng từ header (Android 8 / MediaMetadataRetriever hay thiếu thẻ và thời lượng). */
    private fun flacMeta(path: String, src: String, name: String, album: String): Track? = try {
        val rd = Remote.reader(path)
        try {
            val m = FlacPcm.readMeta(ReaderSource(rd), false)
            val dur = m.durationMs()
            val kb = if (dur > 0 && rd.size > 0) (rd.size * 8 / dur).toInt() else 0
            Track(path, m.title.ifEmpty { name }, m.artist.ifEmpty { m.albumArtist }.ifEmpty { "Không rõ nghệ sĩ" },
                m.album.ifEmpty { album }, m.genre.ifEmpty { "Không rõ thể loại" }, dur, m.track, src, "flac", "", "",
                m.sampleRate, m.bps, m.channels, kb)
        } finally { rd.close() }
    } catch (e: Exception) { null }

    /** DSD (DSF/DFF): đọc tốc độ, số kênh, thời lượng và thẻ ID3 từ header. */
    private fun dsdMeta(path: String, src: String, name: String, album: String, ext: String): Track? = try {
        val rd = Remote.reader(path)
        try {
            val m = DsdPcm.readMeta(ReaderSource(rd))
            val dur = m.durationMs()
            val kb = if (dur > 0 && rd.size > 0) (rd.size * 8 / dur).toInt() else 0
            Track(path, m.title.ifEmpty { name }, m.artist.ifEmpty { "Không rõ nghệ sĩ" }, m.album.ifEmpty { album }, m.genre.ifEmpty { "Không rõ thể loại" },
                dur, 0, src, ext, "", "", m.rate, 1, m.channels, kb)
        } finally { rd.close() }
    } catch (e: Exception) { null }

    /** File trên đám mây: đường dẫn chỉ là id, nên tên file và thư mục phải truyền riêng. */
    private fun readCloudMeta(f: CloudFile, src: String): Track {
        val t = readMeta(f.path, src, f.dir.ifEmpty { SourceStore.name(src) }, f.name)
        return t.copy(dir = f.dir, format = t.format.ifEmpty { Cloud.extOf(f.name) })
    }

    fun readMeta(path: String, src: String, dispDir: String = "", fileName: String? = null): Track {
        val name = (fileName ?: path.substringAfterLast('/')).substringBeforeLast('.')
        val album = dispDir.substringAfterLast('/').ifEmpty { dirLabel(path.substringBeforeLast('/')) }
        val low = (fileName ?: path).lowercase()
        if (low.endsWith(".flac")) flacMeta(path, src, name, album)?.let { return it }
        if (low.endsWith(".dsf") || low.endsWith(".dff")) dsdMeta(path, src, name, album, low.substringAfterLast('.'))?.let { return it }
        return try {
            Media.withMmr(path) { r ->
                fun g(k: Int) = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
                Track(
                    path, g(MMR.METADATA_KEY_TITLE) ?: name,
                    g(MMR.METADATA_KEY_ARTIST) ?: g(MMR.METADATA_KEY_ALBUMARTIST) ?: "Không rõ nghệ sĩ",
                    g(MMR.METADATA_KEY_ALBUM) ?: album, g(MMR.METADATA_KEY_GENRE) ?: "Không rõ thể loại",
                    g(MMR.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                    g(MMR.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toIntOrNull() ?: 0, src,
                )
            }
        } catch (e: Exception) { Track(path, name, "Không rõ nghệ sĩ", album, "Không rõ thể loại", 0L, 0, src) }
    }

    private fun toJson(l: List<Track>): String {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("p", it.path).put("t", it.title).put("a", it.artist).put("b", it.album).put("g", it.genre).put("d", it.durationMs).put("n", it.no)
            .put("s", it.src).put("f", it.format).put("r", it.art).put("dr", it.dir).put("sr", it.sr).put("bt", it.bits).put("ch", it.ch).put("kb", it.kbps)) }
        return a.toString()
    }
    private fun fromJson(s: String): List<Track> {
        val a = JSONArray(s)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Track(o.getString("p"), o.getString("t"), o.getString("a"), o.getString("b"), o.getString("g"), o.getLong("d"), o.getInt("n"),
                o.optString("s", "local"), o.optString("f"), o.optString("r"), o.optString("dr"), o.optInt("sr"), o.optInt("bt"), o.optInt("ch"), o.optInt("kb"))
        }
    }

    private fun sorted(v: List<Track>) = v.sortedWith(compareBy({ if (it.no > 0) it.no else Int.MAX_VALUE }, { it.path.lowercase() }))

    fun groups(tab: Tab, all: List<Track>): List<Group> = when (tab) {
        Tab.ALBUM -> all.groupBy { it.album }.map { (k, v) ->
            val ar = v.map { it.artist }.distinct()
            Group(k, k, if (ar.size == 1) ar[0] else "Nhiều nghệ sĩ", sorted(v))
        }
        Tab.ARTIST -> all.groupBy { it.artist }.map { (k, v) -> Group(k, k, "${v.map { it.album }.distinct().size} album · ${v.size} bài", sorted(v)) }
        Tab.GENRE -> all.groupBy { it.genre }.map { (k, v) -> Group(k, k, "${v.size} bài", sorted(v)) }
        Tab.FOLDER -> all.groupBy { it.folder }.map { (k, v) ->
            val t = if (v[0].dir.isNotEmpty()) v[0].dir.substringAfterLast('/') else k.substringAfterLast('/')
            Group(k, t, "${v.size} bài · ${v[0].sourceName}", sorted(v))
        }
        Tab.SONG, Tab.FAV -> emptyList()
    }.sortedBy { it.title.lowercase() }
}
