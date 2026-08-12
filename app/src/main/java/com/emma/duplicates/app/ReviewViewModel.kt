package com.emma.duplicates.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.core.database.DuplicateGroupWithMembers
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.data.preferences.PreferencesRepository
import com.emma.duplicates.domain.deletion.DeletionCoordinator
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.DeletionGroup
import com.emma.duplicates.domain.deletion.DeletionStatus
import com.emma.duplicates.ui.model.UiFileCategory
import com.emma.duplicates.ui.review.DeletionResultUiState
import com.emma.duplicates.ui.review.ReviewMemberUiState
import com.emma.duplicates.ui.review.ReviewUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReviewViewModel(
    private val groupId: String,
    private val scanStore: ScanStore,
    private val preferencesRepository: PreferencesRepository,
    private val deletionCoordinator: DeletionCoordinator,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ReviewUiState?>(null)
    val state: StateFlow<ReviewUiState?> = mutableState
    private var currentGroup: DuplicateGroupWithMembers? = null

    init {
        viewModelScope.launch {
            scanStore.observeGroup(groupId).collect { group ->
                currentGroup = group
                if (group != null) {
                    val previous = mutableState.value
                    mutableState.value =
                        group.toUiState(
                            deleteConfirmationVisible = previous?.deleteConfirmationVisible ?: false,
                            deletionResult = previous?.deletionResult,
                        )
                } else if (mutableState.value?.deletionResult == null) {
                    mutableState.value = null
                }
            }
        }
    }

    fun changeSelection(
        memberId: String,
        selected: Boolean,
    ) {
        viewModelScope.launch { scanStore.setMemberSelected(memberId, selected) }
    }

    fun requestDelete() {
        if (mutableState.value?.selectedCount == 0) return
        viewModelScope.launch {
            if (preferencesRepository.preferences.first().confirmBeforeDelete) {
                updateState { copy(deleteConfirmationVisible = true) }
            } else {
                deleteSelected()
            }
        }
    }

    fun dismissDelete() {
        updateState { copy(deleteConfirmationVisible = false) }
    }

    fun confirmDelete() {
        updateState { copy(deleteConfirmationVisible = false) }
        viewModelScope.launch { deleteSelected() }
    }

    fun dismissDeletionResult() {
        updateState { copy(deletionResult = null) }
        if (currentGroup == null) mutableState.value = null
    }

    fun groupStillExists(): Boolean = currentGroup != null

    private suspend fun deleteSelected() {
        val group = currentGroup ?: return
        val deletionGroup =
            DeletionGroup(
                id = group.group.id,
                expectedFullHash = group.group.contentHash,
                files =
                    group.members.map { member ->
                        DeletionFile(
                            id = member.file.id,
                            memberId = member.member.id,
                            canonicalPath = member.file.canonicalPath,
                            sizeBytes = member.file.sizeBytes,
                            lastModifiedMillis = member.file.lastModified,
                            contentUri = member.file.contentUri,
                            requiresMediaAuthorization =
                                member.file.contentUri != null &&
                                    member.file.category.toUiCategory() != UiFileCategory.DOCUMENTS,
                            selectedForDeletion = member.member.selectedForDeletion,
                        )
                    },
            )
        try {
            val result = deletionCoordinator.delete(listOf(deletionGroup))
            updateState {
                copy(
                    deleteConfirmationVisible = false,
                    deletionResult =
                        DeletionResultUiState(
                            deletedCount = result.deletedFileCount,
                            failedCount = result.failedFileCount,
                            reclaimedBytes = result.reclaimedBytes,
                            changedFilesKept =
                                result.outcomes.any {
                                    it.status == DeletionStatus.CHANGED ||
                                        it.status == DeletionStatus.REVALIDATION_FAILED
                                },
                            authorizationCanceled =
                                result.outcomes.any { it.status == DeletionStatus.AUTHORIZATION_CANCELED },
                        ),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            updateState {
                copy(
                    deleteConfirmationVisible = false,
                    deletionResult = DeletionResultUiState(deletedCount = 0, failedCount = selectedCount),
                )
            }
        }
    }

    private fun DuplicateGroupWithMembers.toUiState(
        deleteConfirmationVisible: Boolean,
        deletionResult: DeletionResultUiState?,
    ): ReviewUiState {
        val uiCategory = group.category.toUiCategory()
        val validUnselectedCount =
            members.count { member ->
                !member.member.selectedForDeletion &&
                    member.file.readable &&
                    !member.file.changedSinceScan
            }
        val memberStates =
            members
                .sortedWith(
                    compareByDescending<com.emma.duplicates.core.database.DuplicateMemberWithFile> {
                        it.member.recommendedKeep
                    }.thenBy { it.file.canonicalPath },
                ).map { member ->
                    val canSelect =
                        if (member.member.selectedForDeletion) {
                            true
                        } else {
                            member.file.readable &&
                                member.file.writable &&
                                !member.file.changedSinceScan &&
                                validUnselectedCount > 1
                        }
                    ReviewMemberUiState(
                        id = member.member.id,
                        filename = member.file.displayName,
                        sizeBytes = member.file.sizeBytes,
                        dateEpochMillis = member.file.lastModified,
                        path = member.file.canonicalPath,
                        thumbnailModel =
                            if (uiCategory == UiFileCategory.PHOTOS || uiCategory == UiFileCategory.VIDEOS) {
                                member.file.contentUri ?: member.file.canonicalPath
                            } else {
                                null
                            },
                        selectedForDeletion = member.member.selectedForDeletion,
                        recommendedKeep = member.member.recommendedKeep,
                        canSelectForDeletion = canSelect,
                        audioContentUri =
                            if (uiCategory == UiFileCategory.AUDIO) member.file.contentUri else null,
                    )
                }
        return ReviewUiState(
            groupId = group.id,
            title = group.displayTitle,
            category = uiCategory,
            identicalFileCount = group.copyCount,
            reclaimableBytes = group.reclaimableBytes,
            selectedCount = memberStates.count { it.selectedForDeletion },
            selectedBytes =
                members.sumOf { member ->
                    if (
                        member.member.selectedForDeletion &&
                        member.file.readable &&
                        member.file.writable &&
                        !member.file.changedSinceScan
                    ) {
                        member.file.sizeBytes
                    } else {
                        0L
                    }
                },
            members = memberStates,
            deleteConfirmationVisible = deleteConfirmationVisible,
            deletionResult = deletionResult,
        )
    }

    private fun updateState(transform: ReviewUiState.() -> ReviewUiState) {
        mutableState.value = mutableState.value?.transform()
    }
}
