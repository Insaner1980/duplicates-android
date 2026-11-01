package com.emma.duplicates.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.R
import com.emma.duplicates.data.StorageBrowser
import com.emma.duplicates.data.preferences.PreferencesRepository
import com.emma.duplicates.ui.settings.ScanLocationUiState
import com.emma.duplicates.ui.settings.ScanSelectionUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Paths

class ScanLocationsViewModel(
    private val browser: StorageBrowser,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ScanSelectionUiState())
    val state = mutableState.asStateFlow()
    private val mutableCompleted = MutableStateFlow(false)
    val completed = mutableCompleted.asStateFlow()
    private var navigationJob: Job? = null
    private var currentVolumeId: String? = null

    init {
        viewModelScope.launch {
            val preferences = preferencesRepository.preferences.first()
            mutableState.value =
                ScanSelectionUiState(
                    selectedItemsOnly = preferences.scanSelectedItemsOnly,
                    selectedPaths = preferences.selectedScanPaths,
                    locations =
                        browser.mountedVolumes().map { volume ->
                            ScanLocationUiState(
                                volume.id,
                                volume.label,
                                volume.totalBytes,
                                volume.freeBytes,
                                volume.id in preferences.selectedVolumeIds,
                                true,
                            )
                        },
                    loading = false,
                )
        }
    }

    fun setSelectedItemsOnly(selectedItemsOnly: Boolean) {
        navigationJob?.cancel()
        currentVolumeId = null
        mutableState.update {
            it.copy(selectedItemsOnly = selectedItemsOnly, location = null, loading = false, messageRes = null)
        }
    }

    fun setVolumeSelected(
        id: String,
        selected: Boolean,
    ) {
        mutableState.update { state ->
            state.copy(locations = state.locations.map { if (it.id == id) it.copy(selected = selected) else it })
        }
    }

    fun openVolume(id: String) {
        currentVolumeId = id
        openDirectory(null)
    }

    fun openDirectory(path: String?) {
        val id = currentVolumeId ?: return
        navigationJob?.cancel()
        navigationJob =
            viewModelScope.launch {
                mutableState.update { it.copy(loading = true, messageRes = null, selectedItemsOnly = true) }
                val location = browser.open(id, path)
                mutableState.update {
                    it.copy(
                        loading = false,
                        location = location ?: it.location,
                        messageRes = if (location == null) R.string.folder_not_accessible else null,
                    )
                }
            }
    }

    fun navigateBack(): Boolean {
        val location = state.value.location ?: return false
        if (location.parentPath == null) {
            showStorage()
        } else {
            openDirectory(location.parentPath)
        }
        return true
    }

    fun showStorage() {
        navigationJob?.cancel()
        currentVolumeId = null
        mutableState.update { it.copy(location = null, loading = false, messageRes = null) }
    }

    fun setPathSelected(
        path: String,
        selected: Boolean,
    ) {
        val location = state.value.location
        val available = location?.currentPath == path || location?.entries?.any { it.canonicalPath == path } == true
        if (selected && !available) return
        mutableState.update { state ->
            val paths =
                if (selected) {
                    val target = Paths.get(path)
                    if (state.selectedPaths.any { target.startsWith(Paths.get(it)) }) return@update state
                    state.selectedPaths.filterNot { Paths.get(it).startsWith(target) }.toSet() + path
                } else {
                    state.selectedPaths - path
                }
            state.copy(selectedPaths = paths, messageRes = null)
        }
    }

    fun save() {
        val selection = state.value
        if (!selection.canSave || selection.saving) return
        mutableState.update { it.copy(saving = true, messageRes = null) }
        viewModelScope.launch {
            try {
                val roots = if (selection.selectedItemsOnly) browser.scanRoots(selection.selectedPaths) else null
                if (selection.selectedItemsOnly && roots == null) {
                    mutableState.update { it.copy(saving = false, messageRes = R.string.scan_selection_unavailable) }
                    return@launch
                }
                preferencesRepository.setScanSelection(
                    selection.selectedItemsOnly,
                    roots?.map { it.directory.path }?.toSet() ?: selection.selectedPaths,
                    roots?.map { it.id }?.toSet() ?: selection.locations
                        .filter { it.selected }
                        .map { it.id }
                        .toSet(),
                )
                mutableCompleted.value = true
            } catch (_: IOException) {
                mutableState.update { it.copy(saving = false, messageRes = R.string.scan_selection_save_failed) }
            }
        }
    }
}
