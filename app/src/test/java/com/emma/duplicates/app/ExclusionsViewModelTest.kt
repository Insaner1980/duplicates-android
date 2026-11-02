package com.emma.duplicates.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.model.ExclusionType
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.ExclusionRepository
import com.emma.duplicates.data.ScanStore
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
class ExclusionsViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: DuplicatesDatabase
    private lateinit var repository: ExclusionRepository

    @Before
    fun createRepository() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val root = temporaryFolder.newFolder("storage")
        repository =
            ExclusionRepository(
                ScanStore(database),
                StorageVolumeSource { listOf(volume(root)) },
                currentTimeMillis = { 100L },
            )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `folder and file exclusions appear in separate sections and removal updates state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val root = temporaryFolder.root.resolve("storage")
            val folder = root.resolve("Pictures").apply { mkdirs() }
            val file = root.resolve("note.txt").apply { writeText("note") }
            assertEquals(
                com.emma.duplicates.data.AddExclusionResult.ADDED,
                repository.add(folder.path, ExclusionType.FOLDER),
            )
            assertEquals(
                com.emma.duplicates.data.AddExclusionResult.ADDED,
                repository.add(file.path, ExclusionType.FILE),
            )
            val viewModel = ExclusionsViewModel(repository)

            val populated = viewModel.state.first { it.folders.size == 1 && it.files.size == 1 }
            assertEquals(folder.canonicalPath, populated.folders.single().canonicalPath)
            assertEquals(file.canonicalPath, populated.files.single().canonicalPath)

            viewModel.showAddSheet()
            assertTrue(viewModel.state.first { it.addSheetVisible }.addSheetVisible)
            viewModel.dismissAddSheet()
            assertFalse(viewModel.state.first { !it.addSheetVisible }.addSheetVisible)

            viewModel.remove(populated.files.single().id)
            val removed = viewModel.state.first { it.files.isEmpty() }
            assertEquals(listOf(folder.canonicalPath), removed.folders.map { it.canonicalPath })
        }

    @Test
    fun `failed parent insertion preserves covered exclusions`() =
        runTest(mainDispatcherRule.dispatcher) {
            val folder = temporaryFolder.root.resolve("storage/Pictures").apply { mkdirs() }
            val child = folder.resolve("a.txt").apply { writeText("a") }
            repository.add(child.path, ExclusionType.FILE)
            val savedExclusions = database.exclusionDao().getAll()
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER fail_parent_insert BEFORE INSERT ON exclusions
                WHEN NEW.type = 'folder'
                BEGIN
                    SELECT RAISE(ABORT, 'injected exclusion insert failure');
                END
                """.trimIndent(),
            )

            val result = runCatching { repository.add(folder.path, ExclusionType.FOLDER) }

            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("injected exclusion insert failure"),
            )
            assertEquals(savedExclusions, database.exclusionDao().getAll())
        }

    @Test
    fun `failed child removal rolls back the entire replacement`() =
        runTest(mainDispatcherRule.dispatcher) {
            val folder = temporaryFolder.root.resolve("storage/Pictures").apply { mkdirs() }
            listOf("a.txt", "b.txt").forEach { name ->
                val child = folder.resolve(name).apply { writeText(name) }
                repository.add(child.path, ExclusionType.FILE)
            }
            val savedExclusions = database.exclusionDao().getAll()
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER fail_child_removal BEFORE DELETE ON exclusions
                WHEN OLD.displayName = 'b.txt'
                BEGIN
                    SELECT RAISE(ABORT, 'injected exclusion removal failure');
                END
                """.trimIndent(),
            )

            val result = runCatching { repository.add(folder.path, ExclusionType.FOLDER) }

            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("injected exclusion removal failure"),
            )
            assertEquals(savedExclusions, database.exclusionDao().getAll())
        }

    @Test
    fun `adding a parent replaces covered exclusions and preserves siblings`() =
        runTest(mainDispatcherRule.dispatcher) {
            val root = temporaryFolder.root.resolve("storage")
            val folder = root.resolve("Pictures").apply { mkdirs() }
            val child = folder.resolve("a.txt").apply { writeText("a") }
            val sibling = root.resolve("Pictures-other").apply { mkdirs() }
            repository.add(child.path, ExclusionType.FILE)
            repository.add(sibling.path, ExclusionType.FOLDER)

            assertEquals(
                com.emma.duplicates.data.AddExclusionResult.ADDED,
                repository.add(folder.path, ExclusionType.FOLDER),
            )

            assertEquals(
                setOf(folder.canonicalPath, sibling.canonicalPath),
                database
                    .exclusionDao()
                    .getAll()
                    .map { it.canonicalPath }
                    .toSet(),
            )
        }

    private fun volume(root: java.io.File) =
        AvailableStorageVolume(
            id = "primary",
            label = "Phone",
            directory = root,
            mediaStoreVolumeName = "external_primary",
            isPrimary = true,
            isReadOnly = false,
            totalBytes = 100,
            freeBytes = 50,
        )
}
