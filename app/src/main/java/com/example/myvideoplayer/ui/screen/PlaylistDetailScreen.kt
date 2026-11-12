package com.example.myvideoplayer.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult; import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi; import androidx.compose.foundation.Image; import androidx.compose.foundation.background; import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable; import androidx.compose.foundation.layout.*; import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells; import androidx.compose.foundation.lazy.grid.LazyVerticalGrid; import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items; import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons; import androidx.compose.material.icons.automirrored.filled.ArrowBack; import androidx.compose.material.icons.filled.*
import com.example.myvideoplayer.ui.icons.FolderOpen
import androidx.compose.material3.*; import androidx.compose.runtime.*; import androidx.compose.ui.Alignment; import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip; import androidx.compose.ui.graphics.Color; import androidx.compose.ui.graphics.asImageBitmap; import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext; import androidx.compose.ui.text.font.FontWeight; import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp; import androidx.compose.ui.unit.sp
import com.example.myvideoplayer.data.entity.PlaylistEntity; import com.example.myvideoplayer.data.entity.VideoEntity
import com.example.myvideoplayer.ui.viewmodel.MainViewModel; import com.example.myvideoplayer.ui.viewmodel.ScanResult
import com.example.myvideoplayer.util.ThumbnailCache

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(vm: MainViewModel, playlist: PlaylistEntity?, onBack: () -> Unit,
                          onPlayVideo: (List<VideoEntity>, Int) -> Unit) {
    val allVideos by vm.allVideos.collectAsState(); val scan by vm.isScanning.collectAsState(); val sel by vm.selectedVideos.collectAsState()
    val pls by vm.playlists.collectAsState(); var picker by remember { mutableStateOf(false) }; var sb by remember { mutableStateOf("") }
    var delConfirm by remember { mutableStateOf<VideoEntity?>(null) }; val sh = remember { SnackbarHostState() }
    val isAll = playlist == null; val lid = playlist?.id

    // 排除已包含选中视频的播放列表
    var existingIds by remember { mutableStateOf(emptySet<Long>()) }

    val playlistVideos by produceState(initialValue = emptyList<VideoEntity>(), key1 = lid) {
        if (isAll) vm.allVideos.collect { value = it } else lid?.let { vm.getPlaylistVideos(it).collect { value = it } }
    }
    val displayVideos = if (isAll) allVideos else playlistVideos
    val fl = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> uri?.let { vm.scanFolder(it) } }
    LaunchedEffect(Unit) { vm.scanResult.collect { r -> when(r) { is ScanResult.Success -> sb="扫描 ${r.count} 个"; is ScanResult.Error -> sb=r.msg } } }
    LaunchedEffect(Unit) { vm.uiMessage.collect { sb = it } }
    LaunchedEffect(sb) { if (sb.isNotEmpty()) { sh.showSnackbar(sb); sb = "" } }
    // picker 打开时加载已包含的播放列表
    if (picker) {
        LaunchedEffect(sel) { existingIds = vm.getExistingPlaylistIdsSync(sel.toList()) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (isAll) "全部视频" else playlist!!.name) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = {
                if (sel.isNotEmpty()) { TextButton(onClick = { picker=true }) { Text("加入(${sel.size})") }; TextButton(onClick = { vm.clearSelection() }) { Icon(Icons.Default.Close, "取消") } }
                if (isAll) IconButton(onClick = { fl.launch(null) }) { Icon(Icons.Default.FolderOpen, "文件夹") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
        snackbarHost = { SnackbarHost(sh) }
    ) { pad ->
        if (scan) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (displayVideos.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isAll) "暂无视频" else "此列表暂无视频"); Spacer(Modifier.height(8.dp)); if (isAll) Button(onClick = { fl.launch(null) }) { Text("选择文件夹") }
            }
        } else {
            if (isAll) {
                // 全部视频：PaddingValues 防止被 TopAppBar 遮挡
                Box(Modifier.padding(pad)) {
                    FolderGroupedVideoList(videos = displayVideos, selectedVideos = sel,
                        onToggleSelection = { vm.toggleSelection(it) }, onPlayVideo = onPlayVideo,
                        onDeleteVideo = { delConfirm = it },
                        onRefreshFolder = { name -> vm.rescanFolder(name) }
                    )
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().padding(pad),
                    contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(displayVideos, key = { it.id }) { v ->
                        ThumbCard2(v, sel.contains(v.id),
                            onClick = { if (sel.isNotEmpty()) vm.toggleSelection(v.id) else onPlayVideo(displayVideos, displayVideos.indexOf(v).coerceAtLeast(0)) },
                            onLongClick = { vm.toggleSelection(v.id) }, showDel = false, onDel = {},
                            showRm = !isAll && lid != null, onRm = { lid?.let { vm.removeVideoFromPlaylist(v.id, it) } })
                    }
                }
            }
        }
    }
    if (picker) {
        AlertDialog(onDismissRequest = { picker=false }, title = { Text("加入播放列表") }, text = {
            if (pls.isEmpty()) { Text("暂无播放列表") }
            else {
                val available = pls.filterNot { it.id in existingIds }
                if (available.isEmpty()) Text("所选视频已在所有播放列表中")
                else LazyColumn {
                    items(available) { pl ->
                        TextButton(onClick = {
                            vm.addVideosToPlaylist(sel.toList(), pl.id, pl.name)
                            existingIds = existingIds + pl.id  // 立即从UI中移除
                            picker = false
                        }, modifier = Modifier.fillMaxWidth()) { Text(pl.name) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { picker=false }) { Text("关闭") } })
    }
    delConfirm?.let { v -> AlertDialog(onDismissRequest = { delConfirm=null }, title = { Text("确认删除") }, text = { Text("确定删除「${v.name}」？") },
        confirmButton = { TextButton(onClick = { vm.deleteVideoFromLocal(v); delConfirm=null }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) { Text("删除") } },
        dismissButton = { TextButton(onClick = { delConfirm=null }) { Text("取消") } }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbCard2(v: VideoEntity, isSel: Boolean, onClick: ()->Unit, onLongClick: ()->Unit, showDel: Boolean, onDel: ()->Unit, showRm: Boolean, onRm: ()->Unit) {
    val ctx = LocalContext.current;     var bmp by remember(v.path) { mutableStateOf(ThumbnailCache.get(v.path)) }
    LaunchedEffect(v.path) { if (bmp == null) { ThumbnailCache.load(ctx, v.path)?.let { bmp = it } } }
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Box { Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(topStart=12.dp, topEnd=12.dp)).background(Color.Black.copy(0.3f)), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.PlayArrow, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(36.dp)) }
            if (isSel) Icon(Icons.Default.Check, "已选", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).background(Color.White, RoundedCornerShape(12.dp)))
            if (showDel) IconButton(onClick = onDel, modifier = Modifier.align(Alignment.BottomEnd).size(28.dp)) { Icon(Icons.Default.Delete, "删", tint = Color.White, modifier = Modifier.size(16.dp)) }
            if (showRm) IconButton(onClick = onRm, modifier = Modifier.align(Alignment.BottomEnd).size(28.dp)) { Icon(Icons.Default.Close, "移除", tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
        Text(v.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal=8.dp, vertical=6.dp), fontWeight = FontWeight.Medium, fontSize = 12.sp)
    }
}
