package com.example.myvideoplayer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 视频缩略图缓存：内存 LRU + 磁盘 WebP + 按目标尺寸解码。
 *
 * 相比最初的实现改了三处，每一处都对应一个可测量的瓶颈：
 *
 * 1. 解码尺寸：原来 `getFrameAtTime(0, OPTION_CLOSEST)` 按**原始分辨率**解出一整帧，
 *    4K 视频单帧约 33MB，然后再 `createScaledBitmap` 缩到 160px —— 缩放发生在解码之后，
 *    昂贵的那一步一点没省。现在用 `getScaledFrameAtTime(..., 320, 180)`，在解码阶段
 *    就只请求目标尺寸。
 * 2. 取帧模式：`OPTION_CLOSEST` 需要精确定位到指定时刻，是最慢的取帧方式；
 *    缩略图不需要那一帧的精确性，改 `OPTION_CLOSEST_SYNC` 只找就近的关键帧。
 * 3. 磁盘缓存：原来只有 40 张的内存缓存，列表一滚超过 40 项就全部失效，回来重新解码。
 *    现在解码结果落盘为 WebP，二次进入列表直接读文件，不再碰解码器。
 */
object ThumbnailCache {
    private const val TAG = "ThumbnailCache"

    /** 缩略图目标宽度。卡片最大约 160dp，按 2x 密度取 320。 */
    private const val TARGET_W = 320
    private const val TARGET_H = 180

    /**
     * 省电模式下的目标尺寸。
     *
     * 缩略图解码是整个列表页最重的活（每个视频都要起一次 MediaMetadataRetriever），
     * 低电量时把尺寸砍半：解码出来的像素量只有 1/4，磁盘 WebP 体积也小得多。
     * 卡片显示尺寸不变，只是放大后会糊一点 —— 电量告急时这个取舍是划算的。
     */
    private const val LOW_POWER_W = 160
    private const val LOW_POWER_H = 90

    /**
     * 内存上限按字节算，不按张数。320x180 的 ARGB_8888 单张约 230KB，
     * 16MB 大约能放 70 张；原来写死 40 张，实际占多少内存完全取决于视频分辨率。
     */
    private const val MEMORY_BYTES = 16 * 1024 * 1024

    private const val DISK_DIR = "thumbs"
    private const val DISK_QUALITY = 80
    private const val DISK_MAX_FILES = 400

    /** 同时解码的线程数。太高会跟播放器抢解码器，太低会让列表缩略图排队。 */
    private const val PERMITS = 3

    private val sem = Semaphore(PERMITS)

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val writeCount = AtomicInteger(0)

    /** 同步读内存缓存。列表项首帧渲染时用，拿不到就先显示占位图标。 */
    fun get(path: String): Bitmap? = memory.get(cacheKey(path))

    /**
     * 是否用低分辨率解码。
     *
     * 由 [powerSaveMode] 控制，在 Application 里监听 ACTION_POWER_SAVE_MODE_CHANGED 更新。
     * 缓存键要跟着变 —— 否则省电模式解出来的 160x90 会被当成正常结果，
     * 退出省电后列表一直是糊的。
     */
    @Volatile
    private var lowPower = false

    fun setLowPowerMode(on: Boolean) {
        if (lowPower == on) return
        lowPower = on
        // 尺寸变了，旧的缓存全部作废，否则会拿高清图去充当低清（无害）
        // 或拿低清图充当高清（有损）。直接清掉最简单。
        memory.evictAll()
    }

    private fun cacheKey(path: String): String = if (lowPower) "$path#low" else path

    private fun targetW(): Int = if (lowPower) LOW_POWER_W else TARGET_W
    private fun targetH(): Int = if (lowPower) LOW_POWER_H else TARGET_H

    suspend fun load(context: Context, path: String): Bitmap? {
        val key = cacheKey(path)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            sem.withPermit {
                // 排队期间可能已经被别的协程填好了，再查一次避免重复解码
                memory.get(key)?.let { return@withPermit it }

                val file = diskFile(context, key)
                decodeDisk(file)?.let {
                    memory.put(key, it)
                    return@withPermit it
                }

                decodeFrame(context, path)?.let {
                    memory.put(key, it)
                    writeDisk(file, it)
                    it
                }
            }
        }
    }

    fun clear() {
        memory.evictAll()
    }

    /** 磁盘缓存也清掉。cacheDir 本身会被系统在存储紧张时回收，这里只是提前主动释放。 */
    fun clearDisk(context: Context) {
        runCatching {
            File(context.applicationContext.cacheDir, DISK_DIR).listFiles()?.forEach { it.delete() }
        }
    }

    // ==================== 解码 ====================

    private fun decodeFrame(context: Context, path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(path))
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // 取时长 10% 处：第 0 秒常常是黑场或片头 logo，做缩略图没有辨识度。
            // 读不到时长（部分流式格式）就退回 0。
            val timeUs = if (durationMs > 0) durationMs * 1000L / 10 else 0L
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetW(),
                targetH()
            )
        } catch (e: Exception) {
            Log.w(TAG, "取帧失败: $path", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ==================== 磁盘缓存 ====================

    private fun diskFile(context: Context, path: String): File {
        val dir = File(context.applicationContext.cacheDir, DISK_DIR)
        return File(dir, "${md5(path)}.webp")
    }

    private fun md5(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun decodeDisk(file: File): Bitmap? {
        if (!file.isFile || file.length() == 0L) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "读磁盘缩略图失败: ${file.name}", e)
            null
        }
    }

    private fun writeDisk(file: File, bitmap: Bitmap) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, DISK_QUALITY, out)
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "写磁盘缩略图失败: ${file.name}", e)
            runCatching { file.delete() }
        }
        pruneIfNeeded(file.parentFile)
    }

    /**
     * 磁盘文件数超限时删掉最旧的一批。每写 20 次才 listFiles 一次，
     * 否则每次落盘都要遍历目录，省下的时间又被这个抵消掉。
     */
    private fun pruneIfNeeded(dir: File?) {
        if (dir == null) return
        if (writeCount.incrementAndGet() % 20 != 0) return
        val files = dir.listFiles() ?: return
        if (files.size <= DISK_MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - DISK_MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }
}
