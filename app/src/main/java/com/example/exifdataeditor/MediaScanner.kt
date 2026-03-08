package com.example.exifdataeditor

import android.content.Context
import android.provider.MediaStore
import com.example.exifdataeditor.MediaItem

class MediaScanner(private val context: Context) {

    fun scanImages(): List<MediaItem> {

        val mediaList = mutableListOf<MediaItem>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val query = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        query?.use { cursor ->

            val idColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

            val nameColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

            val pathColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

            val dateTakenColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

            val dateModifiedColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {

                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val path = cursor.getString(pathColumn)
                val dateTaken = cursor.getLong(dateTakenColumn)
                val dateModified = cursor.getLong(dateModifiedColumn)

                mediaList.add(
                    MediaItem(
                        id = id,
                        name = name,
                        path = path,
                        dateTaken = dateTaken,
                        dateModified = dateModified
                    )
                )
            }
        }

        return mediaList
    }
}