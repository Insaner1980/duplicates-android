package com.emma.duplicates.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.core.database.DuplicateGroupEntity
import com.emma.duplicates.core.database.DuplicateMemberEntity
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.ExclusionEntity
import com.emma.duplicates.core.database.ExclusionTypes
import com.emma.duplicates.core.database.FingerprintCacheEntity
import com.emma.duplicates.core.database.IndexedFileEntity
import com.emma.duplicates.core.database.ScanCompletionMetrics
import com.emma.duplicates.core.database.ScanFailureReasons
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanSessionEntity
import com.emma.duplicates.core.database.ScanStatuses
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
class ScanStoreTest {
    private lateinit var database: DuplicatesDatabase
    private lateinit var store: ScanStore

    @Before
    fun createStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store = ScanStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun stagedResultsAreVisibleOnlyAfterSuccessfulCompletion() =
        runTest {
            store.startSession("session", startedAt = 10L)
            assertEquals(ScanPhases.FINDING_FILES, store.runningSession.first()?.phase)

            assertTrue(
                store.replaceStagedResults(
                    sessionId = "session",
                    files = listOf(indexedFile()),
                    groups = listOf(group()),
                    members = listOf(member()),
                ),
            )
            assertNull(store.activeResults.first())

            assertTrue(
                store.completeSession(
                    sessionId = "session",
                    completedAt = 20L,
                    metrics =
                        ScanCompletionMetrics(
                            scannedFileCount = 2,
                            scannedByteCount = 200L,
                            duplicateFileCount = 2,
                            duplicateGroupCount = 1,
                            reclaimableBytes = 100L,
                            skippedFileCount = 0,
                            errorCount = 0,
                        ),
                ),
            )

            val active = store.activeResults.first()!!
            assertEquals(ScanStatuses.COMPLETED, active.session.status)
            assertEquals("group", active.groups.single().group.id)
            assertFalse(store.replaceStagedResults("session", emptyList(), emptyList(), emptyList()))
        }

    @Test
    fun startingSessionAtomicallyFailsOrphanedRunningStagingAndPreservesActiveResults() =
        runTest {
            database.scanDao().insertSession(
                ScanSessionEntity(
                    id = "active",
                    status = ScanStatuses.COMPLETED,
                    startedAt = 1L,
                    completedAt = 2L,
                    active = true,
                ),
            )
            store.startSession("orphan", startedAt = 10L)

            store.startSession("fresh", startedAt = 20L)

            val orphan = database.scanDao().getSession("orphan")!!
            assertEquals(ScanStatuses.FAILED, orphan.status)
            assertEquals(ScanFailureReasons.GENERIC, orphan.failureReason)
            assertEquals(20L, orphan.completedAt)
            assertFalse(orphan.active)
            assertEquals("fresh", store.runningSession.first()?.id)
            assertEquals("active", store.activeCompletedSession.first()?.id)
        }

    @Test
    fun successfulActivationPrunesOldSessionsAndTheirMetadataButKeepsFingerprintCache() =
        runTest {
            val fingerprint =
                FingerprintCacheEntity(
                    canonicalPath = "/storage/a.jpg",
                    volume = "primary",
                    sizeBytes = 100L,
                    lastModified = 10L,
                    quickHash = "quick",
                    fullHash = "full",
                    updatedAt = 20L,
                )
            store.putFingerprint(fingerprint)
            database.scanDao().insertSession(
                ScanSessionEntity(
                    id = "previous",
                    status = ScanStatuses.COMPLETED,
                    startedAt = 1L,
                    completedAt = 2L,
                    active = true,
                ),
            )
            database.scanDao().insertIndexedFiles(listOf(indexedFile(sessionId = "previous")))
            database.scanDao().insertGroups(listOf(group(sessionId = "previous")))
            database.scanDao().insertMembers(listOf(member()))
            database.scanDao().insertSession(
                ScanSessionEntity(
                    id = "old-failed",
                    status = ScanStatuses.FAILED,
                    startedAt = 3L,
                    completedAt = 4L,
                ),
            )
            store.startSession("fresh", startedAt = 10L)

            assertTrue(
                store.completeSession(
                    sessionId = "fresh",
                    completedAt = 20L,
                    metrics =
                        ScanCompletionMetrics(
                            scannedFileCount = 0,
                            scannedByteCount = 0L,
                            duplicateFileCount = 0,
                            duplicateGroupCount = 0,
                            reclaimableBytes = 0L,
                            skippedFileCount = 0,
                            errorCount = 0,
                        ),
                ),
            )

            assertNull(database.scanDao().getSession("previous"))
            assertNull(database.scanDao().getSession("old-failed"))
            assertNull(database.scanDao().observeGroup("group").first())
            assertEquals("fresh", store.activeCompletedSession.first()?.id)
            assertEquals(fingerprint, store.findFingerprint("/storage/a.jpg", "primary"))
        }

    @Test
    fun cacheExclusionsAndScanMetadataHaveIndependentLifetimes() =
        runTest {
            val fingerprint =
                FingerprintCacheEntity(
                    canonicalPath = "/storage/a.jpg",
                    volume = "primary",
                    sizeBytes = 100L,
                    lastModified = 10L,
                    quickHash = "quick",
                    fullHash = "full",
                    updatedAt = 20L,
                )
            val exclusion =
                ExclusionEntity(
                    id = "excluded",
                    type = ExclusionTypes.FILE,
                    canonicalPath = "/storage/skip.jpg",
                    displayName = "skip.jpg",
                    createdAt = 30L,
                )
            store.putFingerprint(fingerprint)
            assertTrue(store.addExclusion(exclusion))
            store.startSession("session", startedAt = 10L)

            store.clearScanMetadata()

            assertNull(store.runningSession.first())
            assertEquals(fingerprint, store.findFingerprint("/storage/a.jpg", "primary"))
            assertEquals(listOf(exclusion), store.exclusions.first())

            store.invalidateFingerprint("/storage/a.jpg", "primary")
            store.removeExclusion("excluded")
            assertNull(store.findFingerprint("/storage/a.jpg", "primary"))
            assertTrue(store.exclusions.first().isEmpty())
        }

    private fun indexedFile(sessionId: String = "session") =
        IndexedFileEntity(
            id = "file",
            sessionId = sessionId,
            canonicalPath = "/storage/a.jpg",
            displayName = "a.jpg",
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

    private fun group(sessionId: String = "session") =
        DuplicateGroupEntity(
            id = "group",
            sessionId = sessionId,
            category = "photos",
            contentHash = "full",
            fileSize = 100L,
            copyCount = 2,
            reclaimableBytes = 100L,
            displayTitle = "a.jpg",
        )

    private fun member() =
        DuplicateMemberEntity(
            id = "member",
            groupId = "group",
            indexedFileId = "file",
            recommendedKeep = true,
            selectedForDeletion = false,
            protectedFromAutoSelection = false,
        )
}
