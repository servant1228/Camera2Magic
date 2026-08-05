package com.nothing.camera2magic.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

/**
 * 把选中媒体的 content:// URI 解析成可读的真实路径，用于界面展示。
 *
 * 解析优先级：
 * 1. MediaStore 媒体（content://media/...）→ 直接查询 DATA 得到真实绝对路径；
 * 2. 查不到 DATA 时用 RELATIVE_PATH + DISPLAY_NAME 拼出 /storage/emulated/0/... 路径；
 * 3. 其他 content URI → 回退为文件名（DISPLAY_NAME）；
 * 4. 都失败 → 返回 null，由调用方继续显示原始 URI。
 */
object MediaPathResolver {

    fun resolveDisplayPath(context: Context, uri: Uri): String? {
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> runCatching {
                val resolver = context.contentResolver
                val displayName = queryString(resolver, uri, OpenableColumns.DISPLAY_NAME)
                queryRealPath(resolver, uri, displayName) ?: displayName
            }.getOrNull()
            else -> uri.toString()
        }
    }

    private fun queryRealPath(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String?,
    ): String? {
        // 只有 MediaStore 提供的 URI 才能查到 DATA / RELATIVE_PATH
        if (uri.authority?.startsWith("media") != true) return null
        val data = queryString(resolver, uri, MediaStore.MediaColumns.DATA)
        if (!data.isNullOrBlank()) return data
        if (displayName.isNullOrBlank()) return null
        val relativePath = queryString(resolver, uri, MediaStore.MediaColumns.RELATIVE_PATH) ?: ""
        return if (relativePath.isBlank()) {
            null
        } else {
            "/storage/emulated/0/$relativePath$displayName"
        }
    }

    private fun queryString(
        resolver: ContentResolver,
        uri: Uri,
        column: String,
    ): String? = runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(column)
                if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            } else {
                null
            }
        }
    }.getOrNull()
}
