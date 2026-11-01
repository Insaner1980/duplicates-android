package com.emma.duplicates.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.R
import com.emma.duplicates.core.database.DuplicateGroupWithMembers
import com.emma.duplicates.core.database.ScanSessionWithGroups
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.data.preferences.PreferencesRepository
import com.emma.duplicates.domain.deletion.DeletionCoordinator
import com.emma.duplicates.domain.deletion.DeletionGroup
import com.emma.duplicates.domain.deletion.DeletionResult
import com.emma.duplicates.ui.model.UiFileCategory
import com.emma.duplicates.ui.results.DuplicateGroupCardUiState
import com.emma.duplicates.ui.results.ResultMemberUiState
import com.emma.duplicates.ui.results.ResultsFilter
import com.emma.duplicates.ui.results.ResultsSort
import com.emma.duplicates.ui.results.ResultsUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

// Results owns transient controls; Room owns all membership and selection.
@Suppress("TooManyFunctions")
class ResultsViewModel(
    private val scanStore: ScanStore,
    private val preferencesRepository: PreferencesRepository? = null,
    private val deletionCoordinator: DeletionCoordinator? = null,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val controls =
        MutableStateFlow(
            Controls(
                filter =
                    ResultsFilter.entries.firstOrNull {
                        it.name ==
                            savedStateHandle.get<String>(
                                "filter",
                            )
                    } ?: ResultsFilter.ALL,
                sort =
                    ResultsSort.entries.firstOrNull { it.name == savedStateHandle.get<String>("sort") }
                        ?: ResultsSort.LARGEST_RECLAIMABLE,
                query = savedStateHandle["query"] ?: "",
                search = savedStateHandle["search"] ?: false,
            ),
        )
    private val mutationMutex = Mutex()
    private var pending: PendingBatch? = null
    val state =
        combine(scanStore.activeResults, controls, ::toUiState)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MILLIS),
                toUiState(null, controls.value),
            )

    fun setSearchVisible(visible: Boolean) = update { copy(search = visible, query = if (visible) query else "") }

    fun setSearchQuery(query: String) = update { copy(query = query) }

    fun setFilter(filter: ResultsFilter) = update { copy(filter = filter) }

    fun setSort(sort: ResultsSort) = update { copy(sort = sort, sortMenu = false, overflow = false) }

    fun setOverflowVisible(visible: Boolean) = update { copy(overflow = visible) }

    fun setSortVisible(visible: Boolean) = update { copy(sortMenu = visible) }

    fun showAll() = update { copy(query = "", filter = ResultsFilter.ALL) }

    fun dismissMessage() = update { copy(message = null) }

    fun dismissDeletionResult() = update { copy(result = null, message = null) }

    fun changeSelection(
        memberId: String,
        selected: Boolean,
    ) {
        if (locked()) return
        viewModelScope.launch {
            mutationMutex.withLock {
                if (!locked() && !scanStore.setMemberSelected(memberId, selected)) {
                    update { copy(message = R.string.keep_at_least_one_copy) }
                }
            }
        }
    }

    fun requestClear() {
        if (!locked()) update { copy(clear = true, overflow = false) }
    }

    fun dismissClear() = update { copy(clear = false) }

    fun confirmClear() {
        if (locked()) return
        update { copy(clear = false, busy = true) }
        viewModelScope.launch {
            try {
                mutationMutex.withLock {
                    if (scanStore.pendingDeletions().isEmpty()) {
                        scanStore.clearScanMetadata()
                    } else {
                        update { copy(message = R.string.deletion_recovery_pending) }
                    }
                }
            } finally {
                update { copy(busy = false) }
            }
        }
    }

    fun requestDelete() {
        if (locked() || deletionCoordinator == null || preferencesRepository == null) return
        update { copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                mutationMutex.withLock {
                    if (!deletionReady()) return@withLock
                    val results = scanStore.activeResults.first() ?: return@withLock
                    val batch = capture(results) ?: return@withLock
                    pending = batch
                    if (preferencesRepository.preferences.first().confirmBeforeDelete) {
                        update {
                            copy(
                                confirmation = true,
                                confirmationCount = batch.ids.size,
                                confirmationBytes = batch.bytes,
                                confirmationHiddenCount = batch.hidden,
                            )
                        }
                    } else {
                        execute(batch)
                    }
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                update { copy(message = R.string.deletion_incomplete) }
            } finally {
                update { copy(busy = false) }
            }
        }
    }

    fun dismissDelete() {
        pending = null
        update { copy(confirmation = false) }
    }

    fun confirmDelete() {
        if (controls.value.busy) return
        val batch = pending ?: return
        update { copy(confirmation = false, busy = true) }
        viewModelScope.launch {
            try {
                mutationMutex.withLock { if (deletionReady()) execute(batch) }
            } catch (
                canceled: CancellationException,
            ) {
                throw canceled
            } catch (
                _: Exception,
            ) {
                update { copy(message = R.string.deletion_incomplete) }
            } finally {
                pending = null
                update { copy(busy = false) }
            }
        }
    }

    private suspend fun deletionReady(): Boolean {
        if (scanStore.pendingDeletions().isNotEmpty() || scanStore.runningSession.first() != null) {
            update { copy(message = R.string.deletion_recovery_pending) }
            return false
        }
        return true
    }

    private suspend fun execute(batch: PendingBatch) {
        val current = scanStore.activeResults.first()?.let(::capture)
        val sameSession = current?.sessionId == batch.sessionId
        val sameMembers = current?.ids == batch.ids && current.groups == batch.groups
        if (!sameSession || !sameMembers) {
            pending = null
            update { copy(confirmation = false, message = R.string.deletion_selection_changed) }
            return
        }
        val result = deletionCoordinator!!.delete(batch.groups)
        pending = null
        update { copy(confirmation = false, result = result) }
    }

    private fun capture(results: ScanSessionWithGroups): PendingBatch? {
        val selected =
            results.groups.flatMap { it.members }.filter {
                it.member.selectedForDeletion &&
                    it.eligibleForDeletion()
            }
        if (selected.isEmpty()) return null
        val ids = selected.mapTo(linkedSetOf()) { it.member.id }
        val visible = visibleGroups(results, controls.value).flatMap { it.members }.mapTo(hashSetOf()) { it.member.id }
        return PendingBatch(
            results.session.id,
            ids,
            selected.sumOf { it.file.sizeBytes },
            ids.count { it !in visible },
            results.groups
                .filter { group -> group.members.any { it.member.id in ids } }
                .map { toDeletionGroup(it, ids) },
        )
    }

    private fun toUiState(
        results: ScanSessionWithGroups?,
        control: Controls,
    ): ResultsUiState {
        val visible = visibleGroups(results, control)
        val selected =
            results
                ?.groups
                .orEmpty()
                .flatMap { it.members }
                .filter { it.member.selectedForDeletion && it.eligibleForDeletion() }
        val visibleIds = visible.flatMap { it.members }.mapTo(hashSetOf()) { it.member.id }
        val available = results?.groups.orEmpty().sumOf(::availableBytes)
        return ResultsUiState(
            hasCompletedScan = results != null,
            duplicateFileCount = results?.groups.orEmpty().sumOf { it.members.size },
            duplicateGroupCount = results?.groups.orEmpty().size,
            reclaimableBytes = available,
            availableBytes = available,
            sessionId = results?.session?.id,
            scannedFileCount = results?.session?.scannedFileCount ?: 0,
            selectedFilter = control.filter,
            selectedSort = control.sort,
            searchVisible = control.search,
            searchQuery = control.query,
            overflowVisible = control.overflow,
            sortVisible = control.sortMenu,
            clearConfirmationVisible = control.clear,
            groups = visible.map(::toCard),
            selectedCount = selected.size,
            selectedBytes = selected.sumOf { it.file.sizeBytes },
            hiddenSelectedCount = selected.count { it.member.id !in visibleIds },
            busy = control.busy,
            deleteConfirmationVisible = control.confirmation,
            confirmationCount = control.confirmationCount,
            confirmationBytes = control.confirmationBytes,
            confirmationHiddenCount = control.confirmationHiddenCount,
            messageRes = control.message,
            deletionResult = control.result,
        )
    }

    private fun visibleGroups(
        results: ScanSessionWithGroups?,
        control: Controls,
    ): List<DuplicateGroupWithMembers> =
        results
            ?.groups
            .orEmpty()
            .filter { group ->
                (
                    control.filter == ResultsFilter.ALL || control.filter.name ==
                        group.group.category
                            .toUiCategory()
                            .name
                ) &&
                    (
                        control.query.isBlank() || group.group.displayTitle.contains(control.query, true) ||
                            group.members.any {
                                it.file.displayName.contains(control.query, true) ||
                                    it.file.canonicalPath.contains(control.query, true)
                            }
                    )
            }.sortedWith(control.sort.comparator())

    private fun toCard(group: DuplicateGroupWithMembers): DuplicateGroupCardUiState {
        val keepers =
            group.members.count {
                !it.member.selectedForDeletion && it.file.readable &&
                    !it.file.changedSinceScan
            }
        val members =
            group.members
                .sortedWith(
                    compareByDescending<com.emma.duplicates.core.database.DuplicateMemberWithFile> {
                        it.member.recommendedKeep
                    }.thenBy { it.file.canonicalPath },
                ).map { member ->
                    val file = member.file
                    val category = file.category.toUiCategory()
                    ResultMemberUiState(
                        member.member.id,
                        file.id,
                        file.displayName,
                        category,
                        file.sizeBytes,
                        file.canonicalPath,
                        displayLocation(file.parentPath, file.volume),
                        if (category == UiFileCategory.PHOTOS ||
                            category == UiFileCategory.VIDEOS
                        ) {
                            file.contentUri ?: file.canonicalPath
                        } else {
                            null
                        },
                        if (category == UiFileCategory.AUDIO) file.contentUri else null,
                        member.member.selectedForDeletion,
                        member.member.recommendedKeep,
                        member.eligibleForDeletion(),
                        member.member.selectedForDeletion || (member.eligibleForDeletion() && keepers > 1),
                    )
                }
        val first = members.firstOrNull()
        return DuplicateGroupCardUiState(
            group.group.id,
            group.group.displayTitle,
            group.group.category.toUiCategory(),
            members.size,
            availableBytes(group),
            group.members.map { it.file.parentPath }.distinct(),
            first?.thumbnailModel,
            first?.audioContentUri,
            first?.fileId,
            members,
        )
    }

    private fun ResultsSort.comparator(): Comparator<DuplicateGroupWithMembers> =
        when (this) {
            ResultsSort.LARGEST_RECLAIMABLE -> {
                compareByDescending(::availableBytes)
            }

            ResultsSort.MOST_COPIES -> {
                compareByDescending<DuplicateGroupWithMembers> { it.members.size }.thenByDescending(::availableBytes)
            }

            ResultsSort.FILE_NAME -> {
                compareBy { it.group.displayTitle.lowercase(Locale.ENGLISH) }
            }

            ResultsSort.NEWEST -> {
                compareByDescending { it.members.maxOfOrNull { member -> member.file.lastModified } ?: 0L }
            }

            ResultsSort.OLDEST -> {
                compareBy {
                    it.members.minOfOrNull { member -> member.file.lastModified }
                        ?: Long.MAX_VALUE
                }
            }
        }.thenBy { it.group.displayTitle.lowercase(Locale.ENGLISH) }.thenBy { it.group.id }

    private fun locked() = controls.value.busy || controls.value.confirmation

    private fun update(transform: Controls.() -> Controls) {
        controls.update(transform)
        savedStateHandle["filter"] = controls.value.filter.name
        savedStateHandle["sort"] = controls.value.sort.name
        savedStateHandle["query"] = controls.value.query
        savedStateHandle["search"] = controls.value.search
    }

    private data class PendingBatch(
        val sessionId: String,
        val ids: Set<String>,
        val bytes: Long,
        val hidden: Int,
        val groups: List<DeletionGroup>,
    )

    private data class Controls(
        val filter: ResultsFilter = ResultsFilter.ALL,
        val sort: ResultsSort = ResultsSort.LARGEST_RECLAIMABLE,
        val query: String = "",
        val search: Boolean = false,
        val overflow: Boolean = false,
        val sortMenu: Boolean = false,
        val clear: Boolean = false,
        val busy: Boolean = false,
        val confirmation: Boolean = false,
        val confirmationCount: Int = 0,
        val confirmationBytes: Long = 0L,
        val confirmationHiddenCount: Int = 0,
        val message: Int? = null,
        val result: DeletionResult? = null,
    )
}

private const val STATE_STOP_TIMEOUT_MILLIS = 5_000L
