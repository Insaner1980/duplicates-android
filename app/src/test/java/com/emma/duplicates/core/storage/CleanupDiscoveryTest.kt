package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolExclusion
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.model.ToolVolume
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.ZoneId
import java.time.ZonedDateTime

class CleanupDiscoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun emptyTreesIncludeLeavesButNeverFilesAnchorsOrExcludedDescendants() =
        runTest {
            val root = temporary.newFolder("volume")
            root.resolve("tree/leaf").mkdirs()
            root.resolve("with-zero").mkdirs()
            root.resolve("with-zero/.nomedia").createNewFile()
            root.resolve("excluded/child").mkdirs()
            root.resolve("protected/child").mkdirs()
            val scope = scope(root).copy(exclusions = listOf(ToolExclusion(root.resolve("excluded/child").path, true)))
            val result = EmptyFolderScanner().scan("run", scope, setOf(root.resolve("protected/original.txt").path))
            assertEquals(setOf("leaf", "tree", "child"), result.items.map { it.name }.toSet())
            assertFalse(result.items.any { it.canonicalPath == root.path || it.name == "protected" })
            assertTrue(
                EmptyFolderScanner()
                    .scan(
                        "file-only",
                        scope.copy(includedPaths = listOf(root.resolve("with-zero/.nomedia").path)),
                    ).items
                    .isEmpty(),
            )
            assertEquals(
                listOf(
                    "leaf",
                ),
                EmptyFolderScanner()
                    .scan(
                        "anchor",
                        scope.copy(includedPaths = listOf(root.resolve("tree").path)),
                    ).items
                    .map {
                        it.name
                    },
            )
        }

    @Test
    fun deniedEnumerationBlocksItsParentAndLeavesIndependentEmptyBranches() =
        runTest {
            val root = temporary.newFolder("volume")
            root.resolve("blocked/child").mkdirs()
            root.resolve("good").mkdirs()
            val scanner = EmptyFolderScanner(listChildren = { if (it.name == "blocked") null else it.listFiles() })
            val result = scanner.scan("run", scope(root))
            assertEquals(listOf("good"), result.items.map { it.name })
            assertFalse(result.complete)
        }

    @Test
    fun ordinaryDiscoveryIncludesUniqueZeroByteDownloadsAndUsesRealLocations() =
        runTest {
            val root = temporary.newFolder("volume")
            root.resolve("Download").mkdirs()
            root.resolve("Pictures/Screenshots").mkdirs()
            root.resolve("DCIM").mkdirs()
            root.resolve("Download/empty.txt").createNewFile()
            root.resolve("Download/a.apk").writeText("apk")
            root.resolve("elsewhere.apk").writeText("apk")
            root.resolve("Pictures/Screenshots/a.png").writeText("image")
            root.resolve("Pictures/Screenshots/readme.txt").writeText("text")
            root.resolve("DCIM/screenshot.png").writeText("camera")
            val scanner = ToolFileScanner(FileDiscovery())
            assertEquals(
                setOf(
                    "empty.txt",
                    "a.apk",
                ),
                scanner
                    .scan("downloads", CleanupTool.OLD_DOWNLOADS, scope(root))
                    .items
                    .map {
                        it.name
                    }.toSet(),
            )
            assertEquals(
                listOf("a.png"),
                scanner.scan("shots", CleanupTool.SCREENSHOTS, scope(root)).items.map { it.name },
            )
            assertTrue(
                scanner
                    .scan(
                        "limited",
                        CleanupTool.OLD_DOWNLOADS,
                        scope(root).copy(includedPaths = listOf(root.resolve("DCIM").path)),
                    ).items
                    .isEmpty(),
            )
        }

    @Test
    fun largeFilesUseStrictDecimalThresholdAndStableOrderingWithoutReadingContents() =
        runTest {
            val root = temporary.newFolder("volume")
            listOf(
                "boundary" to 100_000_000L,
                "second" to 100_000_001L,
                "first" to 100_000_001L,
            ).forEach { (name, size) ->
                RandomAccessFile(root.resolve(name), "rw").use { it.setLength(size) }
            }
            val items = ToolFileScanner(FileDiscovery()).scan("run", CleanupTool.LARGE_FILES, scope(root)).items
            assertEquals(listOf("first", "second"), items.map { it.name })
            assertTrue(items.all { !it.selected })
        }

    @Test
    fun ageCutoffUsesCalendarMonthsAndRejectsBoundaryUnknownAndFutureDates() {
        val zone = ZoneId.of("Europe/Helsinki")
        val now = ZonedDateTime.of(2024, 8, 31, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val cutoff = ZonedDateTime.of(2024, 2, 29, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(isOlderThanMonths(cutoff - 1, 6, now, zone))
        assertFalse(isOlderThanMonths(cutoff, 6, now, zone))
        assertFalse(isOlderThanMonths(0L, 6, now, zone))
        assertFalse(isOlderThanMonths(now + 1, 6, now, zone))
        val spring = ZonedDateTime.of(2026, 9, 29, 3, 30, 0, 0, zone)
        assertFalse(
            isOlderThanMonths(
                spring.minusMonths(6).toInstant().toEpochMilli(),
                6,
                spring.toInstant().toEpochMilli(),
                zone,
            ),
        )
    }

    private fun scope(root: File) =
        ToolScope(listOf(ToolVolume("primary", root.canonicalPath)), ignoreSystemFolders = false)
}
