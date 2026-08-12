package com.emma.duplicates.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.core.model.ExclusionType
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.data.AddExclusionResult
import com.emma.duplicates.data.ExclusionRepository
import com.emma.duplicates.data.StorageBrowser
import com.emma.duplicates.data.StorageBrowserLocation
import com.emma.duplicates.ui.exclusions.BrowserBreadcrumbUiState
import com.emma.duplicates.ui.exclusions.BrowserEntryUiState
import com.emma.duplicates.ui.exclusions.BrowserMessage
import com.emma.duplicates.ui.exclusions.ExclusionBrowserUiState
import com.emma.duplicates.ui.exclusions.ExclusionKind
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StorageBrowserViewModel(
    private val storageBrowser: StorageBrowser,
    private val exclusionRepository: ExclusionRepository,
    private val kind: ExclusionKind,
) : ViewModel() {
    private val _state = MutableStateFlow(volumeRootState())
    val state: StateFlow<ExclusionBrowserUiState> = _state.asStateFlow()

    private val _selectionCompleted = MutableStateFlow(false)
    val selectionCompleted: StateFlow<Boolean> = _selectionCompleted.asStateFlow()

    private var currentLocation: StorageBrowserLocation? = null
    private var navigationJob: Job? = null
    private var selectionJob: Job? = null

    fun openDirectory(canonicalPath: String) {
        navigationJob?.cancel()
        navigationJob =
            viewModelScope.launch {
                val location = findLocation(canonicalPath)
                if (location == null) {
                    _state.update { it.copy(message = BrowserMessage.INACCESSIBLE) }
                } else {
                    currentLocation = location
                    _state.value = location.toUiState()
                }
            }
    }

    fun navigateBack(): Boolean {
        val location = currentLocation ?: return false
        val parentPath = location.parentPath
        if (parentPath == null) {
            currentLocation = null
            navigationJob?.cancel()
            _state.value = volumeRootState()
        } else {
            openDirectory(parentPath)
        }
        return true
    }

    fun selectCurrentFolder() {
        if (kind != ExclusionKind.FOLDER) return
        val path = currentLocation?.currentPath ?: return
        addExclusion(path, ExclusionType.FOLDER)
    }

    fun selectFile(canonicalPath: String) {
        if (kind != ExclusionKind.FILE) return
        val selectable =
            _state.value.entries.any { entry ->
                !entry.isDirectory && entry.accessible && entry.canonicalPath == canonicalPath
            }
        if (!selectable) return
        addExclusion(canonicalPath, ExclusionType.FILE)
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun consumeSelectionCompleted() {
        _selectionCompleted.value = false
    }

    private fun addExclusion(
        canonicalPath: String,
        type: ExclusionType,
    ) {
        selectionJob?.cancel()
        selectionJob =
            viewModelScope.launch {
                when (exclusionRepository.add(canonicalPath, type)) {
                    AddExclusionResult.ADDED -> {
                        _state.update { it.copy(message = null) }
                        _selectionCompleted.value = true
                    }

                    AddExclusionResult.ALREADY_EXCLUDED ->
                        _state.update { it.copy(message = BrowserMessage.ALREADY_EXCLUDED) }

                    AddExclusionResult.PARENT_ALREADY_EXCLUDED ->
                        _state.update { it.copy(message = BrowserMessage.PARENT_ALREADY_EXCLUDED) }

                    AddExclusionResult.NOT_ACCESSIBLE ->
                        _state.update { it.copy(message = BrowserMessage.INACCESSIBLE) }
                }
            }
    }

    private suspend fun findLocation(canonicalPath: String): StorageBrowserLocation? {
        for (volume in storageBrowser.mountedVolumes()) {
            storageBrowser.open(volume.id, canonicalPath)?.let { return it }
        }
        return null
    }

    private fun volumeRootState(): ExclusionBrowserUiState =
        ExclusionBrowserUiState(
            kind = kind,
            currentPath = VOLUME_ROOT_PATH,
            entries =
                storageBrowser.mountedVolumes().mapNotNull { volume ->
                    val canonicalPath = volume.directory.safeCanonicalPath() ?: return@mapNotNull null
                    BrowserEntryUiState(
                        canonicalPath = canonicalPath,
                        displayName = volume.label,
                        isDirectory = true,
                        accessible = volume.directory.canRead(),
                    )
                },
            canSelectCurrentFolder = false,
        )

    private fun StorageBrowserLocation.toUiState(): ExclusionBrowserUiState =
        ExclusionBrowserUiState(
            kind = kind,
            currentPath = currentPath,
            entries =
                entries
                    .asSequence()
                    .filter { kind == ExclusionKind.FILE || it.isDirectory }
                    .sortedWith(
                        compareByDescending<com.emma.duplicates.data.StorageBrowserEntry> { it.isDirectory }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                            .thenBy { it.displayName },
                    ).map { entry ->
                        BrowserEntryUiState(
                            canonicalPath = entry.canonicalPath,
                            displayName = entry.displayName,
                            isDirectory = entry.isDirectory,
                            accessible = entry.isReadable,
                        )
                    }.toList(),
            breadcrumbs = breadcrumbs(),
            canSelectCurrentFolder = kind == ExclusionKind.FOLDER,
            message = null,
        )

    private fun StorageBrowserLocation.breadcrumbs(): List<BrowserBreadcrumbUiState> {
        val volume = storageBrowser.mountedVolumes().firstOrNull { it.id == volumeId } ?: return emptyList()
        val root = volume.directory.safeCanonicalFile() ?: return emptyList()
        val current = File(currentPath).safeCanonicalFile() ?: return emptyList()
        val rootPath = root.toPath()
        val currentFilePath = current.toPath()
        if (!currentFilePath.startsWith(rootPath)) return emptyList()

        val breadcrumbs =
            mutableListOf(
                BrowserBreadcrumbUiState(
                    canonicalPath = root.path,
                    label = volumeLabel,
                ),
            )
        if (currentFilePath == rootPath) return breadcrumbs
        var path = rootPath
        for (segment in rootPath.relativize(currentFilePath)) {
            path = path.resolve(segment)
            breadcrumbs +=
                BrowserBreadcrumbUiState(
                    canonicalPath = path.toFile().path,
                    label = segment.toString(),
                )
        }
        return breadcrumbs
    }

    private fun File.safeCanonicalPath(): String? = safeCanonicalFile()?.path

    private fun File.safeCanonicalFile(): File? =
        try {
            canonicalFile
        } catch (_: SecurityException) {
            null
        } catch (_: java.io.IOException) {
            null
        }

    private companion object {
        const val VOLUME_ROOT_PATH = "/"
    }
}
