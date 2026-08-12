package com.emma.duplicates.app

import com.emma.duplicates.core.model.FileCategory
import com.emma.duplicates.ui.model.UiFileCategory

fun FileCategory.toUiCategory(): UiFileCategory =
    when (this) {
        FileCategory.PHOTOS -> UiFileCategory.PHOTOS
        FileCategory.VIDEOS -> UiFileCategory.VIDEOS
        FileCategory.AUDIO -> UiFileCategory.AUDIO
        FileCategory.DOCUMENTS -> UiFileCategory.DOCUMENTS
    }

fun String.toUiCategory(): UiFileCategory =
    runCatching { FileCategory.valueOf(this).toUiCategory() }.getOrDefault(UiFileCategory.DOCUMENTS)
