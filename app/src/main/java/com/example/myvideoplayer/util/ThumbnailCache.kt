package com.example.myvideoplayer.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.LinkedHashMap

object ThumbnailCache {
    private const val MAX = 40
    private val sem = Semaphore(3)
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, Bitmap>(MAX, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > MAX
    })

    fun get(path: String): Bitmap? = cache[path]

    suspend fun load(context: Context, path: String): Bitmap? {
        cache[path]?.let { return it }
        return withContext(Dispatchers.IO) {
            sem.withPermit {
                try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(context, Uri.parse(path))
                    // OPTION_CLOSEST: 取最近帧（不需要关键帧），比默认快很多
                    val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                    r.release()
                    frame?.let {
                        val w = 160  // 更小尺寸加快解码
                        val h = (it.height * w.toFloat() / it.width).toInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(it, w, h, true)
                        if (scaled != it) it.recycle()
                        cache[path] = scaled
                        scaled
                    }
                } catch (_: Exception) { null }
            }
        }
    }

    fun clear() { cache.clear() }
}
