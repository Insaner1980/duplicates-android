package com.emma.duplicates.core.model

data class ExactDuplicateMember(
    val file: FileMetadata,
    val quickSha256: String,
    val fullSha256: String,
)

data class ExactDuplicateGroup(
    val id: String,
    val category: FileCategory,
    val sizeBytes: Long,
    val contentHash: String,
    val members: List<ExactDuplicateMember>,
) {
    val copyCount: Int
        get() = members.size

    val reclaimableBytes: Long
        get() = sizeBytes * (members.size - 1).coerceAtLeast(0)
}
