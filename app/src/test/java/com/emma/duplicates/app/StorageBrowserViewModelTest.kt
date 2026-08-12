package com.emma.duplicates.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.ExclusionRepository
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.data.StorageBrowser
import com.emma.duplicates.ui.exclusions.BrowserMessage
import com.emma.duplicates.ui.exclusions.ExclusionKind
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class StorageBrowserViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: DuplicatesDatabase
    private lateinit var repository: ExclusionRepository
    private lateinit var browser: StorageBrowser
    private lateinit var root: File

    @Before
    fun createDependencies() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        root = temporaryFolder.newFolder("storage")
        val source = StorageVolumeSource { listOf(volume(root)) }
        repository = ExclusionRepository(ScanStore(database), source, currentTimeMillis = { 100L })
        browser = StorageBrowser(source, applicationDirectories = emptySet())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `browser starts at real volumes then uses canonical paths folder-first ordering and bounded back navigation`() =
        runTest(mainDispatcherRule.dispatcher) {
            root.resolve("z-folder").mkdirs()
            val nested = root.resolve("a-folder").apply { mkdirs() }
            root.resolve("m-file.txt").writeText("content")
            val viewModel = StorageBrowserViewModel(browser, repository, ExclusionKind.FILE)

            assertEquals(listOf("Phone"), viewModel.state.value.entries.map { it.displayName })
            assertFalse(viewModel.state.value.canSelectCurrentFolder)

            viewModel.openDirectory(root.path)
            val rootState = viewModel.state.first { it.currentPath == root.canonicalPath }
            assertEquals(listOf("a-folder", "z-folder", "m-file.txt"), rootState.entries.map { it.displayName })
            assertEquals(root.canonicalPath, rootState.breadcrumbs.single().canonicalPath)

            viewModel.openDirectory(nested.path)
            val nestedState = viewModel.state.first { it.currentPath == nested.canonicalPath }
            assertEquals(listOf("Phone", "a-folder"), nestedState.breadcrumbs.map { it.label })
            assertTrue(viewModel.navigateBack())
            viewModel.state.first { it.currentPath == root.canonicalPath }
            assertTrue(viewModel.navigateBack())
            assertFalse(viewModel.state.value.canSelectCurrentFolder)
            assertFalse(viewModel.navigateBack())
        }

    @Test
    fun `folder and file selections add their canonical paths`() =
        runTest(mainDispatcherRule.dispatcher) {
            val folder = root.resolve("Folder").apply { mkdirs() }
            val file = root.resolve("note.txt").apply { writeText("note") }
            val folderViewModel = StorageBrowserViewModel(browser, repository, ExclusionKind.FOLDER)
            folderViewModel.openDirectory(folder.path)
            folderViewModel.state.first { it.currentPath == folder.canonicalPath }
            folderViewModel.selectCurrentFolder()
            assertTrue(folderViewModel.selectionCompleted.first { it })
            assertEquals(folder.canonicalPath, repository.exclusions.first { it.size == 1 }.single().canonicalPath)

            val fileViewModel = StorageBrowserViewModel(browser, repository, ExclusionKind.FILE)
            fileViewModel.openDirectory(root.path)
            fileViewModel.state.first { it.currentPath == root.canonicalPath }
            fileViewModel.selectFile(file.path)
            assertTrue(fileViewModel.selectionCompleted.first { it })
            assertEquals(
                setOf(folder.canonicalPath, file.canonicalPath),
                repository.exclusions.first { it.size == 2 }.map { it.canonicalPath }.toSet(),
            )
        }

    @Test
    fun `repository duplicate parent and inaccessible outcomes become browser messages`() =
        runTest(mainDispatcherRule.dispatcher) {
            val folder = root.resolve("Parent").apply { mkdirs() }
            val file = folder.resolve("child.txt").apply { writeText("child") }
            val first = StorageBrowserViewModel(browser, repository, ExclusionKind.FILE)
            first.openDirectory(folder.path)
            first.state.first { it.currentPath == folder.canonicalPath }
            first.selectFile(file.path)
            first.selectionCompleted.first { it }

            val duplicate = StorageBrowserViewModel(browser, repository, ExclusionKind.FILE)
            duplicate.openDirectory(folder.path)
            duplicate.state.first { it.currentPath == folder.canonicalPath }
            duplicate.selectFile(file.path)
            assertEquals(BrowserMessage.ALREADY_EXCLUDED, duplicate.state.first { it.message != null }.message)

            repository.remove(repository.exclusions.first { it.isNotEmpty() }.single().id)
            val folderViewModel = StorageBrowserViewModel(browser, repository, ExclusionKind.FOLDER)
            folderViewModel.openDirectory(folder.path)
            folderViewModel.state.first { it.currentPath == folder.canonicalPath }
            folderViewModel.selectCurrentFolder()
            folderViewModel.selectionCompleted.first { it }

            val child = StorageBrowserViewModel(browser, repository, ExclusionKind.FILE)
            child.openDirectory(folder.path)
            child.state.first { it.currentPath == folder.canonicalPath }
            child.selectFile(file.path)
            assertEquals(BrowserMessage.PARENT_ALREADY_EXCLUDED, child.state.first { it.message != null }.message)

            repository.remove(repository.exclusions.first { it.isNotEmpty() }.single().id)
            val inaccessible = StorageBrowserViewModel(browser, repository, ExclusionKind.FILE)
            inaccessible.openDirectory(folder.path)
            inaccessible.state.first { it.currentPath == folder.canonicalPath }
            assertTrue(file.delete())
            inaccessible.selectFile(file.path)
            assertEquals(BrowserMessage.INACCESSIBLE, inaccessible.state.first { it.message != null }.message)
        }

    private fun volume(directory: File) =
        AvailableStorageVolume(
            id = "primary",
            label = "Phone",
            directory = directory,
            mediaStoreVolumeName = "external_primary",
            isPrimary = true,
            isReadOnly = false,
            totalBytes = 100,
            freeBytes = 50,
        )
}
