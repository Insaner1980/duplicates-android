package com.emma.duplicates.domain.deletion

import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolScanSession

data class VerifiedCleanupFile(
    val item: ToolItemEntity,
    val digest: String,
    val identityKey: String?,
)

data class CleanupBatch(
    val session: ToolScanSession,
    val selected: List<ToolItemEntity>,
    val files: List<VerifiedCleanupFile>,
    val keepers: List<VerifiedCleanupFile>,
    val hiddenCount: Int,
) {
    val selectedBytes: Long get() = files.sumOf { it.item.sizeBytes }
}

data class CleanupOutcome(
    val itemId: String,
    val status: String,
)

data class CleanupResult(
    val outcomes: List<CleanupOutcome>,
) {
    val movedCount: Int get() = outcomes.count { it.status == "moved" }
    val failedCount: Int get() = outcomes.size - movedCount
    val freedBytes: Long get() = 0L
}
