package com.emma.duplicates.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.BuildConfig
import com.emma.duplicates.core.permissions.StorageAccessManager
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.preferences.PreferencesRepository
import com.emma.duplicates.ui.model.UiFileCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `state contains only real mounted volumes their storage values permission and build version`() =
        runTest(mainDispatcherRule.dispatcher) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            var permissionGranted = false
            val primary = volume("primary", "Phone", totalBytes = 1_000, freeBytes = 400, primary = true)
            val source = MutableVolumeSource(listOf(primary))
            val viewModel =
                SettingsViewModel(
                    context = context,
                    storageAccessManager = StorageAccessManager(context.packageName) { permissionGranted },
                    storageVolumeSource = source,
                    preferencesRepository = repository("state.preferences_pb"),
                )

            val initialLocations = viewModel.scanLocationsState.first { it.locations.isNotEmpty() }
            assertEquals(listOf("primary"), initialLocations.locations.map { it.id })
            assertEquals(1_000L, initialLocations.locations.single().totalBytes)
            assertEquals(400L, initialLocations.locations.single().freeBytes)
            assertTrue(initialLocations.locations.single().selected)
            assertFalse(viewModel.settingsState.value.storagePermissionGranted)
            assertEquals(BuildConfig.VERSION_NAME, viewModel.settingsState.value.appVersion)

            source.volumes =
                listOf(
                    primary,
                    volume("sd-1", "Memory card", totalBytes = 2_000, freeBytes = 1_500),
                )
            permissionGranted = true
            viewModel.refreshPlatformState()

            val refreshed = viewModel.scanLocationsState.first { it.locations.size == 2 }
            assertEquals(listOf("primary", "sd-1"), refreshed.locations.map { it.id })
            assertTrue(viewModel.settingsState.first { it.storagePermissionGranted }.storagePermissionGranted)
        }

    @Test
    fun `all settings mutations persist and flow back into immutable screen states`() =
        runTest(mainDispatcherRule.dispatcher) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val source =
                MutableVolumeSource(
                    listOf(
                        volume("primary", "Phone", primary = true),
                        volume("sd-1", "Memory card"),
                    ),
                )
            val repository = repository("persistence.preferences_pb")
            val viewModel =
                SettingsViewModel(
                    context,
                    StorageAccessManager(context.packageName) { true },
                    source,
                    repository,
                )

            viewModel.setScanHiddenFolders(true)
            viewModel.setIgnoreSystemFolders(false)
            viewModel.setAutoSelectDuplicateCopies(false)
            viewModel.setConfirmBeforeDelete(false)
            viewModel.setCategoryEnabled(UiFileCategory.PHOTOS, false)
            advanceUntilIdle()
            viewModel.setCategoryEnabled(UiFileCategory.VIDEOS, false)
            advanceUntilIdle()
            viewModel.setCategoryEnabled(UiFileCategory.AUDIO, false)
            advanceUntilIdle()
            viewModel.setLocationSelected("sd-1", true)
            advanceUntilIdle()
            viewModel.setLocationSelected("primary", false)
            advanceUntilIdle()

            val persisted = repository.preferences.first {
                it.scanHiddenFolders &&
                    !it.ignoreSystemFolders &&
                    !it.autoSelectDuplicateCopies &&
                    !it.confirmBeforeDelete &&
                    it.selectedVolumeIds == setOf("sd-1")
            }
            assertFalse(persisted.scanPhotos)
            assertFalse(persisted.scanVideos)
            assertFalse(persisted.scanAudio)
            assertTrue(persisted.scanDocuments)
            assertEquals(
                mapOf(
                    UiFileCategory.PHOTOS to false,
                    UiFileCategory.VIDEOS to false,
                    UiFileCategory.AUDIO to false,
                    UiFileCategory.DOCUMENTS to true,
                ),
                viewModel.typesToScanState.first { it.enabled[UiFileCategory.PHOTOS] == false }.enabled,
            )
            assertEquals(
                listOf("sd-1"),
                viewModel.scanLocationsState.first {
                    it.locations.filter { location -> location.selected }.map { location -> location.id } ==
                        listOf("sd-1")
                }.locations.filter { it.selected }.map { it.id },
            )
        }

    @Test
    fun `last mounted location and last enabled type cannot be disabled`() =
        runTest(mainDispatcherRule.dispatcher) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = repository("guards.preferences_pb")
            val viewModel =
                SettingsViewModel(
                    context,
                    StorageAccessManager(context.packageName) { true },
                    MutableVolumeSource(listOf(volume("primary", "Phone", primary = true))),
                    repository,
                )

            viewModel.setLocationSelected("primary", false)
            viewModel.setCategoryEnabled(UiFileCategory.PHOTOS, false)
            advanceUntilIdle()
            viewModel.setCategoryEnabled(UiFileCategory.VIDEOS, false)
            advanceUntilIdle()
            viewModel.setCategoryEnabled(UiFileCategory.AUDIO, false)
            advanceUntilIdle()
            viewModel.setCategoryEnabled(UiFileCategory.DOCUMENTS, false)
            advanceUntilIdle()

            val persisted = repository.preferences.first()
            assertEquals(setOf("primary"), persisted.selectedVolumeIds)
            assertTrue(persisted.scanDocuments)
            assertEquals(
                1,
                viewModel.typesToScanState.first { state -> state.enabled.values.count { it } == 1 }
                    .enabled.values.count { it },
            )
            assertEquals(
                1,
                viewModel.scanLocationsState.first { state -> state.locations.count { it.selected } == 1 }
                    .locations.count { it.selected },
            )
        }

    private fun TestScope.repository(fileName: String): PreferencesRepository =
        PreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { temporaryFolder.root.resolve(fileName) },
            ),
        )

    private fun volume(
        id: String,
        label: String,
        totalBytes: Long = 100,
        freeBytes: Long = 50,
        primary: Boolean = false,
    ) =
        AvailableStorageVolume(
            id = id,
            label = label,
            directory = temporaryFolder.root.resolve(id).apply { mkdirs() },
            mediaStoreVolumeName = if (primary) "external_primary" else id,
            isPrimary = primary,
            isReadOnly = false,
            totalBytes = totalBytes,
            freeBytes = freeBytes,
        )

    private class MutableVolumeSource(
        var volumes: List<AvailableStorageVolume>,
    ) : StorageVolumeSource {
        override fun mountedVolumes(): List<AvailableStorageVolume> = volumes
    }
}
