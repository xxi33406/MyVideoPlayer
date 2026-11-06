package com.example.myvideoplayer.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult; import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable; import androidx.compose.foundation.layout.*; import androidx.compose.foundation.lazy.LazyColumn; import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons; import androidx.compose.material.icons.filled.*; import androidx.compose.material3.*; import androidx.compose.runtime.*
import androidx.compose.ui.Alignment; import androidx.compose.ui.Modifier; import androidx.compose.ui.text.font.FontWeight; import androidx.compose.ui.text.style.TextOverflow; import androidx.compose.ui.unit.dp
import com.example.myvideoplayer.data.entity.PlaylistEntity; import com.example.myvideoplayer.ui.viewmodel.MainViewModel; import com.example.myvideoplayer.ui.viewmodel.ScanResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistListScreen(vm: MainViewModel, onAll: ()->Unit, onPlaylist: (PlaylistEntity)->Unit, onCompare: ()->Unit) {
    val pls by vm.playlists.collectAsState(); val cnt by vm.allVideos.collectAsState().let { remember { derivedStateOf { it.value.size } } }
    var cr by remember { mutableStateOf(false) }; var cn by remember { mutableStateOf("") }; var rn by remember { mutableStateOf<PlaylistEntity?>(null) }
    var rt by remember { mutableStateOf("") }; var sb by remember { mutableStateOf("") }; val sh = remember { SnackbarHostState() }
    val scan by vm.isScanning.collectAsState()
    val fl = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> uri?.let { vm.scanFolder(it) } }
    LaunchedEffect(Unit) { vm.scanResult.collect { r -> when(r) { is ScanResult.Success -> sb="扫描 ${r.count}"; is ScanResult.Error -> sb=r.msg } } }
    LaunchedEffect(sb) { if (sb.isNotEmpty()) { sh.showSnackbar(sb); sb="" } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MyVideoPlayer") }, actions = {
            if (scan) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            IconButton(onClick = { fl.launch(null) }) { Icon(Icons.Default.FolderOpen, "文件夹") }
            IconButton(onClick = onCompare) { Icon(Icons.Default.Compare, "比对") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
        floatingActionButton = { FloatingActionButton(onClick = { cn=""; cr=true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, null, tint=MaterialTheme.colorScheme.onPrimary) } },
        snackbarHost = { SnackbarHost(sh) }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key="all") { Card(Modifier.fillMaxWidth().clickable(onClick = onAll), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VideoLibrary, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("全部视频", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("$cnt 个视频", style = MaterialTheme.typography.bodySmall) }
                    Icon(Icons.Default.ChevronRight, null)
            } } }
            if (pls.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("暂无播放列表") } }
            else items(pls, key={it.id}) { pl -> Card(Modifier.fillMaxWidth().clickable(onClick={onPlaylist(pl)}), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(pl.name, style=MaterialTheme.typography.titleMedium, maxLines=1, overflow=TextOverflow.Ellipsis) }
                    IconButton(onClick = { rt=pl.name; rn=pl }) { Icon(Icons.Default.Edit, "重命名") }; IconButton(onClick = { vm.deletePlaylist(pl) }) { Icon(Icons.Default.Delete, "删除") }
            } } }
        }
    }
    if (cr) AlertDialog(onDismissRequest={cr=false}, title={Text("新建播放列表")}, text={ OutlinedTextField(cn, {cn=it}, label={Text("名称")}, singleLine=true, modifier=Modifier.fillMaxWidth()) },
        confirmButton={ TextButton(onClick={ if (cn.isNotBlank()) { vm.createPlaylist(cn.trim()) { cr=false } } }) { Text("创建") } },
        dismissButton={ TextButton(onClick={cr=false}) { Text("取消") } })
    rn?.let { pl -> AlertDialog(onDismissRequest={rn=null}, title={Text("重命名")}, text={ OutlinedTextField(rt, {rt=it}, label={Text("名称")}, singleLine=true) },
        confirmButton={ TextButton(onClick={ if (rt.isNotBlank()) { vm.renamePlaylist(pl.id, rt); rn=null } }) { Text("确定") } },
        dismissButton={ TextButton(onClick={rn=null}) { Text("取消") } }) }
}
