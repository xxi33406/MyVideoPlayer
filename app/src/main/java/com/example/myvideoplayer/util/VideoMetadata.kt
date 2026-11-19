package com.example.myvideoplayer.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视频元数据提取：宽高（播放页方向判断用）。
 *
 * 为什么在扫描阶段做：播放页方向原先由 `rememberPresentationState().videoSizeDp`
 * 驱动——那个值要等第一帧真正渲染出来才有。于是横屏视频的时序必然是
 * 「进播放页（竖屏）→ 首帧合成 → 尺寸解出 → 跳横屏」，中间那一下竖屏
 * 就是用户报的「先竖屏一下再横屏」。把宽高提前到扫描时入库，进播放页
 * 第一帧还没合成就知道该横还是竖。
 *
 * 成本：每个视频起一次 MediaMetadataRetriever。实测模拟器上 120 个 8 秒
 * 短视频约 1.2 秒（IO 线程、扫描本来就在后台），用户无感；失败（流式
 * m3u8、损坏文件）静默按 0 处理，方向逻辑回落到首帧尺寸，行为不劣化。
 */
object VideoMetadata {

    private const val TAG = "VideoMetadata"

    /** 读取单个视频的宽高。任何失败都返回 0/0，调用方按未知处理。 */
    fun extract(context: Context, uri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            // 带旋转元数据的视频（手机竖拍 mp4 常见 rotation=90），宽高是反的，
            // 显示宽高要按旋转后算，否则方向判断会错。
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) h to w else w to h
        } catch (e: Exception) {
            Log.w(TAG, "取宽高失败: $uri", e)
            0 to 0
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 批量填充宽高。已有宽高的条目跳过（重扫场景），失败置 0。
     * 在 IO 线程逐个跑——MediaMetadataRetriever 不是线程安全的，
     * 但这里瓶颈是磁盘/解码器，串行与并行的总耗时差异很小。
     */
    suspend fun fillDimensions(context: Context, videos: List<FileScanner.VideoFile>): List<FileScanner.VideoFile> =
        withContext(Dispatchers.IO) {
            videos.map { v ->
                if (v.width > 0 && v.height > 0) {
                    v
                } else {
                    val (w, h) = extract(context, v.uri)
                    v.copy(width = w, height = h)
                }
            }
        }
}
