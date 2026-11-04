package com.emma.duplicates.data

import com.emma.duplicates.core.database.RecoveryFile

data class RecoveryEntry(
    val file: RecoveryFile,
    val sizeBytes: Long?,
    val previewPath: String?,
    val available: Boolean,
)
