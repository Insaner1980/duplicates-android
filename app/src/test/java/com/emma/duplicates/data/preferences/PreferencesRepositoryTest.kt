package com.emma.duplicates.data.preferences

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
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
class PreferencesRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun defaultsMatchTheSafeFirstLaunchConfiguration() =
        runTest {
            val repository = repository("defaults.preferences_pb")

            val preferences = repository.preferences.first()

            assertFalse(preferences.scanHiddenFolders)
            assertTrue(preferences.ignoreSystemFolders)
            assertTrue(preferences.autoSelectDuplicateCopies)
            assertTrue(preferences.confirmBeforeDelete)
            assertTrue(preferences.scanPhotos)
            assertTrue(preferences.scanVideos)
            assertTrue(preferences.scanAudio)
            assertTrue(preferences.scanDocuments)
            assertEquals(setOf(PreferenceDefaults.PRIMARY_VOLUME_ID), preferences.selectedVolumeIds)
        }

    @Test
    fun settingValuesPersistsTheCompleteConfiguration() =
        runTest {
            val repository = repository("settings.preferences_pb")

            repository.setScanHiddenFolders(true)
            repository.setIgnoreSystemFolders(false)
            repository.setAutoSelectDuplicateCopies(false)
            repository.setConfirmBeforeDelete(false)
            repository.setScanPhotos(false)
            repository.setScanVideos(false)
            repository.setScanAudio(false)
            repository.setScanDocuments(false)
            repository.setSelectedVolumeIds(setOf("primary", "removable:123"))

            val persisted = repository.preferences.first()
            assertTrue(persisted.scanHiddenFolders)
            assertFalse(persisted.ignoreSystemFolders)
            assertFalse(persisted.autoSelectDuplicateCopies)
            assertFalse(persisted.confirmBeforeDelete)
            assertFalse(persisted.scanPhotos)
            assertFalse(persisted.scanVideos)
            assertFalse(persisted.scanAudio)
            assertFalse(persisted.scanDocuments)
            assertEquals(setOf("primary", "removable:123"), persisted.selectedVolumeIds)
        }

    @Test
    fun individualFlowsReflectPersistedUpdates() =
        runTest {
            val repository = repository("flows.preferences_pb")

            repository.setScanHiddenFolders(true)
            repository.setSelectedVolumeIds(setOf("removable:123"))

            assertTrue(repository.scanHiddenFolders.first())
            assertEquals(setOf("removable:123"), repository.selectedVolumeIds.first())
        }

    private fun kotlinx.coroutines.test.TestScope.repository(fileName: String): PreferencesRepository {
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { temporaryFolder.root.resolve(fileName) },
            )
        return PreferencesRepository(dataStore)
    }
}
