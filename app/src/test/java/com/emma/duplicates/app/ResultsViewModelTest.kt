package com.emma.duplicates.app

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.emma.duplicates.core.database.DuplicateGroupEntity
import com.emma.duplicates.core.database.DuplicateMemberEntity
import com.emma.duplicates.core.database.IndexedFileEntity
import com.emma.duplicates.core.database.ScanCompletionMetrics
import com.emma.duplicates.core.database.ScanStoreDatabaseTest
import com.emma.duplicates.ui.results.ResultsFilter
import com.emma.duplicates.ui.results.ResultsSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ResultsViewModelTest : ScanStoreDatabaseTest() {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun selectionDoesNotChangePotentialOrSortAndHiddenSelectionsRemainGlobal() =
        runTest(mainDispatcherRule.dispatcher) {
            seedResults()
            val model = ResultsViewModel(scanStore, savedStateHandle = SavedStateHandle())
            val initial = model.state.first { it.hasCompletedScan }
            assertEquals(7, initial.selectedCount)
            assertEquals(700L, initial.selectedBytes)
            model.changeSelection("video-1", false)
            val changed = model.state.first { it.selectedCount == 6 }
            assertEquals(700L, changed.availableBytes)
            assertEquals(initial.groups.map { it.id }, changed.groups.map { it.id })
            model.setFilter(ResultsFilter.PHOTOS)
            val filtered = model.state.first { it.selectedFilter == ResultsFilter.PHOTOS }
            assertEquals(6, filtered.selectedCount)
            assertEquals(5, filtered.hiddenSelectedCount)
            model.setSearchQuery("no match")
            assertEquals(6, model.state.first { it.searchQuery == "no match" }.hiddenSelectedCount)
            model.showAll()
            assertEquals(
                0,
                model.state
                    .first {
                        it.selectedFilter == ResultsFilter.ALL && it.searchQuery.isEmpty()
                    }.hiddenSelectedCount,
            )
        }

    @Test
    fun restoredControlsPreserveQueryFilterAndSort() =
        runTest(mainDispatcherRule.dispatcher) {
            seedResults()
            val saved = SavedStateHandle()
            val model = ResultsViewModel(scanStore, savedStateHandle = saved)
            model.setFilter(ResultsFilter.DOCUMENTS)
            model.setSearchVisible(true)
            model.setSearchQuery("budget")
            model.setSort(ResultsSort.NEWEST)
            val restored = ResultsViewModel(scanStore, savedStateHandle = saved).state.first { it.hasCompletedScan }
            assertEquals(ResultsFilter.DOCUMENTS, restored.selectedFilter)
            assertEquals(ResultsSort.NEWEST, restored.selectedSort)
            assertEquals("budget", restored.searchQuery)
            assertEquals(listOf("docs"), restored.groups.map { it.id })
        }

    @Test
    fun `filters search and every sort operate on persisted completed groups`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedResults()
            val viewModel = ResultsViewModel(scanStore, savedStateHandle = SavedStateHandle())

            val initial = viewModel.state.first { it.hasCompletedScan }
            assertEquals(11, initial.duplicateFileCount)
            assertEquals(4, initial.duplicateGroupCount)
            assertEquals(700L, initial.availableBytes)
            assertEquals(listOf("video", "audio", "docs", "photo"), initial.groups.map { it.id })
            assertEquals(
                4,
                initial.groups
                    .single { it.id == "video" }
                    .paths.size,
            )
            assertEquals("content://media/video-0", initial.groups.single { it.id == "video" }.thumbnailModel)
            assertEquals("video-0", initial.groups.single { it.id == "video" }.previewFileId)
            assertEquals("content://media/audio-0", initial.groups.single { it.id == "audio" }.audioContentUri)
            assertEquals("audio-0", initial.groups.single { it.id == "audio" }.previewFileId)

            viewModel.setFilter(ResultsFilter.PHOTOS)
            assertEquals(
                listOf("photo"),
                viewModel.state
                    .first { it.selectedFilter == ResultsFilter.PHOTOS }
                    .groups
                    .map { it.id },
            )
            viewModel.setFilter(ResultsFilter.ALL)

            viewModel.setSearchVisible(true)
            viewModel.setSearchQuery("BuDgEt")
            val searched = viewModel.state.first { it.searchQuery == "BuDgEt" }
            assertTrue(searched.searchVisible)
            assertEquals(listOf("docs"), searched.groups.map { it.id })
            viewModel.setSearchVisible(false)
            assertEquals("", viewModel.state.first { !it.searchVisible && it.searchQuery.isEmpty() }.searchQuery)

            assertSort(viewModel, ResultsSort.LARGEST_RECLAIMABLE, listOf("video", "audio", "docs", "photo"))
            assertSort(viewModel, ResultsSort.MOST_COPIES, listOf("video", "audio", "docs", "photo"))
            assertSort(viewModel, ResultsSort.FILE_NAME, listOf("video", "audio", "docs", "photo"))
            assertSort(viewModel, ResultsSort.NEWEST, listOf("docs", "photo", "video", "audio"))
            assertSort(viewModel, ResultsSort.OLDEST, listOf("docs", "photo", "audio", "video"))
        }

    @Test
    fun `clear confirmation removes scan metadata and resets result state`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedResults()
            val viewModel = ResultsViewModel(scanStore, savedStateHandle = SavedStateHandle())
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
        assertEquals(
            expectedIds,
            viewModel.state
                .first { it.selectedSort == sort }
                .groups
                .map { it.id },
        )
    }

    @Test
    fun audioRowsKeepContentUrisForLazyArtworkLoading() =
        runTest(mainDispatcherRule.dispatcher) {
            seedResults()
            val audio =
                ResultsViewModel(scanStore, savedStateHandle = SavedStateHandle())
                    .state
                    .first { it.groups.isNotEmpty() }
                    .groups
                    .first { it.id == "audio" }
            assertEquals("content://media/audio-0", audio.members.first().audioContentUri)
            assertEquals(null, audio.members.first().thumbnailModel)
        }

    @Test
    fun duplicateSummaryCountsMembersInsteadOfScannedFilesOrSelectedCopies() =
        runTest(mainDispatcherRule.dispatcher) {
            val definitions =
                (0 until 52).map { index ->
                    val copies = if (index < 4) 3 else 2
                    GroupDefinition(
                        "group-$index",
                        "file-$index.txt",
                        "DOCUMENTS",
                        copies,
                        0L,
                        (0 until copies).map { it.toLong() },
                        "/storage/Documents/$index",
                    )
                }
            val files = files(definitions)
            val groups =
                definitions.map {
                    DuplicateGroupEntity(
                        it.id,
                        SESSION_ID,
                        it.category,
                        "hash-${it.id}",
                        100L,
                        it.copies,
                        0L,
                        it.title,
                    )
                }
            val members =
                files.map { file ->
                    DuplicateMemberEntity(
                        "member-${file.id}",
                        file.id.substringBeforeLast('-'),
                        file.id,
                        file.id.endsWith("-0"),
                        false,
                        false,
                    )
                }
            scanStore.startSession(SESSION_ID, 1L)
            scanStore.replaceStagedResults(SESSION_ID, files, groups, members)
            scanStore.completeSession(SESSION_ID, 2L, ScanCompletionMetrics(999, 99900L, 108, 52, 0L, 0, 0))
            val state =
                ResultsViewModel(scanStore, savedStateHandle = SavedStateHandle()).state.first { it.groups.size == 52 }
            assertEquals(108, state.duplicateFileCount)
            assertEquals(52, state.duplicateGroupCount)
            assertEquals(5600L, state.availableBytes)
            assertEquals(0L, state.selectedBytes)
            assertEquals(999, state.scannedFileCount)
        }

    private suspend fun seedResults() {
        val definitions =
            listOf(
                GroupDefinition("photo", "Zulu.jpg", "PHOTOS", 2, 500L, listOf(10L, 90L), "/storage/DCIM/Camera"),
                GroupDefinition("video", "Alpha.mp4", "VIDEOS", 4, 300L, listOf(50L, 55L, 58L, 60L), "/storage/Movies"),
                GroupDefinition("audio", "Middle.mp3", "AUDIO", 3, 700L, listOf(20L, 25L, 30L), "/storage/Music"),
                GroupDefinition("docs", "Report.pdf", "DOCUMENTS", 2, 100L, listOf(5L, 100L), "/storage/Work"),
            )
        val files = files(definitions)
        val groups =
            definitions.map { definition ->
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
        val members =
            definitions.flatMap { definition ->
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

    private fun files(definitions: List<GroupDefinition>): List<IndexedFileEntity> =
        definitions.flatMap { definition ->
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
