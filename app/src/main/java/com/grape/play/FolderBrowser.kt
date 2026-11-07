package com.grape.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.TreeMap

private class Node(val name: String) {
    val dirs = TreeMap<String, Node>(String.CASE_INSENSITIVE_ORDER)
    val tracks = ArrayList<Track>()
    fun all(): List<Track> = tracks + dirs.values.flatMap { it.all() }
    val count: Int by lazy { tracks.size + dirs.values.sumOf { it.count } }
}

private fun build(all: List<Track>): Node {
    val root = Node("")
    for (t in all) {
        val s = segs(t); var n = root
        for (i in 0 until s.size - 1) n = n.dirs.getOrPut(s[i]) { Node(s[i]) }
        n.tracks += t
    }
    return root
}

/** Duyệt theo cây thư mục thật: ổ/nguồn ▸ thư mục ▸ thư mục con ▸ bài hát. */
@Composable
fun FolderBrowser(all: List<Track>, onNav: (Screen) -> Unit) {
    val root = remember(all) { build(all) }
    var crumbs by remember { mutableStateOf(listOf<String>()) }
    var node = root
    for (c in crumbs) node = node.dirs[c] ?: break
    BackHandler(enabled = crumbs.isNotEmpty()) { crumbs = crumbs.dropLast(1) }
    val direct = remember(node) { node.tracks.sortedWith(compareBy({ if (it.no > 0) it.no else Int.MAX_VALUE }, { it.path.lowercase() })) }
    val cur = Playback.queue.collectAsState().value.getOrNull(Playback.index.collectAsState().value)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            T(if (crumbs.isEmpty()) "Tất cả ổ & nguồn" else crumbs.joinToString("  ›  "), 24, C.Gold, Modifier.weight(1f))
            if (crumbs.isNotEmpty()) Focusable({ crumbs = crumbs.dropLast(1) }) { T("◂ Lên một cấp", 18, C.Text, Modifier.padding(16.dp, 8.dp)) }
            if (node.count > 0 && crumbs.isNotEmpty()) {
                Focusable({ Playback.play(node.all(), 0, false); onNav(Screen.NOW) }) { T("▶ Phát cả thư mục", 18, C.Gold, Modifier.padding(16.dp, 8.dp)) }
                Focusable({ Playback.playShuffled(node.all()); onNav(Screen.NOW) }) { T("⤮ Ngẫu nhiên", 18, C.Gold, Modifier.padding(16.dp, 8.dp)) }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(node.dirs.values.toList(), key = { it.name }) { d ->
                Focusable({ crumbs = crumbs + d.name }, Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        T("▸", 22, C.Gold)
                        T(d.name, 22, C.Text, Modifier.weight(1f))
                        T("${d.count} bài", 16, C.Muted)
                    }
                }
            }
            itemsIndexed(direct) { i, t -> TrackRow(i, t, t.path == cur?.path) { Playback.play(direct, i, false); onNav(Screen.NOW) } }
        }
    }
}
