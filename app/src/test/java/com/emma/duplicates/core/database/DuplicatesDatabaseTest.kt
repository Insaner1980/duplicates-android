package com.emma.duplicates.core.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DuplicatesDatabaseTest {
    private lateinit var database: DuplicatesDatabase
    private lateinit var scanDao: ScanDao
    private lateinit var fingerprintDao: FingerprintDao
    private lateinit var exclusionDao: ExclusionDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        scanDao = database.scanDao()
        fingerprintDao = database.fingerprintDao()
        exclusionDao = database.exclusionDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun failedStagingSessionPersistsReasonAndPreservesPreviousCompletedResults() =
        runTest {
            scanDao.insertSession(completedSession(id = "previous", active = true))
            scanDao.insertSession(runningSession(id = "staging"))

            assertEquals("previous", scanDao.observeActiveCompletedSession().first()?.id)

            assertTrue(
                scanDao.failStagingSession(
                    sessionId = "staging",
                    completedAt = 30L,
                    errorCount = 2,
                    failureReason = ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE,
                ),
            )

            assertEquals("previous", scanDao.observeActiveCompletedSession().first()?.id)
            assertEquals(ScanStatuses.FAILED, scanDao.getSession("staging")?.status)
            assertEquals(
                ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE,
                scanDao.getSession("staging")?.failureReason,
            )
            assertFalse(scanDao.getSession("staging")!!.active)
        }

    @Test
    fun canceledStagingSessionPreservesPreviousCompletedResults() =
        runTest {
            scanDao.insertSession(completedSession(id = "previous", active = true))
            scanDao.insertSession(runningSession(id = "staging"))

            assertTrue(scanDao.cancelStagingSession("staging", completedAt = 40L))

            assertEquals("previous", scanDao.observeActiveCompletedSession().first()?.id)
            val canceled = scanDao.getSession("staging")!!
            assertEquals(ScanStatuses.CANCELED, canceled.status)
            assertTrue(canceled.canceled)
            assertFalse(canceled.active)
        }

    @Test
    fun successfulStagingSessionAtomicallyReplacesActiveResultsAndPrunesPreviousSession() =
        runTest {
            scanDao.insertSession(completedSession(id = "previous", active = true))
            scanDao.insertSession(runningSession(id = "staging"))

            val completed =
                scanDao.completeStagingSession(
                    sessionId = "staging",
                    completedAt = 50L,
                    metrics =
                        ScanCompletionMetrics(
                            scannedFileCount = 12,
                            scannedByteCount = 5_000L,
                            duplicateFileCount = 4,
                            duplicateGroupCount = 2,
                            reclaimableBytes = 1_000L,
                            skippedFileCount = 1,
                            errorCount = 0,
                        ),
                )

            assertTrue(completed)
            assertEquals("staging", scanDao.observeActiveCompletedSession().first()?.id)
            assertNull(scanDao.getSession("previous"))
            val active = scanDao.getSession("staging")!!
            assertEquals(ScanStatuses.COMPLETED, active.status)
            assertEquals(12, active.scannedFileCount)
            assertEquals(1_000L, active.reclaimableBytes)
        }

    @Test
    fun runningProgressIsPersistedWithTruthfulOptionalTotal() =
        runTest {
            scanDao.insertSession(runningSession(id = "staging"))

            scanDao.updateProgress(
                sessionId = "staging",
                update =
                    ScanProgressUpdate(
                        phase = ScanPhases.COMPARING_CANDIDATES,
                        completedWork = 2_048L,
                        totalWork = 8_192L,
                        scannedFileCount = 42,
                        scannedByteCount = 50_000L,
                        skippedFileCount = 3,
                        errorCount = 1,
                    ),
            )

            val running = scanDao.observeRunningSession().first()!!
            assertEquals(ScanPhases.COMPARING_CANDIDATES, running.phase)
            assertEquals(2_048L, running.progressCompletedWork)
            assertEquals(8_192L, running.progressTotalWork)
            assertEquals(42, running.scannedFileCount)
            assertEquals(3, running.skippedFileCount)
        }

    @Test
    fun activeResultsContainGroupsMembersAndIndexedFiles() =
        runTest {
            scanDao.insertSession(completedSession(id = "completed", active = true))
            scanDao.insertIndexedFiles(
                listOf(
                    indexedFile(id = "file-a", sessionId = "completed", path = "/storage/a.jpg"),
                    indexedFile(id = "file-b", sessionId = "completed", path = "/storage/b.jpg"),
                ),
            )
            scanDao.insertGroups(
                listOf(
                    duplicateGroup(id = "group", sessionId = "completed"),
                ),
            )
            scanDao.insertMembers(
                listOf(
                    duplicateMember(id = "member-a", fileId = "file-a", recommendedKeep = true),
                    duplicateMember(id = "member-b", fileId = "file-b", selected = true),
                ),
            )

            val results = scanDao.observeActiveResults().first()!!

            assertEquals("completed", results.session.id)
            assertEquals(1, results.groups.size)
            assertEquals(setOf("file-a", "file-b"), results.groups.single().members.map { it.file.id }.toSet())
            assertTrue(results.groups.single().members.first { it.member.id == "member-a" }.member.recommendedKeep)
        }

    @Test
    fun selectionCannotRemoveFinalValidCopyAndUpdatesReclaimableTotals() =
        runTest {
            scanDao.insertSession(completedSession(id = "completed", active = true, reclaimableBytes = 0L))
            scanDao.insertIndexedFiles(
                listOf(
                    indexedFile(id = "file-a", sessionId = "completed", path = "/storage/a.jpg"),
                    indexedFile(id = "file-b", sessionId = "completed", path = "/storage/b.jpg"),
                ),
            )
            scanDao.insertGroups(listOf(duplicateGroup(id = "group", sessionId = "completed", reclaimableBytes = 0L)))
            scanDao.insertMembers(
                listOf(
                    duplicateMember(id = "member-a", fileId = "file-a"),
                    duplicateMember(id = "member-b", fileId = "file-b"),
                ),
            )

            assertTrue(scanDao.setMemberSelected("member-a", selected = true))
            assertFalse(scanDao.setMemberSelected("member-b", selected = true))

            val results = scanDao.observeActiveResults().first()!!
            val group = results.groups.single()
            assertTrue(group.members.first { it.member.id == "member-a" }.member.selectedForDeletion)
            assertFalse(group.members.first { it.member.id == "member-b" }.member.selectedForDeletion)
            assertEquals(100L, group.group.reclaimableBytes)
            assertEquals(100L, results.session.reclaimableBytes)
        }

    @Test
    fun fingerprintIsReusableOnlyForTheCompleteIdentity() =
        runTest {
            val fingerprint =
                FingerprintCacheEntity(
                    canonicalPath = "/storage/a.jpg",
                    volume = "primary",
                    sizeBytes = 100L,
                    lastModified = 200L,
                    quickHash = "quick",
                    fullHash = "full",
                    updatedAt = 300L,
                )
            fingerprintDao.upsert(fingerprint)

            assertEquals(
                fingerprint,
                fingerprintDao.findReusable(
                    canonicalPath = "/storage/a.jpg",
                    volume = "primary",
                    sizeBytes = 100L,
                    lastModified = 200L,
                ),
            )
            assertNull(
                fingerprintDao.findReusable(
                    canonicalPath = "/storage/a.jpg",
                    volume = "primary",
                    sizeBytes = 101L,
                    lastModified = 200L,
                ),
            )
            assertEquals(fingerprint, fingerprintDao.findByLocation("/storage/a.jpg", "primary"))
        }

    @Test
    fun upsertingChangedFingerprintInvalidatesTheOldIdentity() =
        runTest {
            fingerprintDao.upsert(
                FingerprintCacheEntity(
                    canonicalPath = "/storage/a.jpg",
                    volume = "primary",
                    sizeBytes = 100L,
                    lastModified = 200L,
                    quickHash = "old-quick",
                    fullHash = "old-full",
                    updatedAt = 300L,
                ),
            )
            fingerprintDao.upsert(
                FingerprintCacheEntity(
                    canonicalPath = "/storage/a.jpg",
                    volume = "primary",
                    sizeBytes = 110L,
                    lastModified = 210L,
                    quickHash = "new-quick",
                    fullHash = "new-full",
                    updatedAt = 310L,
                ),
            )

            assertNull(fingerprintDao.findReusable("/storage/a.jpg", "primary", 100L, 200L))
            assertEquals("new-full", fingerprintDao.findReusable("/storage/a.jpg", "primary", 110L, 210L)?.fullHash)

            fingerprintDao.invalidate("/storage/a.jpg", "primary")
            assertNull(fingerprintDao.findByLocation("/storage/a.jpg", "primary"))
        }

    @Test
    fun exclusionsAreUniqueByCanonicalPathAndRemovalIsObserved() =
        runTest {
            val first =
                ExclusionEntity(
                    id = "one",
                    type = ExclusionTypes.FOLDER,
                    canonicalPath = "/storage/Pictures/private",
                    displayName = "private",
                    createdAt = 10L,
                )
            val duplicatePath = first.copy(id = "two", displayName = "duplicate")

            assertTrue(exclusionDao.insert(first))
            assertFalse(exclusionDao.insert(duplicatePath))
            assertEquals(listOf(first), exclusionDao.observeAll().first())

            exclusionDao.remove("one")
            assertTrue(exclusionDao.observeAll().first().isEmpty())
        }

    @Test
    fun clearingScanMetadataKeepsFingerprintsAndExclusions() =
        runTest {
            scanDao.insertSession(completedSession(id = "completed", active = true))
            val fingerprint =
                FingerprintCacheEntity(
                    canonicalPath = "/storage/a.jpg",
                    volume = "primary",
                    sizeBytes = 100L,
                    lastModified = 200L,
                    quickHash = "quick",
                    fullHash = "full",
                    updatedAt = 300L,
                )
            fingerprintDao.upsert(fingerprint)
            val exclusion =
                ExclusionEntity(
                    id = "exclusion",
                    type = ExclusionTypes.FILE,
                    canonicalPath = "/storage/skip.jpg",
                    displayName = "skip.jpg",
                    createdAt = 10L,
                )
            exclusionDao.insert(exclusion)

            scanDao.clearScanMetadata()

            assertNull(scanDao.getSession("completed"))
            assertEquals(fingerprint, fingerprintDao.findByLocation("/storage/a.jpg", "primary"))
            assertEquals(listOf(exclusion), exclusionDao.observeAll().first())
        }

    private fun runningSession(id: String): ScanSessionEntity =
        ScanSessionEntity(
            id = id,
            status = ScanStatuses.RUNNING,
            startedAt = 20L,
        )

    private fun completedSession(
        id: String,
        active: Boolean,
        reclaimableBytes: Long = 100L,
    ): ScanSessionEntity =
        ScanSessionEntity(
            id = id,
            status = ScanStatuses.COMPLETED,
            startedAt = 10L,
            completedAt = 20L,
            scannedFileCount = 2,
            scannedByteCount = 200L,
            duplicateFileCount = 2,
            duplicateGroupCount = 1,
            reclaimableBytes = reclaimableBytes,
            active = active,
        )

    private fun indexedFile(
        id: String,
        sessionId: String,
        path: String,
    ): IndexedFileEntity =
        IndexedFileEntity(
            id = id,
            sessionId = sessionId,
            canonicalPath = path,
            displayName = path.substringAfterLast('/'),
            extension = "jpg",
            mimeType = "image/jpeg",
            category = "photos",
            sizeBytes = 100L,
            lastModified = 10L,
            volume = "primary",
            parentPath = "/storage",
            readable = true,
            writable = true,
            favorite = false,
        )

    private fun duplicateGroup(
        id: String,
        sessionId: String,
        reclaimableBytes: Long = 100L,
    ): DuplicateGroupEntity =
        DuplicateGroupEntity(
            id = id,
            sessionId = sessionId,
            category = "photos",
            contentHash = "full",
            fileSize = 100L,
            copyCount = 2,
            reclaimableBytes = reclaimableBytes,
            displayTitle = "a.jpg",
        )

    private fun duplicateMember(
        id: String,
        fileId: String,
        recommendedKeep: Boolean = false,
        selected: Boolean = false,
    ): DuplicateMemberEntity =
        DuplicateMemberEntity(
            id = id,
            groupId = "group",
            indexedFileId = fileId,
            recommendedKeep = recommendedKeep,
            selectedForDeletion = selected,
            protectedFromAutoSelection = false,
        )
}
