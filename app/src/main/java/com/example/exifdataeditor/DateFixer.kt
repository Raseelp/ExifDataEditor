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

    private const val TAG = "DateFixer"

    fun fixOne(context: Context, item: MediaItem): FixResult {
        val takenMillis = item.dateTaken ?: return FixResult.Skipped
        if (takenMillis <= 0) return FixResult.Skipped
        val uri = Uri.parse(item.uri)
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            fixViaFilePath(context, uri, takenMillis)
        } else {
            FixResult.NeedsGrant
        }
    }

    fun changeDate(context: Context, item: MediaItem, newDateMillis: Long): FixResult {
        val uri = Uri.parse(item.uri)
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            fixViaFilePath(context, uri, newDateMillis)
        } else {
            FixResult.NeedsGrant
        }
    }

    fun fixAll(
        context: Context,
        items: List<MediaItem>,
        newDate: Long?,
        progress: (FixProgress) -> Unit
    ): BatchResult {
        progress(FixProgress.Preparing)

        var fixed = 0
        var skipped = 0
        val needsGrant = mutableListOf<Uri>()
        val total = items.size
        var index = 0

        for (item in items) {
            index++
            progress(FixProgress.Processing(index, total))

            val result = if (newDate != null) {
                changeDate(context, item, newDate)
            } else {
                fixOne(context, item)
            }

            when (result) {
                FixResult.Success     -> fixed++
                FixResult.Skipped     -> skipped++
                FixResult.NeedsGrant  -> needsGrant.add(Uri.parse(item.uri))
                is FixResult.Failed   -> skipped++
            }
        }

        if (needsGrant.isNotEmpty()) progress(FixProgress.RequestingPermission)

        return BatchResult(fixed, skipped, needsGrant)
    }

    fun fixAllWithGrant(
        context: Context,
        items: List<MediaItem>,
        newDate: Long?,
        progress: (FixProgress) -> Unit
    ): Int {
        var fixed = 0
        val resolver = context.contentResolver
        val total = items.size
        var index = 0

        for (item in items) {
            index++
            progress(FixProgress.Processing(index, total))

            val targetMillis: Long = when {
                newDate != null && newDate > 0                -> newDate
                item.dateTaken != null && item.dateTaken > 0  -> item.dateTaken
                else                                           -> continue
            }

            val uri = Uri.parse(item.uri)

            try {
                val input = resolver.openInputStream(uri) ?: continue
                val bytes = input.readBytes()
                input.close()

                val output = resolver.openOutputStream(uri, "rwt") ?: continue
                output.write(bytes)
                output.flush()
                output.close()

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATE_MODIFIED, targetMillis / 1000L)
                    put(MediaStore.Images.Media.DATE_TAKEN, targetMillis)
                }
                resolver.update(uri, values, null, null)

                val path = resolver.query(
                    uri,
                    arrayOf(MediaStore.Images.Media.DATA),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst())
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                    else null
                }

                if (path != null) {
                    File(path).setLastModified(targetMillis)
                    MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                }

                fixed++
            } catch (e: Exception) {
                Log.e(TAG, "fixAllWithGrant failed for ${item.name}", e)
            }
        }

        progress(FixProgress.Rescanning)
        return fixed
    }

    fun requestWriteGrant(
        context: Context,
        uris: List<Uri>,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, uris)
        launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    @Suppress("DEPRECATION")
    private fun fixViaFilePath(context: Context, uri: Uri, targetMillis: Long): FixResult {
        return try {
            val path = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                else null
            } ?: return FixResult.Failed("Path null")

            val file = File(path)
            if (!file.exists()) return FixResult.Failed("File missing")

            val ok = file.setLastModified(targetMillis)

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATE_MODIFIED, targetMillis / 1000L)
                put(MediaStore.Images.Media.DATE_TAKEN, targetMillis)
            }
            context.contentResolver.update(uri, values, null, null)
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)

            if (ok) FixResult.Success else FixResult.Failed("setLastModified false")
        } catch (e: Exception) {
            FixResult.Failed(e.message ?: "Unknown error")
        }
    }

    private fun refreshMediaStore(context: Context, uri: Uri) {
        try { context.contentResolver.notifyChange(uri, null) } catch (e: Exception) { /* ignore */ }
        try {
            val path = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                else null
            }
            if (path != null) MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        } catch (e: Exception) { /* ignore */ }
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