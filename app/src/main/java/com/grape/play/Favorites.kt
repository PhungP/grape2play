package com.grape.play

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray

/** Danh sách bài hát yêu thích (lưu đường dẫn bài). Thứ tự = thứ tự thêm; khi hiển thị, bài mới thêm lên đầu. */
object Favorites {
    private lateinit var sp: SharedPreferences
    private lateinit var app: Context
    val paths = mutableStateOf<Set<String>>(emptySet())

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        app = ctx.applicationContext
        sp = app.getSharedPreferences("favorites", Context.MODE_PRIVATE)
        val a = runCatching { JSONArray(sp.getString("v", "[]") ?: "[]") }.getOrDefault(JSONArray())
        val s = LinkedHashSet<String>()
        for (i in 0 until a.length()) s.add(a.getString(i))
        paths.value = s
    }

    fun isFav(path: String): Boolean = path in paths.value

    /** Thêm/bỏ yêu thích. Trả về true nếu bài vừa được thêm. */
    fun toggle(t: Track): Boolean {
        val s = LinkedHashSet(paths.value)
        val added = if (t.path in s) { s.remove(t.path); false } else { s.add(t.path); true }
        paths.value = s
        if (::sp.isInitialized) {
            val a = JSONArray(); s.forEach { a.put(it) }
            sp.edit().putString("v", a.toString()).apply()
            TvHome.schedule(app, 2000)
        }
        return added
    }

    /** Các bài yêu thích có trong thư viện, bài mới thêm đứng đầu. */
    fun tracks(all: List<Track>): List<Track> {
        val m = HashMap<String, Track>(all.size * 2)
        for (t in all) m.putIfAbsent(t.path, t)
        return paths.value.reversed().mapNotNull { m[it] }
    }
}

/** 30 bài phát gần nhất (dùng cho hàng "Phát gần đây" trên trang chủ TV). */
object Recents {
    private lateinit var sp: SharedPreferences
    private var list: List<String> = emptyList()

    @Synchronized fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("recents", Context.MODE_PRIVATE)
        val a = runCatching { JSONArray(sp.getString("v", "[]") ?: "[]") }.getOrDefault(JSONArray())
        list = (0 until a.length()).map { a.getString(it) }
    }

    @Synchronized fun add(path: String) {
        if (!::sp.isInitialized) return
        list = (listOf(path) + list.filter { it != path }).take(30)
        val a = JSONArray(); list.forEach { a.put(it) }
        sp.edit().putString("v", a.toString()).apply()
    }

    @Synchronized fun tracks(all: List<Track>): List<Track> {
        val m = HashMap<String, Track>(all.size * 2)
        for (t in all) m.putIfAbsent(t.path, t)
        return list.mapNotNull { m[it] }
    }
}
