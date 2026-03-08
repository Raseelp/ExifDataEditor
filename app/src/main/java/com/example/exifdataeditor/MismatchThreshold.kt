package com.example.exifdataeditor

sealed class MismatchThreshold(
    val label:       String,
    val description: String,
    val millis:      Long
) {
    object Minutes : MismatchThreshold("Minutes", "Differ by more than 1 minute",  60_000L)
    object Hours   : MismatchThreshold("Hours",   "Differ by more than 1 hour",    3_600_000L)
    object Days    : MismatchThreshold("Days",    "Differ by more than 1 day",     86_400_000L)
    object Weeks   : MismatchThreshold("Weeks",   "Differ by more than 7 days",    604_800_000L)
    object Months  : MismatchThreshold("Months",  "Differ by more than 30 days",   2_592_000_000L)

    companion object {
        val all: List<MismatchThreshold> = listOf(Minutes, Hours, Days, Weeks, Months)
        val default: MismatchThreshold   = Days
    }
}