package com.example.exifdataeditor

sealed class FixProgress {

    object Preparing : FixProgress()

    object RequestingPermission : FixProgress()

    data class Processing(
        val current: Int,
        val total: Int
    ) : FixProgress()

    object Rescanning : FixProgress()

    object Completed : FixProgress()

    data class Error(val message: String) : FixProgress()
}