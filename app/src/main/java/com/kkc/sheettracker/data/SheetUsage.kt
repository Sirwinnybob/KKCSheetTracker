package com.kkc.sheettracker.data

import java.util.Locale

const val SHEET_USAGE_PREFIX = "sheet_usage|"
const val SHEET_USAGE_HIDDEN_PREFIX = "sheet_usage_hidden|"

fun sheetUsageKey(material: String): String = SHEET_USAGE_PREFIX + material.trim().lowercase(Locale.ROOT)
fun sheetUsageHiddenKey(material: String): String = SHEET_USAGE_HIDDEN_PREFIX + material.trim().lowercase(Locale.ROOT)

/** Keyboard type is only a hint: validate pasted text and integer overflow as well. */
fun parseWholeSheets(text: String): Int? =
    text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
