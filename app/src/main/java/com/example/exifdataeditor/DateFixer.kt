package com.example.exifdataeditor

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import java.io.File

object DateFixer {

    private const val TAG       = "DateFixer"
    private const val CHUNK     = 8 * 1024
    private const val MAX_BATCH = 50

    fun fixOne(context: Context, item: MediaItem): FixResult {
        val takenMillis = item.dateTaken ?: return FixResult.Skipped
        if (takenMillis <= 0) return FixResult.Skipped
        return applyFix(context, item, Uri.parse(item.uri), takenMillis)
    }

    fun changeDate(context: Context, item: MediaItem, newDateMillis: Long): FixResult =
        applyFix(context, item, Uri.parse(item.uri), newDateMillis)

    fun fixAll(
        context:  Context,
        items:    List<MediaItem>,
        newDate:  Long?,
        progress: (FixProgress) -> Unit
    ): BatchResult {
        progress(FixProgress.Preparing)

        var fixed      = 0
        var skipped    = 0
        val needsGrant = mutableListOf<Uri>()
        val total      = items.size

        items.forEachIndexed { idx, item ->
            progress(FixProgress.Processing(idx + 1, total))

            val result = if (newDate != null) changeDate(context, item, newDate)
            else                 fixOne(context, item)

            when (result) {
                FixResult.Success    -> fixed++
                FixResult.Skipped    -> skipped++
                FixResult.NeedsGrant -> needsGrant.add(Uri.parse(item.uri))
                is FixResult.Failed  -> skipped++
            }
        }

        if (needsGrant.isNotEmpty()) progress(FixProgress.RequestingPermission)
        return BatchResult(fixed, skipped, needsGrant)
    }

    fun fixAllWithGrant(
        context:  Context,
        items:    List<MediaItem>,
        newDate:  Long?,
        progress: (FixProgress) -> Unit
    ): Int {
        var fixed         = 0
        val pathsToRescan = mutableListOf<String>()

        items.forEachIndexed { idx, item ->
            progress(FixProgress.Processing(idx + 1, items.size))

            val targetMillis: Long = when {
                newDate != null && newDate > 0               -> newDate
                item.dateTaken != null && item.dateTaken > 0 -> item.dateTaken
                else                                          -> return@forEachIndexed
            }

            val uri = Uri.parse(item.uri)

            try {
                val path: String? = if (item.isVideo) {
                    fixVideoMetadata(context, uri, item, targetMillis)
                } else {
                    fixImageStreaming(context, uri, item, targetMillis)
                }

                if (path != null) {
                    pathsToRescan.add(path)
                    fixed++
                }
            } catch (e: Exception) {
                Log.e(TAG, "fixAllWithGrant failed for ${item.name}", e)
            }
        }

        progress(FixProgress.Rescanning)
        pathsToRescan.chunked(MAX_BATCH).forEach { batch ->
            MediaScannerConnection.scanFile(context, batch.toTypedArray(), null, null)
        }

        return fixed
    }

    fun requestWriteGrant(
        context:  Context,
        uris:     List<Uri>,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, uris)
        launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    private fun applyFix(
        context:      Context,
        item:         MediaItem,
        uri:          Uri,
        targetMillis: Long
    ): FixResult = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        fixViaFilePath(context, item, uri, targetMillis)
    } else {
        FixResult.NeedsGrant
    }

    private fun fixVideoMetadata(
        context:      Context,
        uri:          Uri,
        item:         MediaItem,
        targetMillis: Long
    ): String? {
        context.contentResolver.update(uri, buildContentValues(targetMillis), null, null)
        val path = queryPath(context, uri, MediaStore.Video.Media.DATA) ?: return null
        File(path).setLastModified(targetMillis)
        return path
    }

    private fun fixImageStreaming(
        context:      Context,
        uri:          Uri,
        item:         MediaItem,
        targetMillis: Long
    ): String? {
        val resolver = context.contentResolver
        val bytes    = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

        resolver.openOutputStream(uri, "rwt")?.use { out ->
            var offset = 0
            while (offset < bytes.size) {
                val len = minOf(CHUNK, bytes.size - offset)
                out.write(bytes, offset, len)
                offset += len
            }
            out.flush()
        } ?: return null

        resolver.update(uri, buildContentValues(targetMillis), null, null)

        val path = queryPath(context, uri, MediaStore.Images.Media.DATA) ?: return null
        File(path).setLastModified(targetMillis)
        return path
    }

    private fun queryPath(context: Context, uri: Uri, dataCol: String): String? =
        context.contentResolver.query(uri, arrayOf(dataCol), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(dataCol))
                else null
            }

    @Suppress("DEPRECATION")
    private fun fixViaFilePath(
        context:      Context,
        item:         MediaItem,
        uri:          Uri,
        targetMillis: Long
    ): FixResult {
        return try {
            val dataCol = if (item.isVideo) MediaStore.Video.Media.DATA
            else              MediaStore.Images.Media.DATA

            val path = queryPath(context, uri, dataCol)
                ?: return FixResult.Failed("Path null")

            val file = File(path)
            if (!file.exists()) return FixResult.Failed("File missing")

            val ok = file.setLastModified(targetMillis)
            context.contentResolver.update(uri, buildContentValues(targetMillis), null, null)
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)

            if (ok) FixResult.Success else FixResult.Failed("setLastModified returned false")
        } catch (e: Exception) {
            FixResult.Failed(e.message ?: "Unknown error")
        }
    }

    private fun buildContentValues(targetMillis: Long): ContentValues =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DATE_MODIFIED, targetMillis / 1000L)
            put(MediaStore.MediaColumns.DATE_TAKEN,    targetMillis)
        }

    sealed class FixResult {
        object Success    : FixResult()
        object Skipped    : FixResult()
        object NeedsGrant : FixResult()
        data class Failed(val reason: String) : FixResult()
    }

    data class BatchResult(
        val fixed:      Int,
        val skipped:    Int,
        val needsGrant: List<Uri>
    ) {
        val total get() = fixed + skipped + needsGrant.size
    }
}