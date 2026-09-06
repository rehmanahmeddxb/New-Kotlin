package com.rehman.ahmedreactionstudio.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

data class ImportedMedia(
    val path: String,
    val mime: String,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int
)

object MediaImport {
    fun copy(context: Context, uri: Uri, projectId: String, layerId: String): ImportedMedia? {
        val resolver = context.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val mime = resolver.getType(uri) ?: guessMime(uri)
        val display = displayName(context, uri)
        val ext = extensionFor(mime, display)
        val dest = File(File(context.filesDir, "media/$projectId").apply { mkdirs() }, "$layerId.$ext")
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: return null
        if (!dest.exists() || dest.length() == 0L) return null
        val probe = probe(dest, mime)
        return ImportedMedia(dest.absolutePath, probe.mime.ifBlank { mime }, display, probe.durationMs, probe.width, probe.height)
    }

    fun attachFile(layerId: String, projectId: String, src: File, context: Context, mimeHint: String): ImportedMedia? {
        if (!src.exists() || src.length() == 0L) return null
        val probe = probe(src, mimeHint)
        val ext = extensionFor(probe.mime.ifBlank { mimeHint }, src.name)
        val dest = File(File(context.filesDir, "media/$projectId").apply { mkdirs() }, "$layerId.$ext")
        if (src.canonicalPath != dest.canonicalPath) {
            src.copyTo(dest, overwrite = true)
        }
        return ImportedMedia(dest.absolutePath, probe.mime.ifBlank { mimeHint }, src.nameWithoutExtension, probe.durationMs, probe.width, probe.height)
    }

    fun probe(file: File, mimeHint: String = ""): Probe {
        if (!file.exists()) return Probe(mimeHint)
        if (mimeHint.startsWith("image/") || file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val mime = mimeHint.ifBlank {
                when (file.extension.lowercase()) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> "image/jpeg"
                }
            }
            return Probe(mime, 0L, opts.outWidth.coerceAtLeast(0), opts.outHeight.coerceAtLeast(0))
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: mimeHint
            Probe(mime, duration, width, height)
        } catch (_: Exception) {
            Probe(mimeHint)
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun decode(path: String, maxSide: Int = 1600): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?') ?: "media"
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.')?.ifBlank { fallback } ?: fallback
    }

    private fun guessMime(uri: Uri): String {
        val name = uri.toString().lowercase()
        return when {
            name.contains("video") || name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") -> "video/mp4"
            name.contains("image") || name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp") -> "image/jpeg"
            name.contains("audio") || name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".aac") -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    private fun extensionFor(mime: String, display: String): String {
        val fromName = display.substringAfterLast('.', "").lowercase()
        if (fromName in setOf("mp4", "mkv", "webm", "mov", "jpg", "jpeg", "png", "webp", "gif", "mp3", "m4a", "aac", "wav", "ogg")) {
            return if (fromName == "jpeg") "jpg" else fromName
        }
        return when {
            mime.startsWith("video/") -> "mp4"
            mime.startsWith("image/png") -> "png"
            mime.startsWith("image/webp") -> "webp"
            mime.startsWith("image/") -> "jpg"
            mime.startsWith("audio/") -> "m4a"
            else -> "bin"
        }
    }

    data class Probe(val mime: String = "", val durationMs: Long = 0L, val width: Int = 0, val height: Int = 0)
}
