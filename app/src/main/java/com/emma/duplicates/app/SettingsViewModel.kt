package com.emma.duplicates.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.BuildConfig
import com.emma.duplicates.R
import com.emma.duplicates.core.permissions.StorageAccessManager
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.preferences.AppPreferences
import com.emma.duplicates.data.preferences.PreferenceDefaults
import com.emma.duplicates.data.preferences.PreferencesRepository
import com.emma.duplicates.ui.model.UiFileCategory
import com.emma.duplicates.ui.settings.ScanLocationUiState
import com.emma.duplicates.ui.settings.ScanLocationsUiState
import com.emma.duplicates.ui.settings.SettingsUiState
import com.emma.duplicates.ui.settings.TypesToScanUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsViewModel(
    context: Context,
    private val storageAccessManager: StorageAccessManager,
    private val storageVolumeSource: StorageVolumeSource,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {
    private val resources = context.applicationContext.resources
    private val permissionGranted = MutableStateFlow(storageAccessManager.hasAccess())
    private val mountedVolumes = MutableStateFlow(storageVolumeSource.mountedVolumes())
    private val selectionMutex = Mutex()
    private val preferences =
        preferencesRepository.preferences.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            DEFAULT_PREFERENCES,
        )

    val settingsState: StateFlow<SettingsUiState> =
        combine(preferences, mountedVolumes, permissionGranted, ::settingsState)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                settingsState(DEFAULT_PREFERENCES, mountedVolumes.value, permissionGranted.value),
            )

    val scanLocationsState: StateFlow<ScanLocationsUiState> =
        combine(preferences, mountedVolumes, ::scanLocationsState)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                scanLocationsState(DEFAULT_PREFERENCES, mountedVolumes.value),
            )

    val typesToScanState: StateFlow<TypesToScanUiState> =
        preferences
            .map(::typesToScanState)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                typesToScanState(DEFAULT_PREFERENCES),
            )

    fun refreshPlatformState() {
        permissionGranted.value = storageAccessManager.hasAccess()
        mountedVolumes.value = storageVolumeSource.mountedVolumes()
    }

    fun setScanHiddenFolders(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setScanHiddenFolders(enabled) }
    }

    fun setIgnoreSystemFolders(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setIgnoreSystemFolders(enabled) }
    }

    fun setAutoSelectDuplicateCopies(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setAutoSelectDuplicateCopies(enabled) }
    }

    fun setConfirmBeforeDelete(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setConfirmBeforeDelete(enabled) }
    }

    fun setLocationSelected(
        volumeId: String,
        selected: Boolean,
    ) {
        viewModelScope.launch {
            selectionMutex.withLock {
                val mountedIds = mountedVolumes.value.mapTo(linkedSetOf(), AvailableStorageVolume::id)
                if (volumeId !in mountedIds) return@withLock
                val current = preferencesRepository.preferences.first().selectedVolumeIds
                val updated = if (selected) current + volumeId else current - volumeId
                if (!selected && updated.none(mountedIds::contains)) return@withLock
                if (updated != current) preferencesRepository.setSelectedVolumeIds(updated)
            }
        }
    }

    fun setCategoryEnabled(
        category: UiFileCategory,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            selectionMutex.withLock {
                val current = preferencesRepository.preferences.first()
                if (!enabled && current.isEnabled(category) && current.enabledCategoryCount() == 1) {
                    return@withLock
                }
                when (category) {
                    UiFileCategory.PHOTOS -> preferencesRepository.setScanPhotos(enabled)
                    UiFileCategory.VIDEOS -> preferencesRepository.setScanVideos(enabled)
                    UiFileCategory.AUDIO -> preferencesRepository.setScanAudio(enabled)
                    UiFileCategory.DOCUMENTS -> preferencesRepository.setScanDocuments(enabled)
                }
            }
        }
    }

    private fun settingsState(
        preferences: AppPreferences,
        volumes: List<AvailableStorageVolume>,
        granted: Boolean,
    ) =
        SettingsUiState(
            scanLocationsSummary =
                volumes
                    .filter { it.id in preferences.selectedVolumeIds }
                    .joinToString(
                        separator = resources.getString(R.string.summary_separator),
                        transform = AvailableStorageVolume::label,
                    )
                    .ifBlank { resources.getString(R.string.no_locations_selected) },
            scanHiddenFolders = preferences.scanHiddenFolders,
            ignoreSystemFolders = preferences.ignoreSystemFolders,
            autoSelectDuplicateCopies = preferences.autoSelectDuplicateCopies,
            confirmBeforeDelete = preferences.confirmBeforeDelete,
            fileTypesSummary =
                UiFileCategory.entries
                    .filter { preferences.isEnabled(it) }
                    .joinToString(resources.getString(R.string.summary_separator)) {
                        resources.getString(it.labelResource())
                    }
                    .ifBlank { resources.getString(R.string.no_file_types_selected) },
            storagePermissionGranted = granted,
            appVersion = BuildConfig.VERSION_NAME,
        )

    private fun scanLocationsState(
        preferences: AppPreferences,
        volumes: List<AvailableStorageVolume>,
    ) =
        ScanLocationsUiState(
            locations =
                volumes.map { volume ->
                    ScanLocationUiState(
                        id = volume.id,
                        displayName = volume.label,
                        totalBytes = volume.totalBytes,
                        freeBytes = volume.freeBytes,
                        selected = volume.id in preferences.selectedVolumeIds,
                        mounted = true,
                    )
                },
        )

    private fun typesToScanState(preferences: AppPreferences) =
        TypesToScanUiState(
            enabled = UiFileCategory.entries.associateWith { preferences.isEnabled(it) },
        )

    private fun AppPreferences.isEnabled(category: UiFileCategory): Boolean =
        when (category) {
            UiFileCategory.PHOTOS -> scanPhotos
            UiFileCategory.VIDEOS -> scanVideos
            UiFileCategory.AUDIO -> scanAudio
            UiFileCategory.DOCUMENTS -> scanDocuments
        }

    private fun AppPreferences.enabledCategoryCount(): Int =
        UiFileCategory.entries.count { isEnabled(it) }

    private fun UiFileCategory.labelResource(): Int =
        when (this) {
            UiFileCategory.PHOTOS -> R.string.photos
            UiFileCategory.VIDEOS -> R.string.videos
            UiFileCategory.AUDIO -> R.string.audio
            UiFileCategory.DOCUMENTS -> R.string.documents
        }

    private companion object {
        val DEFAULT_PREFERENCES =
            AppPreferences(
                scanHiddenFolders = false,
                ignoreSystemFolders = true,
                autoSelectDuplicateCopies = true,
                confirmBeforeDelete = true,
                scanPhotos = true,
                scanVideos = true,
                scanAudio = true,
                scanDocuments = true,
                selectedVolumeIds = setOf(PreferenceDefaults.PRIMARY_VOLUME_ID),
            )
    }
}
