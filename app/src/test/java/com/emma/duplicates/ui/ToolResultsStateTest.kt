package com.emma.duplicates.ui

import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolResults
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.model.ToolVolume
import com.emma.duplicates.ui.tools.ToolControls
import com.emma.duplicates.ui.tools.ToolScreenState
import com.emma.duplicates.ui.tools.relativeFolder
import com.emma.duplicates.ui.tools.toolVisibleItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultsStateTest {
    @Test
    fun folderLocationIncludesTheFolderItselfInsteadOfOnlyItsParent() {
        val folder = item("photos", 0).copy(canonicalPath = "/storage/DCIM/Google Photos", directory = true)
        val state =
            ToolScreenState(
                CleanupTool.EMPTY_FOLDERS,
                ToolResults(
                    ToolScanSession(
                        "run",
                        CleanupTool.EMPTY_FOLDERS.name,
                        1L,
                        ToolScope(listOf(ToolVolume("primary", "/storage"))).encode(),
                    ),
                    listOf(folder),
                ),
            )
        assertEquals("DCIM/Google Photos", relativeFolder(folder, state))
        assertEquals("DCIM", relativeFolder(folder.copy(directory = false), state))
    }

    @Test
    fun newScreenshotAndDownloadViewsIncludeRecentAndUnknownDates() {
        val started = 1_800_000_000_000L
        for (tool in listOf(CleanupTool.SCREENSHOTS, CleanupTool.OLD_DOWNLOADS)) {
            val results =
                ToolResults(
                    ToolScanSession("run", tool.name, started, ToolScope(emptyList()).encode()),
                    listOf(item("recent", 584000).copy(modifiedAt = started - 1000), item("unknown", 392000)),
                )
            assertEquals(
                setOf("recent", "unknown"),
                toolVisibleItems(tool, results, ToolControls()).map { it.id }.toSet(),
            )
        }
    }

    @Test
    fun filteringKeepsSelectedItemsAndStableSizeTies() {
        val small = item("b", 100_000_001).copy(selected = true)
        val big = item("a", 600_000_000)
        val equal = item("c", 600_000_000)
        val results =
            ToolResults(
                ToolScanSession("run", CleanupTool.LARGE_FILES.name, 100L, ToolScope(emptyList()).encode()),
                listOf(small, equal, big),
            )
        assertEquals(
            listOf(
                "a",
                "c",
            ),
            toolVisibleItems(CleanupTool.LARGE_FILES, results, ToolControls(minimumBytes = 500_000_000)).map {
                it.id
            },
        )
        assertTrue(results.items.first().selected)
        assertEquals(
            listOf(
                "b",
                "a",
                "c",
            ),
            toolVisibleItems(CleanupTool.LARGE_FILES, results, ToolControls(sort = "smallest")).map {
                it.id
            },
        )
    }

    @Test
    fun hiddenSelectionCountProcessesLargeResultsWithoutRepeatedFullTraversals() {
        val entries =
            List(2_000) { index ->
                item(index.toString(), if (index < 1_000) 600_000_000L else 200_000_000L).copy(selected = true)
            }
        var reads = 0
        val measured =
            object : AbstractList<ToolItemEntity>() {
                override val size: Int get() = entries.size

                override fun get(index: Int): ToolItemEntity {
                    reads++
                    return entries[index]
                }
            }
        val state =
            com.emma.duplicates.ui.tools.ToolScreenState(
                tool = CleanupTool.LARGE_FILES,
                results =
                    ToolResults(
                        ToolScanSession("run", CleanupTool.LARGE_FILES.name, 100L, ToolScope(emptyList()).encode()),
                        measured,
                    ),
                controls = ToolControls(minimumBytes = 500_000_000L),
            )
        assertEquals(1_000, state.hiddenCount)
        assertTrue("Expected bounded result traversal, observed $reads reads", reads <= entries.size * 10)
    }

    private fun item(
        id: String,
        size: Long,
    ) = ToolItemEntity("run", id, "primary", "/$id", id, size, 0L, "DOCUMENTS")
}
