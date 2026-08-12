package com.emma.duplicates.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.core.database.DuplicateGroupWithMembers
import com.emma.duplicates.core.database.ScanSessionWithGroups
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.ui.model.UiFileCategory
import com.emma.duplicates.ui.results.DuplicateGroupCardUiState
import com.emma.duplicates.ui.results.ResultsFilter
import com.emma.duplicates.ui.results.ResultsSort
import com.emma.duplicates.ui.results.ResultsUiState
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ResultsViewModel(
    private val scanStore: ScanStore,
) : ViewModel() {
    private val selectedFilter = MutableStateFlow(ResultsFilter.ALL)
    private val selectedSort = MutableStateFlow(ResultsSort.LARGEST_RECLAIMABLE)
    private val searchVisible = MutableStateFlow(false)
    private val searchQuery = MutableStateFlow("")
    private val overflowVisible = MutableStateFlow(false)
    private val sortVisible = MutableStateFlow(false)
    private val clearConfirmationVisible = MutableStateFlow(false)

    private val searchControls =
        combine(
            selectedFilter,
            selectedSort,
            searchVisible,
            searchQuery,
        ) { filter, sort, visible, query ->
            SearchControls(filter, sort, visible, query)
        }
    private val menuControls =
        combine(
            overflowVisible,
            sortVisible,
            clearConfirmationVisible,
        ) { overflow, sort, clear ->
            MenuControls(overflow, sort, clear)
        }
    private val controls =
        combine(searchControls, menuControls) { search, menu ->
            ResultsControls(
                filter = search.filter,
                sort = search.sort,
                searchVisible = search.visible,
                query = search.query,
                overflowVisible = menu.overflow,
                sortVisible = menu.sort,
                clearConfirmationVisible = menu.clear,
            )
        }

    val state: StateFlow<ResultsUiState> =
        combine(scanStore.activeResults, controls, ::toUiState)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyState(),
            )

    fun setSearchVisible(visible: Boolean) {
        searchVisible.value = visible
        if (!visible) searchQuery.value = ""
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setFilter(filter: ResultsFilter) {
        selectedFilter.value = filter
    }

    fun setSort(sort: ResultsSort) {
        selectedSort.value = sort
        sortVisible.value = false
        overflowVisible.value = false
    }

    fun setOverflowVisible(visible: Boolean) {
        overflowVisible.value = visible
    }

    fun setSortVisible(visible: Boolean) {
        sortVisible.value = visible
    }

    fun requestClear() {
        overflowVisible.value = false
        clearConfirmationVisible.value = true
    }

    fun dismissClear() {
        clearConfirmationVisible.value = false
    }

    fun confirmClear() {
        clearConfirmationVisible.value = false
        viewModelScope.launch { scanStore.clearScanMetadata() }
    }

    private fun toUiState(
        results: ScanSessionWithGroups?,
        controls: ResultsControls,
    ): ResultsUiState {
        val groups =
            results?.groups
                .orEmpty()
                .asSequence()
                .filter { group -> controls.filter.matches(group.group.category.toUiCategory()) }
                .filter { group -> group.matches(controls.query) }
                .sortedWith(controls.sort.comparator())
                .map(::toCard)
                .toList()
        return ResultsUiState(
            hasCompletedScan = results != null,
            duplicateFileCount = results?.session?.duplicateFileCount ?: 0,
            duplicateGroupCount = results?.session?.duplicateGroupCount ?: 0,
            reclaimableBytes = results?.session?.reclaimableBytes ?: 0L,
            selectedFilter = controls.filter,
            selectedSort = controls.sort,
            searchVisible = controls.searchVisible,
            searchQuery = controls.query,
            clearConfirmationVisible = controls.clearConfirmationVisible,
            groups = groups,
            overflowVisible = controls.overflowVisible,
            sortVisible = controls.sortVisible,
        )
    }

    private fun toCard(group: DuplicateGroupWithMembers): DuplicateGroupCardUiState {
        val category = group.group.category.toUiCategory()
        val previewFile =
            group.members
                .asSequence()
                .filter { category != UiFileCategory.AUDIO || it.file.contentUri != null }
                .minByOrNull { it.file.id }
                ?.file
                ?: group.members.minByOrNull { it.file.id }?.file
        val thumbnail =
            if (category == UiFileCategory.PHOTOS || category == UiFileCategory.VIDEOS) {
                previewFile?.let { it.contentUri ?: it.canonicalPath }
            } else {
                null
            }
        val audioContentUri =
            if (category == UiFileCategory.AUDIO) {
                previewFile?.contentUri
            } else {
                null
            }
        return DuplicateGroupCardUiState(
            id = group.group.id,
            title = group.group.displayTitle,
            category = category,
            copyCount = group.group.copyCount,
            reclaimableBytes = group.group.reclaimableBytes,
            paths = group.members.map { it.file.parentPath }.distinct(),
            thumbnailModel = thumbnail,
            audioContentUri = audioContentUri,
            previewFileId = previewFile?.id,
        )
    }

    private fun DuplicateGroupWithMembers.matches(query: String): Boolean {
        if (query.isBlank()) return true
        return group.displayTitle.contains(query, ignoreCase = true) ||
            members.any { member ->
                member.file.displayName.contains(query, ignoreCase = true) ||
                    member.file.parentPath.contains(query, ignoreCase = true)
            }
    }

    private fun ResultsSort.comparator(): Comparator<DuplicateGroupWithMembers> =
        when (this) {
            ResultsSort.LARGEST_RECLAIMABLE ->
                compareByDescending<DuplicateGroupWithMembers> { it.group.reclaimableBytes }
                    .thenBy { it.group.displayTitle.lowercase(Locale.ENGLISH) }
            ResultsSort.MOST_COPIES ->
                compareByDescending<DuplicateGroupWithMembers> { it.group.copyCount }
                    .thenByDescending { it.group.reclaimableBytes }
            ResultsSort.FILE_NAME ->
                compareBy { it.group.displayTitle.lowercase(Locale.ENGLISH) }
            ResultsSort.NEWEST ->
                compareByDescending<DuplicateGroupWithMembers> { group ->
                    group.members.maxOfOrNull { it.file.lastModified } ?: 0L
                }
            ResultsSort.OLDEST ->
                compareBy { group -> group.members.minOfOrNull { it.file.lastModified } ?: Long.MAX_VALUE }
        }.thenBy { it.group.id }

    private fun ResultsFilter.matches(category: UiFileCategory): Boolean =
        this == ResultsFilter.ALL || name == category.name

    private fun emptyState() =
        ResultsUiState(
            hasCompletedScan = false,
            duplicateFileCount = 0,
            duplicateGroupCount = 0,
            reclaimableBytes = 0L,
            selectedFilter = ResultsFilter.ALL,
            selectedSort = ResultsSort.LARGEST_RECLAIMABLE,
            searchVisible = false,
            searchQuery = "",
            clearConfirmationVisible = false,
            groups = emptyList(),
        )

    private data class ResultsControls(
        val filter: ResultsFilter,
        val sort: ResultsSort,
        val searchVisible: Boolean,
        val query: String,
        val overflowVisible: Boolean,
        val sortVisible: Boolean,
        val clearConfirmationVisible: Boolean,
    )

    private data class SearchControls(
        val filter: ResultsFilter,
        val sort: ResultsSort,
        val visible: Boolean,
        val query: String,
    )

    private data class MenuControls(
        val overflow: Boolean,
        val sort: Boolean,
        val clear: Boolean,
    )
}
