package com.example.myvideoplayer.ui.screen

import androidx.compose.foundation.layout.*; import androidx.compose.foundation.lazy.LazyColumn; import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons; import androidx.compose.material.icons.automirrored.filled.ArrowBack; import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*; import androidx.compose.runtime.*; import androidx.compose.ui.Alignment; import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color; import androidx.compose.ui.text.font.FontWeight; import androidx.compose.ui.text.style.TextOverflow; import androidx.compose.ui.unit.dp; import androidx.compose.ui.unit.sp
import com.example.myvideoplayer.data.entity.PlaylistEntity; import com.example.myvideoplayer.data.entity.VideoEntity
import com.example.myvideoplayer.ui.viewmodel.MainViewModel; import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(vm: MainViewModel, onBack: () -> Unit) {
    val playlists by vm.playlists.collectAsState()
    var listA by remember { mutableStateOf<PlaylistEntity?>(null) }; var listB by remember { mutableStateOf<PlaylistEntity?>(null) }
    var onlyA by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    var onlyB by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    var both by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }; var hasCompared by remember { mutableStateOf(false) }
    var showPickerA by remember { mutableStateOf(false) }; var showPickerB by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun doCompare() {
        val a = listA ?: return; val b = listB ?: return
        loading = true
        scope.launch {
            try {
                val va = vm.getVideosByPlaylistOnce(a.id)
                val vb = vm.getVideosByPlaylistOnce(b.id)
                val pa = va.map { it.path }.toSet(); val pb = vb.map { it.path }.toSet()
                onlyA = va.filter { it.path !in pb }; onlyB = vb.filter { it.path !in pa }; both = va.filter { it.path in pb }
                hasCompared = true
            } catch (_: Exception) {}
            loading = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("比对播放列表") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Text("列表 A", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { showPickerA = true }, modifier = Modifier.fillMaxWidth()) { Text(listA?.name ?: "选择播放列表") }
            Spacer(Modifier.height(12.dp))
            Text("列表 B", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { showPickerB = true }, modifier = Modifier.fillMaxWidth()) { Text(listB?.name ?: "选择播放列表") }
            Spacer(Modifier.height(16.dp))

            Button(onClick = { doCompare() }, enabled = listA != null && listB != null && !loading,
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                if (loading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White); Spacer(Modifier.width(8.dp)) }
                Text(if (loading) "比对中..." else "开始比对")
            }
            Spacer(Modifier.height(16.dp))

            if (hasCompared) {
                HorizontalDivider(); Spacer(Modifier.height(8.dp))
                if (onlyA.isEmpty() && onlyB.isEmpty() && both.isEmpty()) {
                    Text("两个列表都为空", color = Color.Gray)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onlyA.isNotEmpty()) {
                            item { SectionHeader("仅「${listA?.name}」有 (${onlyA.size})", Color(0xFF4CAF50)) }
                            items(onlyA, key = { it.id }) { v -> VideoRow(v, "加 → B") { vm.addVideosToPlaylist(listOf(v.id), listB!!.id); doCompare() } }
                        }
                        if (onlyB.isNotEmpty()) {
                            item { SectionHeader("仅「${listB?.name}」有 (${onlyB.size})", Color(0xFFFF9800)) }
                            items(onlyB, key = { it.id }) { v -> VideoRow(v, "加 → A") { vm.addVideosToPlaylist(listOf(v.id), listA!!.id); doCompare() } }
                        }
                        if (both.isNotEmpty()) {
                            item { SectionHeader("共有的 (${both.size})", Color(0xFF2196F3)) }
                            items(both, key = { it.id }) { v -> VideoRow(v, null) }
                        }
                    }
                }
            }
        }
    }
    if (showPickerA) AlertDialog(onDismissRequest = { showPickerA=false }, title = { Text("选择列表 A") }, text = {
        LazyColumn { items(playlists) { pl -> TextButton(onClick = { listA=pl; showPickerA=false }, modifier = Modifier.fillMaxWidth()) { Text(pl.name) } } }
    }, confirmButton = { TextButton(onClick = { showPickerA=false }) { Text("取消") } })
    if (showPickerB) AlertDialog(onDismissRequest = { showPickerB=false }, title = { Text("选择列表 B") }, text = {
        LazyColumn { items(playlists) { pl -> TextButton(onClick = { listB=pl; showPickerB=false }, modifier = Modifier.fillMaxWidth()) { Text(pl.name) } } }
    }, confirmButton = { TextButton(onClick = { showPickerB=false }) { Text("取消") } })
}

@Composable private fun SectionHeader(t: String, c: Color) { Text(t, color = c, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp)) }
@Composable private fun VideoRow(v: VideoEntity, label: String?, onClick: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(v.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            if (label != null && onClick != null) TextButton(onClick = onClick) { Text(label, fontSize = 12.sp) }
        }
    }
}
