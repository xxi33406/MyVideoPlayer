package com.example.myvideoplayer.ui.screen

import android.graphics.Bitmap; import android.net.Uri
import androidx.compose.animation.*; import androidx.compose.foundation.ExperimentalFoundationApi; import androidx.compose.foundation.Image; import androidx.compose.foundation.background
import androidx.compose.foundation.clickable; import androidx.compose.foundation.combinedClickable; import androidx.compose.foundation.layout.*; import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons; import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*; import androidx.compose.runtime.*; import androidx.compose.ui.Alignment; import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip; import androidx.compose.ui.graphics.Color; import androidx.compose.ui.graphics.asImageBitmap; import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext; import androidx.compose.ui.text.font.FontWeight; import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp; import androidx.compose.ui.unit.sp
import com.example.myvideoplayer.data.entity.VideoEntity
import com.example.myvideoplayer.util.ThumbnailCache
import java.util.Collections

enum class SortMode { NAME, DATE, SIZE }

@Composable
fun FolderGroupedVideoList(
    videos: List<VideoEntity>,
    selectedVideos: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onPlayVideo: (List<VideoEntity>, Int) -> Unit,
    onDeleteVideo: (VideoEntity) -> Unit,
    onRefreshFolder: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var ascending by remember { mutableStateOf(true) }

    // 持久化折叠状态（本会话内）
    val collapsedFolders = remember { Collections.synchronizedSet(mutableSetOf<String>()) }

    val groups = remember(videos, sortMode, ascending) {
        videos.groupBy { it.folderName.ifEmpty { extractParentFolder(it.path) } }.map { (k, v) ->
            val sorted = when (sortMode) {
                SortMode.NAME -> if (ascending) v.sortedBy { it.name.lowercase() } else v.sortedByDescending { it.name.lowercase() }
                SortMode.DATE -> if (ascending) v.sortedBy { it.lastPlayedAt } else v.sortedByDescending { it.lastPlayedAt }
                SortMode.SIZE -> if (ascending) v.sortedBy { it.size } else v.sortedByDescending { it.size }
            }
            k to sorted
        }.sortedBy { it.first }
    }

    Column(modifier = modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("排序：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SortBtn("名称", SortMode.NAME, sortMode) { sortMode = it }
                SortBtn("时间", SortMode.DATE, sortMode) { sortMode = it }
                SortBtn("大小", SortMode.SIZE, sortMode) { sortMode = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ascending = !ascending }, modifier = Modifier.size(26.dp)) {
                    Icon(if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, "顺序",
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
        }

        LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEachIndexed { _, (folder, folderVideos) ->
                item(key = "folder_$folder") {
                    FolderSection(folder, folderVideos, selectedVideos, onToggleSelection, onPlayVideo,
                        onDeleteVideo, collapsedFolders, sortMode, ascending, onRefreshFolder)
                }
            }
        }
    }
}

@Composable
private fun SortBtn(label: String, mode: SortMode, cur: SortMode, onClick: (SortMode) -> Unit) {
    val a = mode == cur
    TextButton(onClick = { onClick(mode) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = if (a) FontWeight.Bold else FontWeight.Normal,
            color = if (a) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FolderSection(folder: String, videos: List<VideoEntity>, sel: Set<Long>,
                           onToggle: (Long) -> Unit, onPlay: (List<VideoEntity>, Int) -> Unit,
                           onDel: (VideoEntity) -> Unit,
                           collapsedFolders: MutableSet<String>, sortMode: SortMode, ascending: Boolean,
                           onRefreshFolder: (String) -> Unit) {
    val name = folder.ifEmpty { "根目录" }
    var expanded by remember { mutableStateOf(!collapsedFolders.contains(folder)) }

    val sorted = remember(videos, sortMode, ascending) {
        when (sortMode) {
            SortMode.NAME -> if (ascending) videos.sortedBy { it.name.lowercase() } else videos.sortedByDescending { it.name.lowercase() }
            SortMode.DATE -> if (ascending) videos.sortedBy { it.lastPlayedAt } else videos.sortedByDescending { it.lastPlayedAt }
            SortMode.SIZE -> if (ascending) videos.sortedBy { it.size } else videos.sortedByDescending { it.size }
        }
    }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable {
                expanded = !expanded
                if (expanded) collapsedFolders.remove(folder) else collapsedFolders.add(folder)
            }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {}, modifier = Modifier.size(22.dp)) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${videos.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { onRefreshFolder(folder) }, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Filled.Refresh, "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                val rows = sorted.chunked(2)
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (video in row) {
                                Box(Modifier.weight(1f)) {
                                    ThumbCard(video, sel.contains(video.id),
                                        onClick = { if (sel.isNotEmpty()) onToggle(video.id) else onPlay(sorted, sorted.indexOf(video).coerceAtLeast(0)) },
                                        onLongClick = { onToggle(video.id) }, showDel = true, onDel = { onDel(video) }, showRm = false, onRm = {})
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbCard(v: VideoEntity, isSel: Boolean, onClick: ()->Unit, onLongClick: ()->Unit, showDel: Boolean, onDel: ()->Unit, showRm: Boolean, onRm: ()->Unit) {
    val ctx = LocalContext.current
    // 关键修复：remember 绑定 video.path，排序后不会错位
    var bmp by remember(v.path) { mutableStateOf(ThumbnailCache.get(v.path)) }
    LaunchedEffect(v.path) { if (bmp == null) { ThumbnailCache.load(ctx, v.path)?.let { bmp = it } } }
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick), shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Box { Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(RoundedCornerShape(topStart=8.dp,topEnd=8.dp)).background(Color.Black.copy(0.3f)), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.PlayArrow, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(30.dp)) }
            if (isSel) Icon(Icons.Default.Check, "已选", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).background(Color.White, RoundedCornerShape(10.dp)))
            if (showDel) IconButton(onClick = onDel, modifier = Modifier.align(Alignment.BottomEnd).size(24.dp)) { Icon(Icons.Default.Delete, "删", tint = Color.White, modifier = Modifier.size(14.dp)) }
            if (showRm) IconButton(onClick = onRm, modifier = Modifier.align(Alignment.BottomEnd).size(24.dp)) { Icon(Icons.Default.Close, "移除", tint = Color.White, modifier = Modifier.size(14.dp)) }
        }
        Text(v.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal=6.dp, vertical=4.dp), fontSize = 11.sp)
    }
}

fun extractParentFolder(path: String): String {
    try { val s = Uri.decode(path).split("/").filter { it.isNotEmpty() }; if (s.size >= 2) return s[s.size - 2] } catch (_: Exception) {}
    return ""
}
