package com.example.exifdataeditor

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class MediaScanner(private val context: Context) {

    companion object {
        private val REALISTIC_RANGE = run {
            val min = Calendar.getInstance().apply { set(1990, 0, 1, 0, 0, 0) }.timeInMillis
            val max = Calendar.getInstance().apply { set(2100, 11, 31, 23, 59, 59) }.timeInMillis
            min..max
        }

        fun isRealisticDate(millis: Long): Boolean = millis > 0 && millis in REALISTIC_RANGE
    }

    fun scanAll(threshold: MismatchThreshold = MismatchThreshold.default): List<MediaItem> =
        (scanImages(threshold) + scanVideos(threshold)).sortedByDescending { it.dateModified }

    fun scanImages(threshold: MismatchThreshold = MismatchThreshold.default): List<MediaItem> =
        scanStore(
            contentUri  = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            idCol       = MediaStore.Images.Media._ID,
            nameCol     = MediaStore.Images.Media.DISPLAY_NAME,
            takenCol    = MediaStore.Images.Media.DATE_TAKEN,
            modifiedCol = MediaStore.Images.Media.DATE_MODIFIED,
            addedCol    = MediaStore.Images.Media.DATE_ADDED,
            isVideo     = false,
            threshold   = threshold
        )

    fun scanVideos(threshold: MismatchThreshold = MismatchThreshold.default): List<MediaItem> =
        scanStore(
            contentUri  = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            idCol       = MediaStore.Video.Media._ID,
            nameCol     = MediaStore.Video.Media.DISPLAY_NAME,
            takenCol    = MediaStore.Video.Media.DATE_TAKEN,
            modifiedCol = MediaStore.Video.Media.DATE_MODIFIED,
            addedCol    = MediaStore.Video.Media.DATE_ADDED,
            isVideo     = true,
            threshold   = threshold
        )

    private fun scanStore(
        contentUri:  Uri,
        idCol:       String,
        nameCol:     String,
        takenCol:    String,
        modifiedCol: String,
        addedCol:    String,
        isVideo:     Boolean,
        threshold:   MismatchThreshold
    ): List<MediaItem> {
        val list       = mutableListOf<MediaItem>()
        val projection = arrayOf(idCol, nameCol, takenCol, modifiedCol)

        context.contentResolver.query(contentUri, projection, null, null, "$addedCol DESC")
            ?.use { cursor ->
                val iId       = cursor.getColumnIndexOrThrow(idCol)
                val iName     = cursor.getColumnIndexOrThrow(nameCol)
                val iTaken    = cursor.getColumnIndexOrThrow(takenCol)
                val iModified = cursor.getColumnIndexOrThrow(modifiedCol)

                while (cursor.moveToNext()) {
                    val id            = cursor.getLong(iId)
                    val name          = cursor.getString(iName)
                    var dateTaken     = cursor.getLong(iTaken)
                    val dateModMillis = cursor.getLong(iModified) * 1000L
                    val uri           = ContentUris.withAppendedId(contentUri, id)

                    if (dateTaken <= 0) dateTaken = parseDateFromFilename(name) ?: dateModMillis

                    val takenIsUnrealistic = !isRealisticDate(dateTaken)
                    val modIsUnrealistic   = !isRealisticDate(dateModMillis)
                    val hasMismatch = when {
                        takenIsUnrealistic || modIsUnrealistic -> true
                        else -> abs(dateTaken - dateModMillis) > threshold.millis
                    }

                    list.add(
                        MediaItem(
                            id           = id,
                            name         = name,
                            uri          = uri.toString(),
                            dateTaken    = dateTaken,
                            dateModified = dateModMillis,
                            hasMismatch  = hasMismatch,
                            isVideo      = isVideo
                        )
                    )
                }
            }
        return list
    }
    private fun parseDateFromFilename(name: String): Long? {

        val patterns = listOf(
            Regex("(\\d{8})_(\\d{6})"),
            Regex("(\\d{14})"),
            Regex("(\\d{8})")
        )

        val calendar = Calendar.getInstance()

        val startLimit = Calendar.getInstance().apply {
            set(1990, Calendar.JANUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val tomorrowLimit = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

        for (pattern in patterns) {

            val value = pattern.find(name)?.value ?: continue

            val parsed = try {

                val sdf = when (value.length) {
                    15 -> SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    14 -> SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
                    8  -> SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                    else -> null
                } ?: continue

                sdf.isLenient = false

                sdf.parse(value)?.time

            } catch (e: Exception) {
                null
            }

            if (parsed != null) {

                val isRealistic = parsed in startLimit..tomorrowLimit

                if (isRealistic) {
                    return parsed
                }
            }
        }

        return null
    }
}