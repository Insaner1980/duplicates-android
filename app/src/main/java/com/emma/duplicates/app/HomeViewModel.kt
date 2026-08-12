package com.emma.duplicates.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.R
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanSessionWithGroups
import com.emma.duplicates.core.permissions.StorageAccessManager
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.data.preferences.AppPreferences
import com.emma.duplicates.data.preferences.PreferencesRepository
import com.emma.duplicates.ui.home.CompletedHomeUiState
import com.emma.duplicates.ui.home.HomeCategorySummaryUiState
import com.emma.duplicates.ui.home.HomeUiState
import com.emma.duplicates.ui.model.UiFileCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(
    context: Context,
    private val storageAccessManager: StorageAccessManager,
    private val volumeSource: StorageVolumeSource,
    preferencesRepository: PreferencesRepository,
    scanStore: ScanStore,
) : ViewModel() {
    private val resources = context.applicationContext.resources
    private val permissionGranted = MutableStateFlow(storageAccessManager.hasAccess())
    private val volumes = MutableStateFlow(volumeSource.mountedVolumes())

    val state: StateFlow<HomeUiState> =
        combine(
            permissionGranted,
            volumes,
            preferencesRepository.preferences,
            scanStore.runningSession,
            scanStore.activeResults,
        ) { granted, availableVolumes, preferences, running, activeResults ->
            when {
                !granted -> HomeUiState.PermissionRequired
                running != null ->
                    HomeUiState.ActiveScan(
                        phase = running.phase.displayName(),
                        progress = running.progressFraction(),
                        progressLabel = running.progressPercentLabel(),
                        filesIndexed = running.scannedFileCount,
                    )
                activeResults == null ->
                    HomeUiState.Ready(
                        locationSummary = locationSummary(availableVolumes, preferences),
                        typeSummary = typeSummary(preferences),
                        scanEnabled =
                            availableVolumes.any { it.id in preferences.selectedVolumeIds } &&
                                preferences.hasEnabledCategory(),
                    )
                activeResults.session.scannedFileCount == 0 ->
                    HomeUiState.NoMatchingFiles(
                        lastScanEpochMillis =
                            activeResults.session.completedAt ?: activeResults.session.startedAt,
                        skippedFileCount = activeResults.session.skippedFileCount,
                    )
                activeResults.session.duplicateGroupCount == 0 ->
                    HomeUiState.NoDuplicates(
                        lastScanEpochMillis =
                            activeResults.session.completedAt ?: activeResults.session.startedAt,
                        skippedFileCount = activeResults.session.skippedFileCount,
                    )
                else -> completedState(activeResults, availableVolumes, preferences)
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            if (permissionGranted.value) {
                HomeUiState.Ready(
                    locationSummary = resources.getString(R.string.internal_storage),
                    typeSummary = resources.getString(R.string.file_types_summary),
                    scanEnabled = false,
                )
            } else {
                HomeUiState.PermissionRequired
            },
        )

    fun refreshPlatformState() {
        permissionGranted.value = storageAccessManager.hasAccess()
        volumes.value = volumeSource.mountedVolumes()
    }

    private fun completedState(
        results: ScanSessionWithGroups,
        availableVolumes: List<AvailableStorageVolume>,
        preferences: AppPreferences,
    ): HomeUiState.Completed {
        val selectedVolumes = availableVolumes.filter { it.id in preferences.selectedVolumeIds }
        val groupsByCategory = results.groups.groupBy { it.group.category.toUiCategory() }
        val categories =
            UiFileCategory.entries.map { category ->
                val groups = groupsByCategory[category].orEmpty()
                HomeCategorySummaryUiState(
                    category = category,
                    duplicateCount = groups.sumOf { it.group.copyCount },
                    reclaimableBytes = groups.sumOf { it.group.reclaimableBytes },
                )
            }
        return HomeUiState.Completed(
            CompletedHomeUiState(
                reclaimableBytes = results.session.reclaimableBytes,
                duplicateFileCount = results.session.duplicateFileCount,
                duplicateGroupCount = results.session.duplicateGroupCount,
                totalStorageBytes = selectedVolumes.sumOf { it.totalBytes },
                freeStorageBytes = selectedVolumes.sumOf { it.freeBytes },
                lastScanEpochMillis = results.session.completedAt ?: results.session.startedAt,
                categories = categories,
                skippedFileCount = results.session.skippedFileCount,
            ),
        )
    }

    private fun locationSummary(
        availableVolumes: List<AvailableStorageVolume>,
        preferences: AppPreferences,
    ): String =
        availableVolumes
            .filter { it.id in preferences.selectedVolumeIds }
            .joinToString(resources.getString(R.string.summary_separator)) { it.label }
            .ifBlank { resources.getString(R.string.no_locations_selected) }

    private fun typeSummary(preferences: AppPreferences): String =
        buildList {
            if (preferences.scanPhotos) add(resources.getString(R.string.photos))
            if (preferences.scanVideos) add(resources.getString(R.string.videos))
            if (preferences.scanAudio) add(resources.getString(R.string.audio))
            if (preferences.scanDocuments) add(resources.getString(R.string.documents))
        }.joinToString(resources.getString(R.string.summary_separator))
            .ifBlank { resources.getString(R.string.no_file_types_selected) }

    private fun AppPreferences.hasEnabledCategory(): Boolean =
        scanPhotos || scanVideos || scanAudio || scanDocuments

    private fun String?.displayName(): String =
        when (this) {
            ScanPhases.COMPARING_CANDIDATES -> resources.getString(R.string.comparing_candidates)
            ScanPhases.VERIFYING_DUPLICATES -> resources.getString(R.string.verifying_duplicates)
            else -> resources.getString(R.string.finding_files)
        }

    private fun com.emma.duplicates.core.database.ScanSessionEntity.progressFraction(): Float? {
        val total = progressTotalWork ?: return null
        if (total <= 0L) return null
        return (progressCompletedWork.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private fun com.emma.duplicates.core.database.ScanSessionEntity.progressPercentLabel(): String? =
        progressFraction()?.let { progress ->
            resources.getString(R.string.percent_value, (progress * 100).toInt())
        }
}
