package com.example.myvideoplayer.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF 目录扫描器。
 *
 * 不用 `DocumentFile.listFiles()` + 逐项 `.name` / `.length()` 的原因：
 * `DocumentFile` 的每个属性访问都是一次独立的 Binder IPC，120 个文件就要
 * 120 × 2 次往返。实测取 name + size 单这一步要 633ms，而用一次 cursor
 * 批量查询只要 43ms（同一个目录、同一份数据）。
 *
 * 这里改为每层目录一次 `DocumentsContract` children 查询，四个字段
 * （documentId / displayName / mimeType / size）一次拿全。
 */
object FileScanner {

    val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "webm", "m3u8", "ts", "mov", "flv", "wmv", "rmvb", "m4v", "3gp")

    data class VideoFile(
        val name: String,
        val path: String,
        val uri: Uri,
        val size: Long = 0,
        val parentFolder: String = "",
        /** 文件修改时间（毫秒），来自 SAF COLUMN_LAST_MODIFIED，时间排序用 */
        val lastModified: Long = 0
    )

    data class ScanResult(
        val rootName: String,
        val videos: List<VideoFile>
    )

    /** cursor 一行，只留扫描需要的字段 */
    private class Entry(
        val docId: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )

    suspend fun scanFolder(context: Context, folderUri: Uri): ScanResult {
        return withContext(Dispatchers.IO) {
            val videos = mutableListOf<VideoFile>()
            val rootDocId = DocumentsContract.getTreeDocumentId(folderUri)
            val rootName = queryDisplayName(context, folderUri, rootDocId)
                ?: folderUri.lastPathSegment ?: ""

            val entries = listChildren(context, folderUri, rootDocId)
            scanEntries(context, folderUri, entries, videos, rootName, false, rootName)

            ScanResult(rootName, videos)
        }
    }

    /**
     * 一次查询拿到目录下所有直接子项的四个字段。
     * 只发一次 Binder 往返，与子项数量无关（120 项实测 43ms）。
     */
    private fun listChildren(context: Context, treeUri: Uri, docId: String): List<Entry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = ArrayList<Entry>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(2)
                    out.add(
                        Entry(
                            docId = c.getString(0) ?: continue,
                            name = c.getString(1) ?: "",
                            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            // 部分 provider 不给 size 列，一律按 0 处理
                            size = if (c.isNull(3)) 0L else c.getLong(3),
                            // 部分 provider 不给修改时间，同样按 0 处理（排序时沉底）
                            lastModified = if (c.isNull(4)) 0L else c.getLong(4)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("FileScanner", "列目录失败: $childrenUri", e)
        }
        return out
    }

    /** 取目录自身的显示名，同样是单次查询 */
    private fun queryDisplayName(context: Context, treeUri: Uri, docId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return try {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            Log.w("FileScanner", "取目录名失败: $docUri", e)
            null
        }
    }

    private fun documentUri(treeUri: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /**
     * 递归扫描。逻辑与改造前一致（含 web_video_cache / video_cache 特判），
     * 只是数据来源从 DocumentFile 换成了批量 cursor 的结果。
     */
    private fun scanEntries(
        context: Context,
        treeUri: Uri,
        entries: List<Entry>,
        result: MutableList<VideoFile>,
        parentName: String,
        inCache: Boolean,
        selfName: String
    ) {
        val dirName = selfName
        val isCacheVideoDir = inCache &&
            dirName.matches(Regex("^\\d+$")) &&
            entries.any { it.isDirectory && (it.name == "segments" || it.name == "Segments") }

        if (isCacheVideoDir) {
            var mainVideo: VideoFile? = null
            for (e in entries) {
                if (e.isDirectory) continue
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (ext == "m3u8") {
                    mainVideo = toVideoFile(treeUri, e, "$dirName/${e.name}", parentName)
                    break
                }
            }
            if (mainVideo == null) {
                for (e in entries) {
                    if (e.isDirectory) continue
                    val ext = e.name.substringAfterLast('.', "").lowercase()
                    if (ext == "mp4") {
                        mainVideo = toVideoFile(treeUri, e, "$dirName/${e.name}", parentName)
                        break
                    }
                }
            }
            mainVideo?.let { result.add(it) }
            return
        }

        val nowInCache = inCache ||
            dirName.contains("web_video_cache", ignoreCase = true) ||
            dirName.contains("video_cache", ignoreCase = true)

        for (e in entries) {
            if (e.isDirectory) {
                // 子目录递归：先按需再查一次它的 children
                val childEntries = listChildren(context, treeUri, e.docId)
                scanEntries(
                    context, treeUri, childEntries, result,
                    if (nowInCache) dirName else parentName,
                    nowInCache,
                    e.name
                )
                continue
            }
            val name = e.name
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext == "ts" && nowInCache) continue
            if (ext == "ts.des") continue
            if (ext == "m3u8" && isAudioIndex(name)) continue
            if (ext in VIDEO_EXTENSIONS) {
                result.add(
                    toVideoFile(
                        treeUri, e, name,
                        if (nowInCache) dirName else parentName
                    )
                )
            }
        }
    }

    private fun toVideoFile(treeUri: Uri, e: Entry, name: String, parentFolder: String): VideoFile {
        val uri = documentUri(treeUri, e.docId)
        return VideoFile(
            name = name, path = uri.toString(), uri = uri,
            size = e.size, parentFolder = parentFolder, lastModified = e.lastModified
        )
    }

    private fun isAudioIndex(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("audio") || lower.contains("sound")
    }
}
