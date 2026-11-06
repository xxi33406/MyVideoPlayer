package com.example.myvideoplayer.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FileScanner {

    val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "webm", "m3u8", "ts", "mov", "flv", "wmv", "rmvb", "m4v", "3gp")

    data class VideoFile(
        val name: String,
        val path: String,
        val uri: Uri,
        val size: Long = 0,
        val parentFolder: String = ""
    )

    data class ScanResult(
        val rootName: String,
        val videos: List<VideoFile>
    )

    suspend fun scanFolder(context: Context, folderUri: Uri): ScanResult {
        return withContext(Dispatchers.IO) {
            val videos = mutableListOf<VideoFile>()
            val rootDir = DocumentFile.fromTreeUri(context, folderUri)
            val rootName = rootDir?.name ?: folderUri.lastPathSegment ?: ""
            if (rootDir != null) {
                scanSmart(rootDir, videos, rootName, false)
            }
            ScanResult(rootName, videos)
        }
    }

    private fun scanSmart(dir: DocumentFile, result: MutableList<VideoFile>, parentName: String, inCache: Boolean) {
        val files = dir.listFiles()
        val dirName = dir.name ?: ""
        val isCacheVideoDir = inCache && dirName.matches(Regex("^\\d+$")) && files.any { f ->
            f.isDirectory && (f.name == "segments" || f.name == "Segments")
        }
        if (isCacheVideoDir) {
            var mainVideo: VideoFile? = null
            for (f in files) {
                if (f.isFile) {
                    val n = f.name ?: continue
                    val ext = n.substringAfterLast('.', "").lowercase()
                    if (ext == "m3u8") {
                        mainVideo = VideoFile(name = "$dirName/$n", path = f.uri.toString(), uri = f.uri, size = safeLength(f), parentFolder = parentName)
                        break
                    }
                }
            }
            if (mainVideo == null) {
                for (f in files) {
                    if (f.isFile) {
                        val n = f.name ?: continue
                        val ext = n.substringAfterLast('.', "").lowercase()
                        if (ext == "mp4") {
                            mainVideo = VideoFile(name = "$dirName/$n", path = f.uri.toString(), uri = f.uri, size = safeLength(f), parentFolder = parentName)
                            break
                        }
                    }
                }
            }
            if (mainVideo != null) {
                result.add(mainVideo)
            }
            return
        }
        val nowInCache = inCache || dirName.contains("web_video_cache", ignoreCase = true) || dirName.contains("video_cache", ignoreCase = true)
        for (file in files) {
            if (file.isDirectory) {
                scanSmart(file, result, if (nowInCache) dirName else parentName, nowInCache)
            } else if (file.isFile) {
                val name = file.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext == "ts" && nowInCache) continue
                if (ext == "ts.des") continue
                if (ext == "m3u8" && isAudioIndex(name)) continue
                if (ext in VIDEO_EXTENSIONS) {
                    result.add(VideoFile(name = name, path = file.uri.toString(), uri = file.uri,
                        size = safeLength(file),
                        parentFolder = if (nowInCache) dirName else parentName))
                }
            }
        }
    }

    private fun isAudioIndex(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("audio") || lower.contains("sound")
    }

    private fun safeLength(file: androidx.documentfile.provider.DocumentFile): Long {
        return try { file.length() } catch (_: Exception) { 0L }
    }
}
