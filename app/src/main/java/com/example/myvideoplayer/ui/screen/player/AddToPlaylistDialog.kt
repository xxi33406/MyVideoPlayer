package com.example.myvideoplayer.ui.screen.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myvideoplayer.data.entity.PlaylistEntity

/**
 * 「加入播放列表」对话框。
 *
 * available 是**已经过滤掉**"当前视频已在其中"的列表：如果不过滤，用户点进去
 * 看到的是一串点了没反应的选项（cross_ref 有唯一约束，重复插入被 IGNORE 掉，
 * 但界面不会给任何反馈），看起来像是按钮坏了。
 */
@Composable
internal fun AddToPlaylistDialog(
    videoName: String,
    allPlaylists: List<PlaylistEntity>,
    available: List<PlaylistEntity>,
    onPick: (PlaylistEntity) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入播放列表") },
        text = {
            Column {
                Text(
                    videoName,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                when {
                    allPlaylists.isEmpty() -> Text("暂无播放列表")
                    available.isEmpty() -> Text("该视频已在所有播放列表中")
                    else -> LazyColumn {
                        items(available) { playlist ->
                            TextButton(
                                onClick = { onPick(playlist) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(playlist.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
