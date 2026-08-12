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
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.data.preferences.PreferencesRepository
import com.emma.duplicates.domain.deletion.DeletionCoordinator
import com.emma.duplicates.domain.deletion.DeletionDatabaseUpdate
import com.emma.duplicates.domain.deletion.DeletionGroup
import com.emma.duplicates.domain.deletion.DeletionResult
import com.emma.duplicates.domain.deletion.DeletionStatus
import com.emma.duplicates.domain.deletion.FileDeletionOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: DuplicatesDatabase
    private lateinit var scanStore: ScanStore

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
    fun `review maps persisted members and propagates allowed and blocked selection changes`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedGroup()
            val viewModel = ReviewViewModel(GROUP_ID, scanStore, preferences("selection.preferences_pb"), mockk(relaxed = true))

            val initial = viewModel.state.first { it != null }!!
            assertTrue(viewModel.groupStillExists())
            assertEquals(5, initial.identicalFileCount)
            assertEquals(3, initial.selectedCount)
            assertEquals(200L, initial.selectedBytes)
            assertTrue(initial.members.first().recommendedKeep)
            assertNotNull(initial.members.first().thumbnailModel)
            assertTrue(initial.members.single { it.id == "keep" }.canSelectForDeletion)

            viewModel.changeSelection("keep", selected = true)
            val changed = viewModel.state.first { it?.selectedCount == 4 }!!
            assertTrue(changed.members.single { it.id == "keep" }.selectedForDeletion)
            assertFalse(changed.members.single { it.id == "other" }.canSelectForDeletion)

            viewModel.changeSelection("other", selected = true)
            advanceUntilIdle()
            val blocked = viewModel.state.value!!
            assertEquals(4, blocked.selectedCount)
            assertFalse(blocked.members.single { it.id == "other" }.selectedForDeletion)
        }

    @Test
    fun `confirm preference gates deletion and maps detailed deletion result`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedGroup()
            val preferences = preferences("deletion.preferences_pb")
            val coordinator = mockk<DeletionCoordinator>()
            val captured = slot<List<DeletionGroup>>()
            coEvery { coordinator.delete(capture(captured)) } returns deletionResult()
            val viewModel = ReviewViewModel(GROUP_ID, scanStore, preferences, coordinator)
            viewModel.state.first { it != null }

            viewModel.requestDelete()
            val confirmation = viewModel.state.first { it?.deleteConfirmationVisible == true }!!
            assertEquals(3, confirmation.selectedCount)
            coVerify(exactly = 0) { coordinator.delete(any()) }

            viewModel.confirmDelete()
            val completed = viewModel.state.first { it?.deletionResult != null }!!
            coVerify(exactly = 1) { coordinator.delete(any()) }
            assertEquals(setOf("selected-a", "selected-b", "selected-c"), captured.captured.single().files.filter { it.selectedForDeletion }.mapTo(mutableSetOf()) { it.id })
            assertEquals(
                "member-selected-b",
                captured.captured.single().files.single { it.id == "selected-b" }.memberId,
            )
            assertTrue(
                captured.captured.single().files
                    .filter { it.selectedForDeletion }
                    .all { it.requiresMediaAuthorization },
            )
            assertEquals("full-hash", captured.captured.single().expectedFullHash)
            assertEquals(1, completed.deletionResult?.deletedCount)
            assertEquals(2, completed.deletionResult?.failedCount)
            assertEquals(100L, completed.deletionResult?.reclaimedBytes)
            assertTrue(completed.deletionResult?.changedFilesKept == true)
            assertTrue(completed.deletionResult?.authorizationCanceled == true)
            assertFalse(completed.deleteConfirmationVisible)

            viewModel.dismissDeletionResult()
            assertEquals(null, viewModel.state.value?.deletionResult)
            preferences.setConfirmBeforeDelete(false)
            viewModel.requestDelete()
            viewModel.state.first { it?.deletionResult != null }
            coVerify(exactly = 2) { coordinator.delete(any()) }
            assertFalse(viewModel.state.value!!.deleteConfirmationVisible)
        }

    @Test
    fun `document content URI is routed to direct deletion`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedGroup(category = "DOCUMENTS")
            val coordinator = mockk<DeletionCoordinator>()
            val captured = slot<List<DeletionGroup>>()
            coEvery { coordinator.delete(capture(captured)) } returns deletionResult()
            val viewModel = ReviewViewModel(
                GROUP_ID,
                scanStore,
                preferences("document-deletion.preferences_pb"),
                coordinator,
            )
            viewModel.state.first { it != null }

            viewModel.requestDelete()
            viewModel.state.first { it?.deleteConfirmationVisible == true }
            viewModel.confirmDelete()
            viewModel.state.first { it?.deletionResult != null }

            val selectedFiles = captured.captured.single().files.filter { it.selectedForDeletion }
            assertTrue(selectedFiles.all { it.contentUri != null })
            assertTrue(selectedFiles.none { it.requiresMediaAuthorization })
        }

    @Test
    fun `audio review members pass their content URI without preloading album art`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedGroup(category = "AUDIO")
            val viewModel = ReviewViewModel(GROUP_ID, scanStore, preferences("audio.preferences_pb"), mockk(relaxed = true))

            val state = viewModel.state.first { it != null }!!

            assertEquals("content://media/keep", state.members.first().audioContentUri)
            assertEquals(null, state.members.first().thumbnailModel)
        }

    private suspend fun seedGroup(category: String = "PHOTOS") {
        val files = listOf(
            file("keep", readable = true, category = category),
            file("other", readable = true, category = category),
            file("selected-a", readable = true, category = category),
            file("selected-b", readable = true, category = category),
            file("selected-c", readable = false, category = category),
        )
        val members = files.map { file ->
            DuplicateMemberEntity(
                id = if (file.id == "selected-b") "member-selected-b" else file.id,
                groupId = GROUP_ID,
                indexedFileId = file.id,
                recommendedKeep = file.id == "keep",
                selectedForDeletion = file.id.startsWith("selected"),
                protectedFromAutoSelection = file.id == "keep",
            )
        }
        val group = DuplicateGroupEntity(
            id = GROUP_ID,
            sessionId = SESSION_ID,
            category = category,
            contentHash = "full-hash",
            fileSize = 100L,
            copyCount = files.size,
            reclaimableBytes = 300L,
            displayTitle = "photo.jpg",
        )
        scanStore.startSession(SESSION_ID, 10L)
        assertTrue(scanStore.replaceStagedResults(SESSION_ID, files, listOf(group), members))
        assertTrue(
            scanStore.completeSession(
                SESSION_ID,
                20L,
                ScanCompletionMetrics(files.size, 500L, files.size, 1, 300L, 0, 0),
            ),
        )
    }

    private fun file(
        id: String,
        readable: Boolean,
        category: String,
    ): IndexedFileEntity {
        val extension = when (category) {
            "AUDIO" -> "mp3"
            "DOCUMENTS" -> "pdf"
            else -> "jpg"
        }
        val mimeType = when (category) {
            "AUDIO" -> "audio/mpeg"
            "DOCUMENTS" -> "application/pdf"
            else -> "image/jpeg"
        }
        val parentPath = if (category == "DOCUMENTS") "/storage/Download" else "/storage/Pictures"
        return IndexedFileEntity(
            id = id,
            sessionId = SESSION_ID,
            canonicalPath = "$parentPath/$id.$extension",
            displayName = "$id.$extension",
            extension = extension,
            mimeType = mimeType,
            category = category,
            sizeBytes = 100L,
            lastModified = 10L,
            volume = "primary",
            parentPath = parentPath,
            contentUri = "content://media/$id",
            fullHash = "full-hash",
            readable = readable,
            writable = true,
            favorite = id == "keep",
        )
    }

    private fun deletionResult() = DeletionResult(
        outcomes = listOf(
            FileDeletionOutcome(GROUP_ID, "selected-a", DeletionStatus.DELETED, reclaimedBytes = 100L),
            FileDeletionOutcome(GROUP_ID, "selected-b", DeletionStatus.REVALIDATION_FAILED),
            FileDeletionOutcome(GROUP_ID, "selected-c", DeletionStatus.AUTHORIZATION_CANCELED),
        ),
        databaseUpdate = DeletionDatabaseUpdate(emptySet(), emptySet(), emptySet(), emptyList()),
    )

    private fun TestScope.preferences(fileName: String) = PreferencesRepository(
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temporaryFolder.root.resolve(fileName) },
        ),
    )

    private companion object {
        const val SESSION_ID = "completed"
        const val GROUP_ID = "group"
    }
}
