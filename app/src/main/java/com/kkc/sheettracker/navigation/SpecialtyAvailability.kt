package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.ui.specialty.hasClosetRodCutList

internal data class SpecialtyAvailability(
    val hasDeliverySheet: Boolean = false,
    val hasAssemblySheet: Boolean = false,
    val hasPlansElevations: Boolean = false,
    val hasThreeDAssets: Boolean = false,
    val hasClosetRods: Boolean = false
)

internal fun resolveSpecialtyAvailability(
    hasDeliverySheet: () -> Boolean,
    hasAssemblySheet: () -> Boolean,
    hasPlansElevations: () -> Boolean,
    hasThreeDAssets: () -> Boolean,
    hasClosetRods: () -> Boolean
): SpecialtyAvailability = SpecialtyAvailability(
    hasDeliverySheet = hasDeliverySheet(),
    hasAssemblySheet = hasAssemblySheet(),
    hasPlansElevations = hasPlansElevations(),
    hasThreeDAssets = hasThreeDAssets(),
    hasClosetRods = hasClosetRods()
)

internal fun loadSpecialtyAvailability(
    jobRepository: JobRepository,
    folderName: String
): SpecialtyAvailability = resolveSpecialtyAvailability(
    hasDeliverySheet = {
        jobRepository.getJobPdfCatalog(folderName).deliverySheet != null
    },
    hasAssemblySheet = {
        jobRepository.hasReferenceDocument(
            folderName,
            ReferenceDocType.ASSEMBLY
        )
    },
    hasPlansElevations = {
        jobRepository.hasReferenceDocument(
            folderName,
            ReferenceDocType.PLANS_ELEVATIONS
        )
    },
    hasThreeDAssets = { jobRepository.hasThreeDAssets(folderName) },
    hasClosetRods = { hasClosetRodCutList(jobRepository.loadHardwoodsIndex(folderName)) }
)
