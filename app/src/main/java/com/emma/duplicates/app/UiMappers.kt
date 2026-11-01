package com.emma.duplicates.app

import android.content.res.Resources
import com.emma.duplicates.R
import com.emma.duplicates.core.model.FileCategory
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.data.preferences.AppPreferences
import com.emma.duplicates.ui.model.UiFileCategory
import java.io.File

internal fun scanLocationSummary(
    resources: Resources,
    availableVolumes: List<AvailableStorageVolume>,
    preferences: AppPreferences,
): String {
    val summary =
        if (preferences.scanSelectedItemsOnly) {
            preferences.selectedScanPaths
                .sorted()
                .joinToString(", ") { File(it).name }
        } else {
            availableVolumes
                .filter { it.id in preferences.selectedVolumeIds }
                .joinToString(resources.getString(R.string.summary_separator)) { it.label }
        }
    return summary.ifBlank { resources.getString(R.string.no_locations_selected) }
}

fun FileCategory.toUiCategory(): UiFileCategory =
    when (this) {
        FileCategory.PHOTOS -> UiFileCategory.PHOTOS
        FileCategory.VIDEOS -> UiFileCategory.VIDEOS
        FileCategory.AUDIO -> UiFileCategory.AUDIO
        FileCategory.DOCUMENTS -> UiFileCategory.DOCUMENTS
    }

fun String.toUiCategory(): UiFileCategory =
    runCatching {
        FileCategory.valueOf(this).toUiCategory()
    }.getOrDefault(UiFileCategory.DOCUMENTS)
