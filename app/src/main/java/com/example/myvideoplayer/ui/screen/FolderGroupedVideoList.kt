package com.example.myvideoplayer.ui.screen

import android.graphics.Bitmap; import android.net.Uri
import androidx.compose.animation.*; import androidx.compose.foundation.ExperimentalFoundationApi; import androidx.compose.foundation.Image; import androidx.compose.foundation.background
import androidx.compose.foundation.clickable; import androidx.compose.foundation.combinedClickable; import androidx.compose.foundation.layout.*; import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons; import androidx.compose.material.icons.filled.*
import com.example.myvideoplayer.ui.icons.ArrowDownward
import com.example.myvideoplayer.ui.icons.ArrowUpward
import com.example.myvideoplayer.ui.icons.ExpandLess
import com.example.myvideoplayer.ui.icons.ExpandMore
import com.example.myvideoplayer.ui.icons.Folder
import androidx.compose.material3.*; import androidx.compose.runtime.*; import androidx.compose.runtime.saveable.rememberSaveable; import androidx.compose.runtime.saveable.Saver; import androidx.compose.ui.Alignment; import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip; import androidx.compose.ui.graphics.Color; import androidx.compose.ui.graphics.asImageBitmap; import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext; import androidx.compose.ui.text.font.FontWeight; import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp; import androidx.compose.ui.unit.sp
import com.example.myvideoplayer.data.entity.VideoEntity
import com.example.myvideoplayer.util.ThumbnailCache

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
    // 排序与折叠状态必须 rememberSaveable：进入播放页时 NavHost 会销毁本组合，
    // 返回时 remember 的状态全部归零——用户看到的「收起的列表重新展开、排序
    // 变回名称」就是这么来的。rememberSaveable 走实例状态恢复，能活过这个过程。
    var sortMode by rememberSaveable { mutableStateOf(SortMode.NAME) }
    var ascending by rememberSaveable { mutableStateOf(true) }

    // 折叠状态。必须用 snapshot 支持的集合：普通 Set 改动不会触发重组，
    // 折叠/展开时 item 列表不会重新构建。
    // SnapshotStateMap 本身进不了 Bundle，所以 Saver 只保存「被折叠的文件夹名」，
    // 恢复时重建成 map（未出现的 key 视为展开，与初始语义一致）。
    val collapsedFolders = rememberSaveable(
        saver = Saver(
            save = { it.filterValues { v -> v }.keys.toList() },
            restore = { saved ->
                mutableStateMapOf<String, Boolean>().apply { saved.forEach { k -> this[k] = true } }
            }
        )
    ) { mutableStateMapOf<String, Boolean>() }

    val groups = remember(videos, sortMode, ascending) {
        videos.groupBy { it.folderName.ifEmpty { extractParentFolder(it.path) } }.map { (k, v) ->
            val sorted = when (sortMode) {
                SortMode.NAME -> if (ascending) v.sortedBy { it.name.lowercase() } else v.sortedByDescending { it.name.lowercase() }
                // 文件自身的修改时间，不是 lastPlayedAt——后者是"最后播放时刻"，
                // 没播过的视频恒为 0，用它排序用户看到的就是无规律（bug 报告里的"时间排序不对"）
                SortMode.DATE -> if (ascending) v.sortedBy { it.fileModifiedAt } else v.sortedByDescending { it.fileModifiedAt }
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
            groups.forEach { (folder, folderVideos) ->
                // 标题行单独成一个 item，卡片则按行展开成独立 item。
                // 此前整个文件夹是一个 item、内部用 Column + forEach 全量布局，
                // 120 个文件会一次性组合 120 张卡片、并发 120 次取帧请求。
                item(key = "folder_head_$folder") {
                    FolderHeader(
                        folder = folder,
                        count = folderVideos.size,
                        expanded = collapsedFolders[folder] != true,
                        onToggle = { collapsedFolders[folder] = collapsedFolders[folder] != true },
                        onRefresh = { onRefreshFolder(folder) }
                    )
                }
                if (collapsedFolders[folder] != true) {
                    folderVideos.chunked(2).forEachIndexed { rowIndex, row ->
                        item(key = "folder_${folder}_row_$rowIndex") {
                            VideoRow(
                                row = row,
                                allVideos = videos,
                                selectedVideos = selectedVideos,
                                onToggleSelection = onToggleSelection,
                                onPlayVideo = onPlayVideo,
                                onDeleteVideo = onDeleteVideo
                            )
                        }
                    }
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
private fun FolderHeader(
    folder: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit
) {
    val name = folder.ifEmpty { "根目录" }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {}, modifier = Modifier.size(22.dp)) {
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onRefresh, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Filled.Refresh, "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * 文件夹内的一行（最多两张卡片）。
 * 之所以按行拆成独立 lazy item，而不是整个文件夹一个 item 内部 Column 全量布局：
 * LazyColumn 只组合可见 item，屏幕外的行不会触发 ThumbCard 的 LaunchedEffect，
 * 也就不会去解码缩略图。
 */
@Composable
private fun VideoRow(
    row: List<VideoEntity>,
    allVideos: List<VideoEntity>,
    selectedVideos: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onPlayVideo: (List<VideoEntity>, Int) -> Unit,
    onDeleteVideo: (VideoEntity) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (video in row) {
            Box(Modifier.weight(1f)) {
                ThumbCard(
                    video,
                    selectedVideos.contains(video.id),
                    onClick = {
                        if (selectedVideos.isNotEmpty()) onToggleSelection(video.id)
                        else onPlayVideo(allVideos, allVideos.indexOf(video).coerceAtLeast(0))
                    },
                    onLongClick = { onToggleSelection(video.id) },
                    showDel = true,
                    onDel = { onDeleteVideo(video) },
                    showRm = false,
                    onRm = {}
                )
            }
        }
        if (row.size == 1) Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbCard(v: VideoEntity, isSel: Boolean, onClick: ()->Unit, onLongClick: ()->Unit, showDel: Boolean, onDel: ()->Unit, showRm: Boolean, onRm: ()->Unit) {
    val ctx = LocalContext.current
    // 缓存键带文件修改时间：同一 URI 被覆盖写后旧图自动失效
    var bmp by remember(v.path, v.fileModifiedAt) { mutableStateOf(ThumbnailCache.get(v.path, v.fileModifiedAt)) }
    LaunchedEffect(v.path, v.fileModifiedAt) { if (bmp == null) { ThumbnailCache.load(ctx, v.path, v.fileModifiedAt)?.let { bmp = it } } }
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
