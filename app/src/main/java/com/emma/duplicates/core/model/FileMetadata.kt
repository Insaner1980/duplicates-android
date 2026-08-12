package com.emma.duplicates.core.model

data class FileMetadata(
    val id: String,
    val canonicalPath: String,
    val displayName: String,
    val extension: String,
    val mimeType: String?,
    val category: FileCategory,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val storageVolume: String,
    val parentDirectory: String,
    val isReadable: Boolean,
    val isWritable: Boolean,
    val contentUri: String?,
    val isFavorite: Boolean?,
    val isTrashed: Boolean?,
)
