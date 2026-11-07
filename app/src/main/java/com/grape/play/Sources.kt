package com.grape.play

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** type: "smb" | "dav" | "dlna" | "gdrive" | "onedrive".  smb: host+share+root.  dav: url.  dlna: url = ContentDirectory control URL.
 *  gdrive/onedrive: pass = refresh token, user = tài khoản, root = id thư mục gốc, host = "personal"/"work", url = đường dẫn thư mục (để hiển thị). */
data class Source(
    val id: String, val type: String, val name: String,
    val host: String = "", val share: String = "", val root: String = "",
    val url: String = "", val user: String = "", val pass: String = "",
)

object SourceStore {
    private lateinit var sp: SharedPreferences
    val list = MutableStateFlow<List<Source>>(emptyList())

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("sources", Context.MODE_PRIVATE)
        list.value = runCatching { parse(sp.getString("v", "[]") ?: "[]") }.getOrDefault(emptyList())
    }
    fun get(id: String) = list.value.firstOrNull { it.id == id }
    fun name(id: String) = get(id)?.name ?: "NAS"
    fun add(s: Source) { list.value = list.value + s; save() }
    fun update(s: Source) { list.value = list.value.map { if (it.id == s.id) s else it }; save() }
    fun remove(id: String) { list.value = list.value.filter { it.id != id }; save() }

    private fun save() {
        val a = JSONArray()
        list.value.forEach { a.put(JSONObject().put("id", it.id).put("t", it.type).put("n", it.name).put("h", it.host).put("s", it.share).put("r", it.root).put("u", it.url).put("us", it.user).put("p", it.pass)) }
        sp.edit().putString("v", a.toString()).apply()
    }
    private fun parse(s: String): List<Source> {
        val a = JSONArray(s)
        return (0 until a.length()).map { val o = a.getJSONObject(it); Source(o.getString("id"), o.getString("t"), o.getString("n"), o.optString("h"), o.optString("s"), o.optString("r"), o.optString("u"), o.optString("us"), o.optString("p")) }
    }
}
