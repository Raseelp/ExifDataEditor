package com.example.exifdataeditor

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

class MediaScanner(private val context: Context) {

    fun scanImages(threshold: MismatchThreshold = MismatchThreshold.default): List<MediaItem> {
        val mediaList = mutableListOf<MediaItem>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol    = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id   = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)

                var dateTaken          = cursor.getLong(takenCol)
                val dateModifiedMillis = cursor.getLong(modifiedCol) * 1000L

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )

                if (dateTaken <= 0) dateTaken = parseDateFromFilename(name) ?: 0L

                val mismatch = dateTaken > 0 &&
                        abs(dateTaken - dateModifiedMillis) > threshold.millis

                mediaList.add(
                    MediaItem(
                        id           = id,
                        name         = name,
                        uri          = contentUri.toString(),
                        dateTaken    = dateTaken,
                        dateModified = dateModifiedMillis,
                        hasMismatch  = mismatch
                    )
                )
            }
        }

        return mediaList
    }

    private fun parseDateFromFilename(name: String): Long? {
        val patterns = listOf(
            Regex("(\\d{8})_(\\d{6})"),
            Regex("(\\d{14})"),
            Regex("(\\d{8})")
        )
        for (pattern in patterns) {
            val value = pattern.find(name)?.value ?: continue
            return try {
                when (value.length) {
                    15   -> SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).parse(value)?.time
                    14   -> SimpleDateFormat("yyyyMMddHHmmss",  Locale.getDefault()).parse(value)?.time
                    8    -> SimpleDateFormat("yyyyMMdd",        Locale.getDefault()).parse(value)?.time
                    else -> null
                }
            } catch (e: Exception) { null }
        }
        return null
    }
}