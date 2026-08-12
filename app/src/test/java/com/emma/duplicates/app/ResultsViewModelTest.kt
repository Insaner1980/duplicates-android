package com.emma.duplicates.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.core.database.DuplicateGroupEntity
import com.emma.duplicates.core.database.DuplicateMemberEntity
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.IndexedFileEntity
import com.emma.duplicates.core.database.ScanCompletionMetrics
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.ui.results.ResultsFilter
import com.emma.duplicates.ui.results.ResultsSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ResultsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
    fun `filters search and every sort operate on persisted completed groups`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedResults()
            val viewModel = ResultsViewModel(scanStore)

            val initial = viewModel.state.first { it.hasCompletedScan }
            assertEquals(11, initial.duplicateFileCount)
            assertEquals(4, initial.duplicateGroupCount)
            assertEquals(1_600L, initial.reclaimableBytes)
            assertEquals(listOf("audio", "photo", "video", "docs"), initial.groups.map { it.id })
            assertEquals(4, initial.groups.single { it.id == "video" }.paths.size)
            assertEquals("content://media/video-0", initial.groups.single { it.id == "video" }.thumbnailModel)
            assertEquals("video-0", initial.groups.single { it.id == "video" }.previewFileId)
            assertEquals("content://media/audio-0", initial.groups.single { it.id == "audio" }.audioContentUri)
            assertEquals("audio-0", initial.groups.single { it.id == "audio" }.previewFileId)

            viewModel.setFilter(ResultsFilter.PHOTOS)
            assertEquals(listOf("photo"), viewModel.state.first { it.selectedFilter == ResultsFilter.PHOTOS }.groups.map { it.id })
            viewModel.setFilter(ResultsFilter.ALL)

            viewModel.setSearchVisible(true)
            viewModel.setSearchQuery("BuDgEt")
            val searched = viewModel.state.first { it.searchQuery == "BuDgEt" }
            assertTrue(searched.searchVisible)
            assertEquals(listOf("docs"), searched.groups.map { it.id })
            viewModel.setSearchVisible(false)
            assertEquals("", viewModel.state.first { !it.searchVisible && it.searchQuery.isEmpty() }.searchQuery)

            assertSort(viewModel, ResultsSort.LARGEST_RECLAIMABLE, listOf("audio", "photo", "video", "docs"))
            assertSort(viewModel, ResultsSort.MOST_COPIES, listOf("video", "audio", "photo", "docs"))
            assertSort(viewModel, ResultsSort.FILE_NAME, listOf("video", "audio", "docs", "photo"))
            assertSort(viewModel, ResultsSort.NEWEST, listOf("docs", "photo", "video", "audio"))
            assertSort(viewModel, ResultsSort.OLDEST, listOf("docs", "photo", "audio", "video"))
        }

    @Test
    fun `clear confirmation removes scan metadata and resets result state`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedResults()
            val viewModel = ResultsViewModel(scanStore)
            viewModel.state.first { it.hasCompletedScan }

            viewModel.setOverflowVisible(true)
            viewModel.requestClear()
            val requested = viewModel.state.first { it.clearConfirmationVisible }
            assertFalse(requested.overflowVisible)
            viewModel.dismissClear()
            assertFalse(viewModel.state.first { !it.clearConfirmationVisible }.clearConfirmationVisible)

            viewModel.requestClear()
            viewModel.confirmClear()
            val cleared = viewModel.state.first { !it.hasCompletedScan }

            assertEquals(0, cleared.duplicateFileCount)
            assertEquals(0, cleared.duplicateGroupCount)
            assertEquals(0L, cleared.reclaimableBytes)
            assertTrue(cleared.groups.isEmpty())
            assertFalse(cleared.clearConfirmationVisible)
        }

    private suspend fun assertSort(
        viewModel: ResultsViewModel,
        sort: ResultsSort,
        expectedIds: List<String>,
    ) {
        viewModel.setSort(sort)
        assertEquals(expectedIds, viewModel.state.first { it.selectedSort == sort }.groups.map { it.id })
    }

    private suspend fun seedResults() {
        val definitions = listOf(
            GroupDefinition("photo", "Zulu.jpg", "PHOTOS", 2, 500L, listOf(10L, 90L), "/storage/DCIM/Camera"),
            GroupDefinition("video", "Alpha.mp4", "VIDEOS", 4, 300L, listOf(50L, 55L, 58L, 60L), "/storage/Movies"),
            GroupDefinition("audio", "Middle.mp3", "AUDIO", 3, 700L, listOf(20L, 25L, 30L), "/storage/Music"),
            GroupDefinition("docs", "Report.pdf", "DOCUMENTS", 2, 100L, listOf(5L, 100L), "/storage/Work"),
        )
        val files = definitions.flatMap { definition ->
            definition.dates.mapIndexed { index, date ->
                val id = "${definition.id}-$index"
                val parentPath =
                    if (definition.id == "video") "${definition.parent}/$index" else definition.parent
                IndexedFileEntity(
                    id = id,
                    sessionId = SESSION_ID,
                    canonicalPath = "$parentPath/$id",
                    displayName = if (definition.id == "docs" && index == 1) "budget-report.pdf" else "$id.bin",
                    extension = "bin",
                    mimeType = null,
                    category = definition.category,
                    sizeBytes = 100L,
                    lastModified = date,
                    volume = "primary",
                    parentPath = parentPath,
                    contentUri =
                        if (definition.category == "DOCUMENTS") {
                            null
                        } else {
                            "content://media/$id"
                        },
                    readable = true,
                    writable = true,
                )
            }
        }
        val groups = definitions.map { definition ->
            DuplicateGroupEntity(
                id = definition.id,
                sessionId = SESSION_ID,
                category = definition.category,
                contentHash = "hash-${definition.id}",
                fileSize = 100L,
                copyCount = definition.copies,
                reclaimableBytes = definition.reclaimable,
                displayTitle = definition.title,
            )
        }
        val members = definitions.flatMap { definition ->
            definition.dates.indices.map { index ->
                val id = "${definition.id}-$index"
                DuplicateMemberEntity(
                    id = id,
                    groupId = definition.id,
                    indexedFileId = id,
                    recommendedKeep = index == 0,
                    selectedForDeletion = index > 0,
                    protectedFromAutoSelection = false,
                )
            }
        }
        scanStore.startSession(SESSION_ID, 10L)
        assertTrue(scanStore.replaceStagedResults(SESSION_ID, files, groups, members))
        assertTrue(
            scanStore.completeSession(
                SESSION_ID,
                20L,
                ScanCompletionMetrics(files.size, files.size * 100L, 11, 4, 1_600L, 0, 0),
            ),
        )
    }

    private data class GroupDefinition(
        val id: String,
        val title: String,
        val category: String,
        val copies: Int,
        val reclaimable: Long,
        val dates: List<Long>,
        val parent: String,
    )

    private companion object {
        const val SESSION_ID = "completed"
    }
}
