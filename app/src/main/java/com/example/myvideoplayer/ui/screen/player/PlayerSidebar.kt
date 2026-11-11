package com.example.myvideoplayer.ui.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myvideoplayer.data.entity.VideoEntity

/**
 * 右侧播放列表侧栏。播放中随时切集用，不打断当前播放页。
 *
 * 宽度固定 140dp：再宽就压到画面主体了，再窄片名会被截得没有辨识度。
 */
@Composable
internal fun PlayerSidebar(
    playlist: List<VideoEntity>,
    currentIndex: Int,
    onPlayAt: (Int) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(0.9f))
            .padding(8.dp)
    ) {
        Text("播放列表", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(playlist) { idx, video ->
                val isCurrent = idx == currentIndex
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(65.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isCurrent) Color.White.copy(0.2f) else Color.White.copy(0.08f)
                        )
                        .clickable { onPlayAt(idx) }
                        .padding(6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        video.name,
                        color = if (isCurrent) Color.White else Color.White.copy(0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
