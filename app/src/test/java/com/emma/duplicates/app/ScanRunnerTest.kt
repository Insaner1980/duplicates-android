package com.emma.duplicates.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.ScanCompletionMetrics
import com.emma.duplicates.core.database.ScanFailureReasons
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanProgressUpdate
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.permissions.StorageAccessManager
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.ExactDuplicateScanner
import com.emma.duplicates.core.storage.FileDiscovery
import com.emma.duplicates.core.storage.MediaStoreRefreshResult
import com.emma.duplicates.core.storage.PlatformFileMetadata
import com.emma.duplicates.core.storage.PlatformMetadataIndex
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.RoomFingerprintCache
import com.emma.duplicates.data.ScanResultMapper
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.data.preferences.PreferencesRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScanRunnerTest {
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
    fun `real files complete a namespaced staging scan and prune old active results`() = runTest {
        val root = duplicateFixture("complete")
        val volume = volume(root)
        val metadataIndex = TestMetadataIndex()
        seedPreviousActive(volume, metadataIndex)
        val runner = runner(volume, metadataIndex, preferences("complete.preferences_pb"))

        val outcome = runner.run("staging")

        assertEquals(ScanRunOutcome.COMPLETED, outcome)
        assertEquals("staging", scanStore.activeCompletedSession.first()?.id)
        assertNull(database.scanDao().getSession("previous"))
        val active = scanStore.activeResults.first()!!
        assertEquals(1, active.groups.size)
        assertEquals(2, active.groups.single().members.size)
        assertEquals(1, active.groups.single().members.count { it.member.selectedForDeletion })
        assertTrue(active.groups.single().members.all { it.file.quickHash != null && it.file.fullHash != null })
        assertNotNull(scanStore.findFingerprint(root.resolve("copy-a.txt").canonicalPath, "primary"))
    }

    @Test
    fun `overall progress is monotonic across hashing and verification phases`() = runTest {
        val root = duplicateFixture("monotonic-progress")
        val volume = volume(root)
        val updates = mutableListOf<ScanProgressUpdate>()
        val runner = runner(
            volume,
            TestMetadataIndex(),
            preferences("monotonic-progress.preferences_pb"),
        )

        assertEquals(
            ScanRunOutcome.COMPLETED,
            runner.run("monotonic-progress") { update -> updates += update },
        )

        val measurable = updates.filter { it.totalWork != null }
        assertTrue(measurable.isNotEmpty())
        assertTrue(measurable.all { it.totalWork == 600_000L })
        val completed = measurable.map(ScanProgressUpdate::completedWork)
        assertEquals(completed.sorted(), completed)
        assertEquals(200_000L, measurable.first().completedWork)
        assertEquals(600_000L, measurable.last().completedWork)
        assertEquals(ScanPhases.VERIFYING_DUPLICATES, measurable.last().phase)
    }

    @Test
    fun `cancellation marks staging canceled and preserves previous active results`() = runTest {
        val root = duplicateFixture("canceled")
        val volume = volume(root)
        val metadataIndex = TestMetadataIndex()
        seedPreviousActive(volume, metadataIndex)
        val runner = runner(volume, metadataIndex, preferences("canceled.preferences_pb"))

        try {
            runner.run("canceled") { throw CancellationException("stop") }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
        }

        assertEquals("previous", scanStore.activeCompletedSession.first()?.id)
        assertEquals(ScanStatuses.CANCELED, database.scanDao().getSession("canceled")?.status)
        assertFalse(database.scanDao().getSession("canceled")!!.active)
    }

    @Test
    fun `failure marks staging failed and preserves previous active results`() = runTest {
        val root = duplicateFixture("failed")
        val volume = volume(root)
        seedPreviousActive(volume, TestMetadataIndex())
        val runner = runner(
            volume,
            TestMetadataIndex(refreshException = IllegalStateException("metadata refresh failed")),
            preferences("failed.preferences_pb"),
        )

        val outcome = runner.run("failed")

        assertEquals(ScanRunOutcome.FAILED, outcome)
        assertEquals("previous", scanStore.activeCompletedSession.first()?.id)
        assertEquals(ScanStatuses.FAILED, database.scanDao().getSession("failed")?.status)
        assertEquals(
            ScanFailureReasons.GENERIC,
            database.scanDao().getSession("failed")?.failureReason,
        )
        assertFalse(database.scanDao().getSession("failed")!!.active)
    }

    @Test
    fun `storage full failure is persisted separately from other database failures`() = runTest {
        val root = duplicateFixture("storage-full")
        val volume = volume(root)
        val runner =
            runner(
                volume,
                TestMetadataIndex(refreshException = SQLiteFullException("database or disk is full")),
                preferences("storage-full.preferences_pb"),
            )

        val outcome = runner.run("storage-full")

        assertEquals(ScanRunOutcome.FAILED, outcome)
        val failed = database.scanDao().getSession("storage-full")!!
        assertEquals(ScanStatuses.FAILED, failed.status)
        assertEquals(ScanFailureReasons.STORAGE_FULL, failed.failureReason)
    }

    @Test
    fun `database failure is persisted separately from a generic scan failure`() = runTest {
        val root = duplicateFixture("database-failure")
        val volume = volume(root)
        val runner =
            runner(
                volume,
                TestMetadataIndex(refreshException = SQLiteException("database unavailable")),
                preferences("database-failure.preferences_pb"),
            )

        val outcome = runner.run("database-failure")

        assertEquals(ScanRunOutcome.FAILED, outcome)
        val failed = database.scanDao().getSession("database-failure")!!
        assertEquals(ScanStatuses.FAILED, failed.status)
        assertEquals(ScanFailureReasons.DATABASE, failed.failureReason)
    }

    @Test
    fun `stale selected volume does not block another selected mounted volume`() = runTest {
        val root = duplicateFixture("stale-selected-volume")
        val volume = volume(root)
        val metadataIndex = TestMetadataIndex()
        val preferencesRepository = preferences("stale-selected-volume.preferences_pb")
        preferencesRepository.setSelectedVolumeIds(setOf("primary", "removed-volume"))
        val runner = runner(volume, metadataIndex, preferencesRepository)

        val outcome = runner.run("stale-selected-volume")

        assertEquals(ScanRunOutcome.COMPLETED, outcome)
        assertEquals("stale-selected-volume", scanStore.activeCompletedSession.first()?.id)
    }

    @Test
    fun `no mounted selected volume leaves a terminal session with an actionable reason`() = runTest {
        val root = duplicateFixture("no-mounted-selected-volume")
        val volume = volume(root)
        val runner =
            runner(
                volume = volume,
                metadataIndex = TestMetadataIndex(),
                preferencesRepository = preferences("no-mounted-selected-volume.preferences_pb"),
                storageVolumeSource = StorageVolumeSource { emptyList() },
            )

        val outcome = runner.run("no-mounted-selected-volume")

        assertEquals(ScanRunOutcome.EMPTY_SCOPE, outcome)
        val failed = database.scanDao().getSession("no-mounted-selected-volume")!!
        assertEquals(ScanStatuses.FAILED, failed.status)
        assertEquals(ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE, failed.failureReason)
    }

    @Test
    fun `permission revoked after discovery fails staging and preserves previous active results`() =
        runTest {
            val root = duplicateFixture("permission-revoked")
            val volume = volume(root)
            val metadataIndex = TestMetadataIndex()
            seedPreviousActive(volume, metadataIndex)
            var accessChecks = 0
            val runner =
                runner(
                    volume = volume,
                    metadataIndex = metadataIndex,
                    preferencesRepository = preferences("permission-revoked.preferences_pb"),
                    storageAccessManager =
                        StorageAccessManager(context.packageName) {
                            accessChecks++
                            accessChecks == 1
                        },
                )

            val outcome = runner.run("permission-revoked")

            assertEquals(ScanRunOutcome.STORAGE_ACCESS_REQUIRED, outcome)
            assertEquals(2, accessChecks)
            assertEquals("previous", scanStore.activeCompletedSession.first()?.id)
            assertEquals(
                ScanStatuses.FAILED,
                database.scanDao().getSession("permission-revoked")?.status,
            )
            assertEquals(
                ScanFailureReasons.PERMISSION_REVOKED,
                database.scanDao().getSession("permission-revoked")?.failureReason,
            )
        }

    @Test
    fun `selected volume root changed before activation fails staging and preserves previous results`() =
        runTest {
            val root = duplicateFixture("volume-changed")
            val volume = volume(root)
            val replacementRoot = temporaryFolder.newFolder("replacement-volume-root")
            val metadataIndex = TestMetadataIndex()
            seedPreviousActive(volume, metadataIndex)
            var volumeReads = 0
            val volumeSource =
                StorageVolumeSource {
                    volumeReads++
                    if (volumeReads < 3) {
                        listOf(volume)
                    } else {
                        listOf(volume.copy(directory = replacementRoot))
                    }
                }
            val runner =
                runner(
                    volume = volume,
                    metadataIndex = metadataIndex,
                    preferencesRepository = preferences("volume-changed.preferences_pb"),
                    storageVolumeSource = volumeSource,
                )

            val outcome = runner.run("volume-changed")

            assertEquals(ScanRunOutcome.FAILED, outcome)
            assertEquals(3, volumeReads)
            assertEquals("previous", scanStore.activeCompletedSession.first()?.id)
            assertEquals(
                ScanStatuses.FAILED,
                database.scanDao().getSession("volume-changed")?.status,
            )
            assertEquals(
                ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE,
                database.scanDao().getSession("volume-changed")?.failureReason,
            )
        }

    @Test
    fun `foreground setup failure leaves a terminal session with its own reason`() = runTest {
        val root = duplicateFixture("foreground-failure")
        val volume = volume(root)
        val runner = runner(volume, TestMetadataIndex(), preferences("foreground-failure.preferences_pb"))

        val outcome =
            runner.run(
                sessionId = "foreground-failure",
                onSessionStarted = {
                    throw ForegroundWorkerFailureException(IllegalStateException("foreground failed"))
                },
            )

        assertEquals(ScanRunOutcome.FAILED, outcome)
        val failed = database.scanDao().getSession("foreground-failure")!!
        assertEquals(ScanStatuses.FAILED, failed.status)
        assertEquals(ScanFailureReasons.FOREGROUND_WORKER, failed.failureReason)
    }

    @Test
    fun `cancellation during foreground setup leaves a canceled terminal session`() = runTest {
        val root = duplicateFixture("foreground-canceled")
        val volume = volume(root)
        val metadataIndex = TestMetadataIndex()
        seedPreviousActive(volume, metadataIndex)
        val runner = runner(volume, metadataIndex, preferences("foreground-canceled.preferences_pb"))
        val cancellation = CancellationException("work canceled")

        try {
            runner.run(
                sessionId = "foreground-canceled",
                onSessionStarted = { throw cancellation },
            )
            fail("Expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }

        val canceled = database.scanDao().getSession("foreground-canceled")!!
        assertEquals(ScanStatuses.CANCELED, canceled.status)
        assertNull(canceled.failureReason)
        assertEquals("previous", scanStore.activeCompletedSession.first()?.id)
    }

    @Test
    fun `context external media directories are excluded from discovery`() = runTest {
        val root = temporaryFolder.newFolder("external-media-exclusion")
        root.resolve("public.txt").writeText("public")
        val appPrivateMedia = root.resolve("app-private-media").apply { mkdirs() }
        appPrivateMedia.resolve("private-a.txt").writeText("same private bytes")
        appPrivateMedia.resolve("private-b.txt").writeText("same private bytes")
        val volume = volume(root)
        val scanContext =
            object : ContextWrapper(context) {
                override fun getExternalMediaDirs(): Array<File> = arrayOf(appPrivateMedia)
            }
        val runner =
            runner(
                volume = volume,
                metadataIndex = TestMetadataIndex(),
                preferencesRepository = preferences("external-media-exclusion.preferences_pb"),
                runnerContext = scanContext,
            )

        val outcome = runner.run("external-media-exclusion")

        assertEquals(ScanRunOutcome.COMPLETED, outcome)
        assertEquals(1, database.scanDao().getSession("external-media-exclusion")?.scannedFileCount)
    }

    private suspend fun seedPreviousActive(
        volume: AvailableStorageVolume,
        metadataIndex: PlatformMetadataIndex,
    ) {
        val discovery = FileDiscovery(metadataReader = metadataIndex).discover(listOf(volume.toScanRoot()))
        val result = ExactDuplicateScanner().scan(discovery.files)
        val mapped = ScanResultMapper().map("previous", discovery.files, result, autoSelect = true)
        scanStore.startSession("previous", 10L)
        assertTrue(scanStore.replaceStagedResults("previous", mapped.files, mapped.groups, mapped.members))
        assertTrue(
            scanStore.completeSession(
                "previous",
                20L,
                ScanCompletionMetrics(
                    scannedFileCount = discovery.files.size,
                    scannedByteCount = discovery.files.sumOf { it.sizeBytes },
                    duplicateFileCount = result.duplicateFileCount,
                    duplicateGroupCount = result.duplicateGroupCount,
                    reclaimableBytes = mapped.groups.sumOf { it.reclaimableBytes },
                    skippedFileCount = result.skippedFileCount,
                    errorCount = result.errorCount,
                ),
            ),
        )
    }

    private fun runner(
        volume: AvailableStorageVolume,
        metadataIndex: PlatformMetadataIndex,
        preferencesRepository: PreferencesRepository,
        storageAccessManager: StorageAccessManager =
            StorageAccessManager(context.packageName) { true },
        storageVolumeSource: StorageVolumeSource = StorageVolumeSource { listOf(volume) },
        runnerContext: Context = context,
    ): ScanRunner {
        var now = 30L
        return ScanRunner(
            context = runnerContext,
            storageAccessManager = storageAccessManager,
            storageVolumeSource = storageVolumeSource,
            mediaStoreMetadataIndex = metadataIndex,
            preferencesRepository = preferencesRepository,
            scanStore = scanStore,
            fileDiscovery = FileDiscovery(metadataReader = metadataIndex),
            exactDuplicateScanner = ExactDuplicateScanner(fingerprintCache = RoomFingerprintCache(scanStore)),
            scanResultMapper = ScanResultMapper(),
            currentTimeMillis = { now++ },
        )
    }

    private fun TestScope.preferences(fileName: String): PreferencesRepository =
        PreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { temporaryFolder.root.resolve(fileName) },
            ),
        )

    private fun duplicateFixture(name: String): File =
        temporaryFolder.newFolder(name).apply {
            resolve("copy-a.txt").writeText("identical bytes")
            resolve("copy-b.txt").writeText("identical bytes")
            resolve("unique.txt").writeText("different")
        }

    private fun volume(root: File) = AvailableStorageVolume(
        id = "primary",
        label = "Internal storage",
        directory = root,
        mediaStoreVolumeName = null,
        isPrimary = true,
        isReadOnly = false,
        totalBytes = 1_000_000L,
        freeBytes = 500_000L,
    )

    private class TestMetadataIndex(
        private val refreshException: Exception? = null,
    ) : PlatformMetadataIndex {
        override suspend fun refresh(volumes: List<AvailableStorageVolume>): MediaStoreRefreshResult {
            refreshException?.let { throw it }
            return MediaStoreRefreshResult(indexedFileCount = 0, errorCount = 0)
        }

        override suspend fun read(file: File): PlatformFileMetadata =
            PlatformFileMetadata(mimeType = "text/plain", isFavorite = false, isTrashed = false)
    }
}
