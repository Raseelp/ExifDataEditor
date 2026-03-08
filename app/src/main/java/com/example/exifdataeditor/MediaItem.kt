package com.example.exifdataeditor

data class MediaItem(
    val id:           Long,
    val name:         String,
    val uri:          String,
    val dateTaken:    Long?,
    val dateModified: Long,
    val hasMismatch:  Boolean
)