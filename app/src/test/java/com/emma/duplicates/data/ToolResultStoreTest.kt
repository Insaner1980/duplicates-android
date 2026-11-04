package com.emma.duplicates.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.model.ToolVolume
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ToolResultStoreTest {
    private lateinit var database: DuplicatesDatabase
    private lateinit var store: ToolResultStore

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    DuplicatesDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        store = ToolResultStore(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun screenshotMonthSelectionChangesOnlyRequestedValidItems() =
        runTest {
            complete(
                "screens",
                CleanupTool.SCREENSHOTS,
                listOf(
                    item("screens", "one"),
                    item("screens", "two"),
                    item("screens", "hidden"),
                    item("screens", "invalid").copy(valid = false),
                    item("screens", "keeper").copy(keeper = true),
                ),
            )
            store.setSelected("screens", "hidden", true)
            store.setScreenshotSelection("screens", setOf("one", "two", "invalid", "keeper", "foreign"), true)
            assertEquals(
                setOf("one", "two", "hidden"),
                store.dao
                    .items("screens")
                    .filter { it.selected }
                    .map { it.id }
                    .toSet(),
            )
            store.setScreenshotSelection("screens", setOf("one", "two"), false)
            assertEquals(
                listOf("hidden"),
                store.dao
                    .items("screens")
                    .filter { it.selected }
                    .map { it.id },
            )
        }

    @Test
    fun screenshotBatchSelectionRejectsInactiveSessionAndPendingOperation() =
        runTest {
            complete("screens", CleanupTool.SCREENSHOTS, listOf(item("screens", "one")))
            val session = store.dao.session("screens")!!
            store.dao.updateSession(session.copy(active = false))
            store.setScreenshotSelection("screens", setOf("one"), true)
            assertFalse(
                store.dao
                    .items("screens")
                    .single()
                    .selected,
            )
            store.dao.updateSession(session)
            store.dao.insertOperation(
                com.emma.duplicates.core.database
                    .CleanupOperation("pending", "screens", CleanupTool.SCREENSHOTS.name, 1),
            )
            store.setScreenshotSelection("screens", setOf("one"), true)
            assertFalse(
                store.dao
                    .items("screens")
                    .single()
                    .selected,
            )
        }

    @Test
    fun canceledReplacementKeepsSelectionAndOtherToolsAreIsolated() =
        runTest {
            complete("old", CleanupTool.LARGE_FILES, listOf(item("old", "first")))
            store.setSelected("old", "first", true)
            complete("downloads", CleanupTool.OLD_DOWNLOADS, listOf(item("downloads", "second")))
            store.start(session("replacement", CleanupTool.LARGE_FILES))
            store.finish("replacement", "canceled", null)
            val result = store.active(CleanupTool.LARGE_FILES).first()!!
            assertEquals("old", result.session.id)
            assertTrue(result.items.single().selected)
            assertEquals(
                "downloads",
                store
                    .active(CleanupTool.OLD_DOWNLOADS)
                    .first()!!
                    .session.id,
            )
        }

    @Test
    fun restartCannotReplaceCompletedResultsOrBypassOperationGuards() =
        runTest {
            complete("saved", CleanupTool.LARGE_FILES, listOf(item("saved", "first")))
            store.setSelected("saved", "first", true)
            assertTrue(runCatching { store.start(session("saved", CleanupTool.LARGE_FILES)) }.isFailure)
            val attempt = session("restart", CleanupTool.OLD_DOWNLOADS)
            store.start(attempt)
            assertTrue(runCatching { store.start(session("restart", CleanupTool.LARGE_FILES)) }.isFailure)
            assertTrue(runCatching { store.start(session("other", CleanupTool.LARGE_FILES)) }.isFailure)
            database.toolDao().insertOperation(
                com.emma.duplicates.core.database.CleanupOperation(
                    "pending",
                    "saved",
                    CleanupTool.LARGE_FILES.name,
                    1L,
                ),
            )
            assertTrue(runCatching { store.start(attempt) }.isFailure)
            assertEquals(attempt, store.dao.session("restart"))
            val preserved = store.active(CleanupTool.LARGE_FILES).first()!!
            assertTrue(preserved.items.single().selected)
        }

    @Test
    fun replacingResultsPrunesOnlyTheSameToolAndStartsUnselected() =
        runTest {
            complete("old", CleanupTool.LARGE_FILES, listOf(item("old", "first")))
            store.setSelected("old", "first", true)
            complete("other", CleanupTool.SCREENSHOTS, listOf(item("other", "photo")))
            complete("new", CleanupTool.LARGE_FILES, listOf(item("new", "first")))
            assertFalse(
                store
                    .active(CleanupTool.LARGE_FILES)
                    .first()!!
                    .items
                    .single()
                    .selected,
            )
            assertEquals(null, database.toolDao().session("old"))
            assertEquals(
                "other",
                store
                    .active(CleanupTool.SCREENSHOTS)
                    .first()!!
                    .session.id,
            )
        }

    @Test
    fun emptyParentSelectsDescendantsAndDeselectingChildClearsDependentParents() =
        runTest {
            complete(
                "folders",
                CleanupTool.EMPTY_FOLDERS,
                listOf(
                    item("folders", "parent", "/storage/test/parent", directory = true),
                    item("folders", "child", "/storage/test/parent/child", directory = true),
                    item("folders", "sibling", "/storage/test/parent/sibling", directory = true),
                ),
            )
            store.setSelected("folders", "parent", true)
            assertEquals(
                3,
                store
                    .active(CleanupTool.EMPTY_FOLDERS)
                    .first()!!
                    .items
                    .count { it.selected },
            )
            store.setSelected("folders", "child", false)
            assertEquals(
                listOf("sibling"),
                store
                    .active(CleanupTool.EMPTY_FOLDERS)
                    .first()!!
                    .items
                    .filter {
                        it.selected
                    }.map { it.id },
            )
        }

    @Test
    fun keeperCannotBeSelectedAndChangingKeeperClearsItsRemovalSelection() =
        runTest {
            complete(
                "photos",
                CleanupTool.SIMILAR_PHOTOS,
                listOf(
                    item("photos", "first").copy(groupId = "group", keeper = true),
                    item("photos", "second").copy(groupId = "group"),
                ),
            )
            store.setSelected("photos", "first", true)
            store.setSelected("photos", "second", true)
            assertEquals(
                listOf("second"),
                store
                    .active(CleanupTool.SIMILAR_PHOTOS)
                    .first()!!
                    .items
                    .filter {
                        it.selected
                    }.map { it.id },
            )
            store.keep("photos", "second")
            val items = store.active(CleanupTool.SIMILAR_PHOTOS).first()!!.items
            assertTrue(items.none { it.selected })
            assertEquals("second", items.single { it.keeper }.id)
        }

    @Test
    fun terminalAttemptsRetainOnlyLatestStatusActiveResultsAndJournalReferences() =
        runTest {
            complete("active", CleanupTool.LARGE_FILES, listOf(item("active", "first")))
            store.setSelected("active", "first", true)
            complete("other", CleanupTool.SCREENSHOTS, listOf(item("other", "photo")))
            store.start(session("failed", CleanupTool.LARGE_FILES).copy(startedAt = 11L))
            store.finish("failed", "failed", "unavailable")
            store.start(session("canceled", CleanupTool.LARGE_FILES).copy(startedAt = 12L))
            store.finish("canceled", "canceled", null)
            assertEquals(null, database.toolDao().session("failed"))
            store.start(session("latest", CleanupTool.LARGE_FILES).copy(startedAt = 13L))
            database.toolDao().insertSession(session("referenced", CleanupTool.LARGE_FILES).copy(status = "failed"))
            database.toolDao().insertOperation(
                com.emma.duplicates.core.database.CleanupOperation(
                    "pending",
                    "referenced",
                    CleanupTool.LARGE_FILES.name,
                    1L,
                ),
            )
            store.finish("latest", "failed", "unavailable")
            assertEquals(null, database.toolDao().session("canceled"))
            assertEquals("latest", store.latest(CleanupTool.LARGE_FILES).first()?.id)
            assertEquals("failed", database.toolDao().session("referenced")?.status)
            val active = requireNotNull(store.active(CleanupTool.LARGE_FILES).first())
            assertEquals("active", active.session.id)
            assertTrue(active.items.single().selected)
            assertEquals(
                "other",
                store
                    .active(CleanupTool.SCREENSHOTS)
                    .first()
                    ?.session
                    ?.id,
            )
        }

    private suspend fun complete(
        id: String,
        tool: CleanupTool,
        items: List<ToolItemEntity>,
    ) {
        store.start(session(id, tool))
        store.complete(id, items, completedAt = 20L)
    }

    private fun session(
        id: String,
        tool: CleanupTool,
    ) = ToolScanSession(
        id = id,
        tool = tool.name,
        startedAt = 10L,
        scope = ToolScope(volumes = listOf(ToolVolume("primary", "/storage/test"))).encode(),
    )

    private fun item(
        session: String,
        id: String,
        path: String = "/storage/test/$id",
        directory: Boolean = false,
    ) = ToolItemEntity(
        sessionId = session,
        id = id,
        volume = "primary",
        canonicalPath = path,
        name = id,
        sizeBytes = 101_000_000L,
        modifiedAt = 1L,
        directory = directory,
        category = "DOCUMENTS",
    )
}
