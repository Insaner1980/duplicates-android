package com.emma.duplicates.app

import android.app.Application
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.emma.duplicates.core.database.ScanFailureReasons
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.database.ScanStoreDatabaseTest
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.FileDiscovery
import com.emma.duplicates.core.storage.MediaStoreRefreshResult
import com.emma.duplicates.data.ToolResultStore
import com.emma.duplicates.data.preferences.AppPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ToolScanRunnerTest : ScanStoreDatabaseTest() {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val container = mockk<AppContainer>(relaxed = true)
    private lateinit var store: ToolResultStore
    private lateinit var volume: AvailableStorageVolume
    private lateinit var runner: ToolScanRunner
    private val preferences = AppPreferences(false, true, true, true, true, true, true, true, setOf("primary"))

    @Before
    fun prepareRunner() {
        store = ToolResultStore(database)
        volume = AvailableStorageVolume("primary", "Storage", temporaryFolder.newFolder(), null, true, false, 1000, 500)
        every { container.toolResultStore } returns store
        every { container.database } returns database
        every { container.deletionOperationMutex } returns Mutex()
        every { container.storageAccessManager.hasAccess() } returns true
        every { container.storageVolumeSource.mountedVolumes() } answers { listOf(volume) }
        every { container.preferencesRepository.preferences } returns flowOf(preferences)
        every { container.fileDiscovery } returns FileDiscovery()
        coEvery { container.scanStore.getExclusions() } returns emptyList()
        coEvery { container.mediaStoreMetadataIndex.refresh(any()) } returns MediaStoreRefreshResult(0, 0)
        runner = ToolScanRunner(context, container)
    }

    @Test
    fun completedScanStoresScopeAndLeavesFilesUnselected() =
        runTest {
            val downloads = volume.directory.resolve("Download").apply { mkdir() }
            val file = downloads.resolve("old.txt").apply { writeText("download") }
            val progress = mutableListOf<Int>()
            assertTrue(runner.run("downloads", CleanupTool.OLD_DOWNLOADS) { _, count -> progress += count })
            val result = store.active(CleanupTool.OLD_DOWNLOADS).first()!!
            assertEquals(listOf(file.canonicalPath), result.items.map { it.canonicalPath })
            assertTrue(result.items.none { it.selected })
            assertEquals(ScanStatuses.COMPLETED, result.session.status)
            assertEquals(
                volume.directory.canonicalPath,
                ToolScope
                    .decode(result.session.scope)
                    .volumes
                    .single()
                    .path,
            )
            assertEquals(0, progress.first())
        }

    @Test
    fun toolSpecificDiscoveryPersistsParametersAndCapacity() =
        runTest {
            volume.directory.resolve("empty").mkdir()
            assertTrue(runner.run("folders", CleanupTool.EMPTY_FOLDERS))
            assertTrue(
                store
                    .active(CleanupTool.EMPTY_FOLDERS)
                    .first()!!
                    .items
                    .single()
                    .directory,
            )
            assertTrue(runner.run("large", CleanupTool.LARGE_FILES))
            assertTrue(ToolScope.decode(store.dao.session("large")!!.scope).minimumFileBytes > 0)
            assertTrue(runner.run("photos", CleanupTool.SIMILAR_PHOTOS))
            assertNotNull(ToolScope.decode(store.dao.session("photos")!!.scope).photoFingerprintVersion)
            volume.directory.resolve("note.txt").writeText("usage")
            assertTrue(runner.run("usage", CleanupTool.STORAGE_USAGE, "primary"))
            val usage = store.active(CleanupTool.STORAGE_USAGE).first()!!
            assertEquals(1000L, usage.session.totalBytes)
            assertEquals(500L, usage.session.freeBytes)
            assertEquals("note.txt", usage.items.single().name)
            assertTrue(ToolScope.decode(usage.session.scope).scanHiddenFolders)
        }

    @Test
    fun selectedPathsRestrictTheScanAndMissingSelectionFailsClosed() =
        runTest {
            val chosen = volume.directory.resolve("chosen").apply { mkdir() }
            chosen.resolve("empty").mkdir()
            volume.directory.resolve("outside").mkdir()
            every { container.preferencesRepository.preferences } returns
                flowOf(
                    preferences.copy(scanSelectedItemsOnly = true, selectedScanPaths = setOf(chosen.path)),
                )
            coEvery { container.storageBrowser.scanRoots(any()) } returns
                listOf(volume.toScanRoot().copy(directory = chosen))
            assertTrue(runner.run("selected", CleanupTool.EMPTY_FOLDERS))
            assertTrue(
                store
                    .active(CleanupTool.EMPTY_FOLDERS)
                    .first()!!
                    .items
                    .all { it.canonicalPath.startsWith(chosen.path) },
            )
            coEvery { container.storageBrowser.scanRoots(any()) } returns null
            assertFalse(runner.run("missing", CleanupTool.EMPTY_FOLDERS))
            assertEquals(ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE, store.dao.session("missing")!!.failureReason)
            assertEquals(
                "selected",
                store
                    .active(CleanupTool.EMPTY_FOLDERS)
                    .first()!!
                    .session.id,
            )
        }

    @Test
    fun failuresAreClassifiedAndPreserveCompletedResults() =
        runTest {
            assertTrue(runner.run("previous", CleanupTool.LARGE_FILES))
            val failures =
                listOf(
                    SecurityException() to ScanFailureReasons.PERMISSION_REVOKED,
                    IOException() to ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE,
                    SQLiteFullException() to ScanFailureReasons.STORAGE_FULL,
                    SQLiteException() to ScanFailureReasons.DATABASE,
                    ForegroundWorkerFailureException(IllegalStateException()) to ScanFailureReasons.FOREGROUND_WORKER,
                    IllegalStateException() to ScanFailureReasons.GENERIC,
                )
            failures.forEachIndexed { index, (failure, reason) ->
                coEvery { container.mediaStoreMetadataIndex.refresh(any()) } throws failure
                assertFalse(runner.run("failed-$index", CleanupTool.LARGE_FILES))
                assertEquals(reason, store.dao.session("failed-$index")!!.failureReason)
                assertEquals(
                    "previous",
                    store
                        .active(CleanupTool.LARGE_FILES)
                        .first()!!
                        .session.id,
                )
            }
        }

    @Test
    fun cancellationIsRethrownAndUnsupportedToolsDoNotCreateSessions() =
        runTest {
            val cancellation = CancellationException("stopped")
            try {
                runner.run("canceled", CleanupTool.LARGE_FILES) { _, _ -> throw cancellation }
                org.junit.Assert.fail("Cancellation must propagate")
            } catch (caught: CancellationException) {
                assertEquals(cancellation.message, caught.message)
            }
            assertEquals(ScanStatuses.CANCELED, store.dao.session("canceled")!!.status)
            assertFalse(runner.run("unsupported", CleanupTool.UNUSED_APPS))
            assertEquals(null, store.dao.session("unsupported"))
        }

    @Test
    fun revokedAccessAndMissingVolumesFailWithoutActivatingResults() =
        runTest {
            every { container.storageAccessManager.hasAccess() } returns false
            assertFalse(runner.run("denied", CleanupTool.LARGE_FILES))
            assertEquals(ScanFailureReasons.PERMISSION_REVOKED, store.dao.session("denied")!!.failureReason)
            every { container.storageAccessManager.hasAccess() } returns true
            every { container.storageVolumeSource.mountedVolumes() } returns emptyList()
            assertFalse(runner.run("removed", CleanupTool.LARGE_FILES))
            assertEquals(ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE, store.dao.session("removed")!!.failureReason)
            assertEquals(null, store.active(CleanupTool.LARGE_FILES).first())
        }

    @Test
    fun metadataErrorsAndChangingCapacityAreVisibleInTheResult() =
        runTest {
            coEvery { container.mediaStoreMetadataIndex.refresh(any()) } returns MediaStoreRefreshResult(0, 2)
            var reads = 0
            every { container.storageVolumeSource.mountedVolumes() } answers {
                reads++
                listOf(if (reads >= 4) volume.copy(freeBytes = 400) else volume)
            }
            assertTrue(runner.run("partial", CleanupTool.STORAGE_USAGE))
            val result = store.dao.session("partial")!!
            assertFalse(result.complete)
            assertEquals(2, result.errorCount)
            assertEquals("measurement_changed", result.failureReason)
            assertEquals(400L, result.freeBytes)
        }
}
