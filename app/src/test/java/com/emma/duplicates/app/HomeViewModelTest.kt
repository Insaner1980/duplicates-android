package com.emma.duplicates.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.core.database.DuplicateGroupEntity
import com.emma.duplicates.core.database.DuplicateMemberEntity
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.IndexedFileEntity
import com.emma.duplicates.core.database.ScanCompletionMetrics
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanProgressUpdate
import com.emma.duplicates.core.permissions.StorageAccessManager
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.data.preferences.PreferencesRepository
import com.emma.duplicates.ui.home.HomeUiState
import com.emma.duplicates.ui.model.UiFileCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: DuplicatesDatabase
    private lateinit var scanStore: ScanStore

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scanStore = ScanStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `initial ready state never enables scanning before available scope is known`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(access = { true }, volumes = { emptyList() }, preferences("initial.preferences_pb"))

            val initial = viewModel.state.value as HomeUiState.Ready

            assertFalse(initial.scanEnabled)
        }

    @Test
    fun `permission refresh transitions to ready state with real scope summaries`() =
        runTest(mainDispatcherRule.dispatcher) {
            var access = false
            var volumes = emptyList<AvailableStorageVolume>()
            val viewModel = viewModel({ access }, { volumes }, preferences("permission.preferences_pb"))
            assertEquals(HomeUiState.PermissionRequired, viewModel.state.value)

            access = true
            volumes = listOf(primaryVolume())
            viewModel.refreshPlatformState()
            val ready = viewModel.state.first { it is HomeUiState.Ready && it.scanEnabled } as HomeUiState.Ready

            assertEquals("Internal storage", ready.locationSummary)
            assertEquals("Photos / Videos / Audio / Documents", ready.typeSummary)
        }

    @Test
    fun `running session maps truthful active scan progress`() =
        runTest(mainDispatcherRule.dispatcher) {
            scanStore.startSession("running", 10L)
            scanStore.updateProgress(
                "running",
                ScanProgressUpdate(
                    phase = ScanPhases.COMPARING_CANDIDATES,
                    completedWork = 25L,
                    totalWork = 100L,
                    scannedFileCount = 7,
                    scannedByteCount = 1_000L,
                    skippedFileCount = 0,
                    errorCount = 0,
                ),
            )
            val viewModel = viewModel({ true }, { listOf(primaryVolume()) }, preferences("running.preferences_pb"))

            val active = viewModel.state.first { it is HomeUiState.ActiveScan } as HomeUiState.ActiveScan

            assertEquals("Comparing candidates", active.phase)
            assertEquals(0.25f, active.progress)
            assertEquals("25%", active.progressLabel)
            assertEquals(7, active.filesIndexed)
        }

    @Test
    fun `completed results map storage category and duplicate summaries`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedCompletedResults(withDuplicates = true)
            val viewModel = viewModel({ true }, { listOf(primaryVolume()) }, preferences("completed.preferences_pb"))

            val completed = viewModel.state.first { it is HomeUiState.Completed } as HomeUiState.Completed

            assertEquals(200L, completed.summary.reclaimableBytes)
            assertEquals(4, completed.summary.duplicateFileCount)
            assertEquals(2, completed.summary.duplicateGroupCount)
            assertEquals(1_000L, completed.summary.totalStorageBytes)
            assertEquals(400L, completed.summary.freeStorageBytes)
            assertEquals(
                listOf(
                    UiFileCategory.PHOTOS,
                    UiFileCategory.VIDEOS,
                    UiFileCategory.AUDIO,
                    UiFileCategory.DOCUMENTS,
                ),
                completed.summary.categories.map { it.category },
            )
            assertEquals(listOf(2, 0, 0, 2), completed.summary.categories.map { it.duplicateCount })
            assertEquals(listOf(100L, 0L, 0L, 100L), completed.summary.categories.map { it.reclaimableBytes })
        }

    @Test
    fun `completed scan without groups maps no duplicates and survives later failed staging`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedCompletedResults(withDuplicates = false)
            scanStore.startSession("failed", 30L)
            scanStore.failSession("failed", 40L, errorCount = 1)
            val viewModel = viewModel({ true }, { listOf(primaryVolume()) }, preferences("empty.preferences_pb"))

            val empty = viewModel.state.first { it is HomeUiState.NoDuplicates } as HomeUiState.NoDuplicates

            assertEquals(20L, empty.lastScanEpochMillis)
            assertEquals(1, empty.skippedFileCount)
            assertNull(scanStore.runningSession.first())
        }

    @Test
    fun `completed scan without matching files maps actionable empty state`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedCompletedResults(withDuplicates = false, withMatchingFiles = false)
            val viewModel =
                viewModel(
                    { true },
                    { listOf(primaryVolume()) },
                    preferences("no-matching-files.preferences_pb"),
                )

            val empty =
                viewModel.state.first { it is HomeUiState.NoMatchingFiles } as
                    HomeUiState.NoMatchingFiles

            assertEquals(20L, empty.lastScanEpochMillis)
            assertEquals(1, empty.skippedFileCount)
        }

    private fun viewModel(
        access: () -> Boolean,
        volumes: () -> List<AvailableStorageVolume>,
        preferencesRepository: PreferencesRepository,
    ) = HomeViewModel(
        context = context,
        storageAccessManager = StorageAccessManager(context.packageName, access),
        volumeSource = StorageVolumeSource(volumes),
        preferencesRepository = preferencesRepository,
        scanStore = scanStore,
    )

    private suspend fun seedCompletedResults(
        withDuplicates: Boolean,
        withMatchingFiles: Boolean = true,
    ) {
        scanStore.startSession("completed", 10L)
        val files =
            when {
                withDuplicates ->
                    listOf(
                        file("photo-a", "PHOTOS"),
                        file("photo-b", "PHOTOS"),
                        file("doc-a", "DOCUMENTS"),
                        file("doc-b", "DOCUMENTS"),
                    )
                withMatchingFiles -> listOf(file("unique", "DOCUMENTS"))
                else -> emptyList()
            }
        val groups = if (withDuplicates) {
            listOf(group("photos", "PHOTOS"), group("documents", "DOCUMENTS"))
        } else {
            emptyList()
        }
        val members = if (withDuplicates) {
            listOf(
                member("photo-a", "photos"),
                member("photo-b", "photos"),
                member("doc-a", "documents"),
                member("doc-b", "documents"),
            )
        } else {
            emptyList()
        }
        assertTrue(scanStore.replaceStagedResults("completed", files, groups, members))
        assertTrue(
            scanStore.completeSession(
                "completed",
                20L,
                ScanCompletionMetrics(
                    scannedFileCount = files.size,
                    scannedByteCount = files.sumOf { it.sizeBytes },
                    duplicateFileCount = groups.sumOf { it.copyCount },
                    duplicateGroupCount = groups.size,
                    reclaimableBytes = groups.sumOf { it.reclaimableBytes },
                    skippedFileCount = 1,
                    errorCount = 0,
                ),
            ),
        )
    }

    private fun file(id: String, category: String) = IndexedFileEntity(
        id = id,
        sessionId = "completed",
        canonicalPath = "/storage/$id",
        displayName = id,
        extension = "bin",
        mimeType = null,
        category = category,
        sizeBytes = 100L,
        lastModified = 10L,
        volume = "primary",
        parentPath = "/storage",
        readable = true,
        writable = true,
    )

    private fun group(id: String, category: String) = DuplicateGroupEntity(
        id = id,
        sessionId = "completed",
        category = category,
        contentHash = "hash-$id",
        fileSize = 100L,
        copyCount = 2,
        reclaimableBytes = 100L,
        displayTitle = id,
    )

    private fun member(id: String, groupId: String) = DuplicateMemberEntity(
        id = id,
        groupId = groupId,
        indexedFileId = id,
        recommendedKeep = id.endsWith("a"),
        selectedForDeletion = id.endsWith("b"),
        protectedFromAutoSelection = false,
    )

    private fun primaryVolume() = AvailableStorageVolume(
        id = "primary",
        label = "Internal storage",
        directory = temporaryFolder.root,
        mediaStoreVolumeName = null,
        isPrimary = true,
        isReadOnly = false,
        totalBytes = 1_000L,
        freeBytes = 400L,
    )

    private fun TestScope.preferences(fileName: String) = PreferencesRepository(
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temporaryFolder.root.resolve(fileName) },
        ),
    )
}
