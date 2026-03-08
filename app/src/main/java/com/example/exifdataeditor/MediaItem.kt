package com.example.exifdataeditor

data class MediaItem(
    val id: Long,
    val name: String,
    val path: String?,
    val dateTaken: Long?,
    val dateModified: Long
)