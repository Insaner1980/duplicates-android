package com.emma.duplicates.core.model

import java.io.File

data class StorageVolumeRoot(
    val directory: File,
    val id: String,
)

enum class ExclusionType {
    FILE,
    FOLDER,
}

data class ScanExclusion(
    val canonicalPath: String,
    val type: ExclusionType,
)
