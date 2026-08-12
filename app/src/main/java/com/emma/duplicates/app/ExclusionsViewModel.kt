package com.emma.duplicates.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.core.database.ExclusionEntity
import com.emma.duplicates.core.database.ExclusionTypes
import com.emma.duplicates.data.ExclusionRepository
import com.emma.duplicates.ui.exclusions.ExclusionItemUiState
import com.emma.duplicates.ui.exclusions.ExclusionKind
import com.emma.duplicates.ui.exclusions.ExclusionsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExclusionsViewModel(
    private val exclusionRepository: ExclusionRepository,
) : ViewModel() {
    private val addSheetVisible = MutableStateFlow(false)

    val state: StateFlow<ExclusionsUiState> =
        combine(exclusionRepository.exclusions, addSheetVisible) { exclusions, sheetVisible ->
            val items = exclusions.map { it.toUiState() }
            ExclusionsUiState(
                folders = items.filter { it.kind == ExclusionKind.FOLDER },
                files = items.filter { it.kind == ExclusionKind.FILE },
                addSheetVisible = sheetVisible,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ExclusionsUiState(folders = emptyList(), files = emptyList()),
        )

    fun showAddSheet() {
        addSheetVisible.value = true
    }

    fun dismissAddSheet() {
        addSheetVisible.value = false
    }

    fun remove(exclusionId: String) {
        viewModelScope.launch { exclusionRepository.remove(exclusionId) }
    }

    private fun ExclusionEntity.toUiState() =
        ExclusionItemUiState(
            id = id,
            kind = if (type == ExclusionTypes.FOLDER) ExclusionKind.FOLDER else ExclusionKind.FILE,
            displayName = displayName,
            canonicalPath = canonicalPath,
        )
}
