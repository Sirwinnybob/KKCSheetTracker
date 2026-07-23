package com.kkc.sheettracker.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MoldingLibraryItem(
    val id: String,
    val category: String,
    val fileId: String,
    val name: String,
    val frameStyle: String? = null,
    val hidden: Boolean = false
) : Parcelable

data class MoldingLibrary(
    val categories: List<String> = emptyList(),
    val moldings: List<MoldingLibraryItem> = emptyList()
) {
    val isEmpty: Boolean get() = moldings.isEmpty()
}

data class MoldingUsage(
    val job: String,
    val type: String?,
    val estimatedFeet: Double?
)
