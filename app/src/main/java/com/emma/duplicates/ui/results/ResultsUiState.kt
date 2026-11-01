package com.emma.duplicates.ui.results

import com.emma.duplicates.ui.model.UiFileCategory

enum class ResultsFilter {
    ALL,
    PHOTOS,
    VIDEOS,
    AUDIO,
    DOCUMENTS,
}

enum class ResultsSort {
    LARGEST_RECLAIMABLE,
    MOST_COPIES,
    FILE_NAME,
    NEWEST,
    OLDEST,
}

data class DuplicateGroupCardUiState(
    val id: String,
    val title: String,
    val category: UiFileCategory,
    val copyCount: Int,
    val reclaimableBytes: Long,
    val paths: List<String>,
    val thumbnailModel: Any?,
    val audioContentUri: String? = null,
    val previewFileId: String? = null,
    val members: List<ResultMemberUiState> = emptyList(),
)

data class ResultsUiState(
    val hasCompletedScan: Boolean,
    val duplicateFileCount: Int,
    val duplicateGroupCount: Int,
    val reclaimableBytes: Long,
    val selectedFilter: ResultsFilter,
    val selectedSort: ResultsSort,
    val searchVisible: Boolean,
    val searchQuery: String,
    val clearConfirmationVisible: Boolean,
    val groups: List<DuplicateGroupCardUiState>,
    val overflowVisible: Boolean = false,
    val sortVisible: Boolean = false,
    val sessionId: String? = null,
    val scannedFileCount: Int = 0,
    val availableBytes: Long = 0L,
    val selectedBytes: Long = 0L,
    val selectedCount: Int = 0,
    val hiddenSelectedCount: Int = 0,
    val busy: Boolean = false,
    val deleteConfirmationVisible: Boolean = false,
    val confirmationCount: Int = 0,
    val confirmationBytes: Long = 0L,
    val confirmationHiddenCount: Int = 0,
    val messageRes: Int? = null,
    val deletionResult: com.emma.duplicates.domain.deletion.DeletionResult? = null,
)

data class ResultMemberUiState(
    val memberId: String,
    val fileId: String,
    val filename: String,
    val category: UiFileCategory,
    val sizeBytes: Long,
    val fullPath: String,
    val location: String,
    val thumbnailModel: Any?,
    val audioContentUri: String?,
    val selected: Boolean,
    val recommendedKeep: Boolean,
    val eligible: Boolean,
    val canSelect: Boolean,
)
