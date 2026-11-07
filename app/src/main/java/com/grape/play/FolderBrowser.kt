package com.grape.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.util.TreeMap

private class Node(val name: String) {
    val dirs = TreeMap<String, Node>(String.CASE_INSENSITIVE_ORDER)
    val tracks = ArrayList<Track>()
    fun all(): List<Track> = tracks + dirs.values.flatMap { it.all() }
    val count: Int by lazy { tracks.size + dirs.values.sumOf { it.count } }
    /** Bài đại diện để lấy ảnh bìa cho ô thư mục: ưu tiên bài nằm ngay trong thư mục, rồi mới tới thư mục con. */
    fun first(): Track? = tracks.firstOrNull() ?: dirs.values.firstNotNullOfOrNull { it.first() }
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

/** Ô thư mục: ảnh bìa (nếu có; không có thì bìa tạo sẵn) + dấu thư mục + số bài + tên, giống ô Album. */
@Composable
private fun FolderTile(name: String, count: Int, cover: Track?, onClick: () -> Unit) {
    Focusable(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                CoverImage(cover, Modifier.fillMaxSize(), 8)
                Box(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Bg.copy(alpha = 0.8f))
                        .border(1.dp, C.Gold, RoundedCornerShape(4.dp)).padding(6.dp, 2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Filled.Folder, null, tint = C.Gold, modifier = Modifier.size(15.dp))
                        T("$count bài", 12, C.Gold, bold = true)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            T(name, 19, bold = true, lines = 2)
        }
    }
}

/** Duyệt theo cây thư mục thật: ổ/nguồn ▸ thư mục ▸ thư mục con ▸ bài hát. Thư mục hiện dạng ô có ảnh bìa; bài hát nằm ngay trong thư mục hiện thành danh sách bên dưới. */
@Composable
fun FolderBrowser(all: List<Track>, onNav: (Screen) -> Unit) {
    val compact = LocalCompact.current
    val root = remember(all) { build(all) }
    var crumbs by remember { mutableStateOf(listOf<String>()) }
    var node = root
    for (c in crumbs) node = node.dirs[c] ?: break
    BackHandler(enabled = crumbs.isNotEmpty()) { crumbs = crumbs.dropLast(1) }
    val direct = remember(node) { node.tracks.sortedWith(compareBy({ if (it.no > 0) it.no else Int.MAX_VALUE }, { it.path.lowercase() })) }
    val dirs = remember(node) { node.dirs.values.toList() }
    val cur = Playback.queue.collectAsState().value.getOrNull(Playback.index.collectAsState().value)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            T(if (crumbs.isEmpty()) "Tất cả ổ & nguồn" else crumbs.joinToString("  ›  "), if (compact) 18 else 24, C.Gold, Modifier.weight(1f), lines = 2)
            if (crumbs.isNotEmpty()) Focusable({ crumbs = crumbs.dropLast(1) }) { T("◂ Lên một cấp", if (compact) 15 else 18, C.Text, Modifier.padding(16.dp, 8.dp)) }
            if (node.count > 0 && crumbs.isNotEmpty()) {
                Focusable({ Playback.play(node.all(), 0, false); onNav(Screen.NOW) }) { T("▶ Phát cả thư mục", if (compact) 15 else 18, C.Gold, Modifier.padding(16.dp, 8.dp)) }
                if (!compact) Focusable({ Playback.playShuffled(node.all()); onNav(Screen.NOW) }) { T("⤮ Ngẫu nhiên", 18, C.Gold, Modifier.padding(16.dp, 8.dp)) }
            }
        }
        LazyVerticalGrid(
            if (compact) GridCells.Adaptive(130.dp) else GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp),
        ) {
            items(dirs, key = { "d:" + it.name }) { d -> FolderTile(d.name, d.count, d.first()) { crumbs = crumbs + d.name } }
            itemsIndexed(direct, span = { _, _ -> GridItemSpan(maxLineSpan) }) { i, t ->
                TrackRow(i, t, t.path == cur?.path) { Playback.play(direct, i, false); onNav(Screen.NOW) }
            }
        }
    }
}
