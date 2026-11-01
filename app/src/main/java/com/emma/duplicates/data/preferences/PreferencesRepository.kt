package com.emma.duplicates.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.duplicatesPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "duplicates_settings",
)

object PreferenceDefaults {
    const val PRIMARY_VOLUME_ID = "primary"
}

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

data class AppPreferences(
    val scanHiddenFolders: Boolean,
    val ignoreSystemFolders: Boolean,
    val autoSelectDuplicateCopies: Boolean,
    val confirmBeforeDelete: Boolean,
    val scanPhotos: Boolean,
    val scanVideos: Boolean,
    val scanAudio: Boolean,
    val scanDocuments: Boolean,
    val selectedVolumeIds: Set<String>,
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val scanSelectedItemsOnly: Boolean = false,
    val selectedScanPaths: Set<String> = emptySet(),
)

// Each persisted preference has a matching setter on this single DataStore owner.
@Suppress("TooManyFunctions")
class PreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<AppPreferences> =
        dataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }.map(::toAppPreferences)
            .distinctUntilChanged()

    val scanHiddenFolders = preferences.map { it.scanHiddenFolders }.distinctUntilChanged()
    val ignoreSystemFolders = preferences.map { it.ignoreSystemFolders }.distinctUntilChanged()
    val autoSelectDuplicateCopies =
        preferences.map { it.autoSelectDuplicateCopies }.distinctUntilChanged()
    val confirmBeforeDelete = preferences.map { it.confirmBeforeDelete }.distinctUntilChanged()
    val scanPhotos = preferences.map { it.scanPhotos }.distinctUntilChanged()
    val scanVideos = preferences.map { it.scanVideos }.distinctUntilChanged()
    val scanAudio = preferences.map { it.scanAudio }.distinctUntilChanged()
    val scanDocuments = preferences.map { it.scanDocuments }.distinctUntilChanged()
    val selectedVolumeIds = preferences.map { it.selectedVolumeIds }.distinctUntilChanged()
    val themeMode = preferences.map { it.themeMode }.distinctUntilChanged()

    suspend fun setThemeMode(mode: AppThemeMode) {
        dataStore.edit { it[Keys.themeMode] = mode.name }
    }

    suspend fun setScanHiddenFolders(enabled: Boolean) = setBoolean(Keys.scanHiddenFolders, enabled)

    suspend fun setIgnoreSystemFolders(enabled: Boolean) = setBoolean(Keys.ignoreSystemFolders, enabled)

    suspend fun setAutoSelectDuplicateCopies(enabled: Boolean) = setBoolean(Keys.autoSelectDuplicateCopies, enabled)

    suspend fun setConfirmBeforeDelete(enabled: Boolean) = setBoolean(Keys.confirmBeforeDelete, enabled)

    suspend fun setScanPhotos(enabled: Boolean) = setBoolean(Keys.scanPhotos, enabled)

    suspend fun setScanVideos(enabled: Boolean) = setBoolean(Keys.scanVideos, enabled)

    suspend fun setScanAudio(enabled: Boolean) = setBoolean(Keys.scanAudio, enabled)

    suspend fun setScanDocuments(enabled: Boolean) = setBoolean(Keys.scanDocuments, enabled)

    suspend fun setSelectedVolumeIds(volumeIds: Set<String>) {
        dataStore.edit { values ->
            values[Keys.selectedVolumeIds] = volumeIds.toSet()
        }
    }

    suspend fun setScanSelection(
        selectedItemsOnly: Boolean,
        paths: Set<String>,
        volumeIds: Set<String>,
    ) {
        dataStore.edit { values ->
            values[Keys.scanSelectedItemsOnly] = selectedItemsOnly
            values[Keys.selectedScanPaths] = paths.toSet()
            values[Keys.selectedVolumeIds] = volumeIds.toSet()
        }
    }

    private suspend fun setBoolean(
        key: Preferences.Key<Boolean>,
        value: Boolean,
    ) {
        dataStore.edit { values ->
            values[key] = value
        }
    }

    private fun toAppPreferences(values: Preferences): AppPreferences =
        AppPreferences(
            scanHiddenFolders = values[Keys.scanHiddenFolders] ?: false,
            ignoreSystemFolders = values[Keys.ignoreSystemFolders] ?: true,
            autoSelectDuplicateCopies = values[Keys.autoSelectDuplicateCopies] ?: true,
            confirmBeforeDelete = values[Keys.confirmBeforeDelete] ?: true,
            themeMode = AppThemeMode.entries.firstOrNull { it.name == values[Keys.themeMode] } ?: AppThemeMode.DARK,
            scanSelectedItemsOnly = values[Keys.scanSelectedItemsOnly] ?: false,
            selectedScanPaths = values[Keys.selectedScanPaths]?.toSet().orEmpty(),
            scanPhotos = values[Keys.scanPhotos] ?: true,
            scanVideos = values[Keys.scanVideos] ?: true,
            scanAudio = values[Keys.scanAudio] ?: true,
            scanDocuments = values[Keys.scanDocuments] ?: true,
            selectedVolumeIds =
                values[Keys.selectedVolumeIds]?.toSet()
                    ?: setOf(PreferenceDefaults.PRIMARY_VOLUME_ID),
        )

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val scanSelectedItemsOnly = booleanPreferencesKey("scan_selected_items_only")
        val selectedScanPaths = stringSetPreferencesKey("selected_scan_paths")
        val scanHiddenFolders = booleanPreferencesKey("scan_hidden_folders")
        val ignoreSystemFolders = booleanPreferencesKey("ignore_system_folders")
        val autoSelectDuplicateCopies = booleanPreferencesKey("auto_select_duplicate_copies")
        val confirmBeforeDelete = booleanPreferencesKey("confirm_before_delete")
        val scanPhotos = booleanPreferencesKey("scan_photos")
        val scanVideos = booleanPreferencesKey("scan_videos")
        val scanAudio = booleanPreferencesKey("scan_audio")
        val scanDocuments = booleanPreferencesKey("scan_documents")
        val selectedVolumeIds = stringSetPreferencesKey("selected_volume_ids")
    }
}
