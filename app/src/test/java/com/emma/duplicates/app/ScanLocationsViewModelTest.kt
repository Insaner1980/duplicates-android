package com.emma.duplicates.app

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.emma.duplicates.R
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.StorageBrowser
import com.emma.duplicates.data.preferences.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScanLocationsViewModelTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `folder and file selection persists only on Done and restores on reopening`() =
        runTest(mainDispatcherRule.dispatcher) {
            val root = temporaryFolder.newFolder("storage")
            val folder = root.resolve("Photos").apply { mkdirs() }
            val file = root.resolve("note.txt").apply { writeText("content") }
            val preferences =
                PreferencesRepository(
                    PreferenceDataStoreFactory.create(
                        scope = backgroundScope,
                        produceFile = { temporaryFolder.root.resolve("selection.preferences_pb") },
                    ),
                )
            val browser = browser(root)
            val model = ScanLocationsViewModel(browser, preferences)
            model.state.first { !it.loading }
            model.openVolume("primary")
            model.state.first { !it.loading && it.location != null }
            model.setPathSelected(folder.canonicalPath, true)
            model.setPathSelected(file.canonicalPath, true)
            assertFalse(preferences.preferences.first().scanSelectedItemsOnly)
            model.save()
            model.completed.first { it }

            val restored = ScanLocationsViewModel(browser, preferences).state.first { !it.loading }
            assertTrue(restored.selectedItemsOnly)
            assertEquals(setOf(folder.canonicalPath, file.canonicalPath), restored.selectedPaths)
            assertTrue(restored.canSave)
        }

    @Test
    fun `selecting a parent removes redundant child selections and missing items cannot be saved`() =
        runTest(mainDispatcherRule.dispatcher) {
            val root = temporaryFolder.newFolder("nested")
            val folder = root.resolve("Photos").apply { mkdirs() }
            val file = folder.resolve("photo.jpg").apply { writeText("content") }
            val preferences =
                PreferencesRepository(
                    PreferenceDataStoreFactory.create(
                        scope = backgroundScope,
                        produceFile = { temporaryFolder.root.resolve("nested.preferences_pb") },
                    ),
                )
            val model = ScanLocationsViewModel(browser(root), preferences)
            model.state.first { !it.loading }
            model.openVolume("primary")
            model.state.first { !it.loading && it.location != null }
            model.openDirectory(folder.path)
            model.state.first { !it.loading && it.location?.currentPath == folder.canonicalPath }
            model.setPathSelected(file.canonicalPath, true)
            model.setPathSelected(folder.canonicalPath, true)
            assertEquals(setOf(folder.canonicalPath), model.state.value.selectedPaths)
            assertTrue(model.navigateBack())
            model.state.first { !it.loading && it.location?.currentPath == root.canonicalPath }
            model.setPathSelected(folder.canonicalPath, false)
            assertFalse(model.state.value.canSave)
            model.setPathSelected(folder.canonicalPath, true)
            assertTrue(file.delete())
            assertTrue(folder.delete())
            model.save()
            val failure = model.state.first { it.messageRes != null }
            assertEquals(R.string.scan_selection_unavailable, failure.messageRes)
            assertFalse(model.completed.value)
            assertFalse(preferences.preferences.first().scanSelectedItemsOnly)
        }

    @Test
    fun `selected roots reject outside private and restricted descendants`() =
        runTest {
            val root = temporaryFolder.newFolder("bounded")
            val private = root.resolve("Private").apply { mkdirs() }
            val restricted =
                root
                    .resolve("Android/data/package")
                    .apply {
                        mkdirs()
                    }.resolve("private.txt")
                    .apply { writeText("private") }
            val external = temporaryFolder.newFile("outside.txt")
            val browser = browser(root, setOf(private.path))
            assertNull(browser.scanRoots(setOf(restricted.path)))
            assertNull(browser.scanRoots(setOf(private.path)))
            assertNull(browser.scanRoots(setOf(external.path)))
            assertNull(browser.open("primary", restricted.parent))
        }

    private fun browser(
        root: File,
        privatePaths: Set<String> = emptySet(),
    ) = StorageBrowser(
        StorageVolumeSource {
            listOf(
                AvailableStorageVolume("primary", "Internal storage", root, null, true, false, 100, 50),
            )
        },
        privatePaths,
    )
}
