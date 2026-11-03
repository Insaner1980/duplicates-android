package com.emma.duplicates.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolResults
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.model.ToolVolume
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.data.UnusedApp
import com.emma.duplicates.data.UnusedAppsResult
import com.emma.duplicates.data.UsageAccessState
import com.emma.duplicates.ui.tools.RootNavigationBar
import com.emma.duplicates.ui.tools.RootTab
import com.emma.duplicates.ui.tools.StorageUsageScreen
import com.emma.duplicates.ui.tools.ToolResultsScreen
import com.emma.duplicates.ui.tools.ToolScreenState
import com.emma.duplicates.ui.tools.UnusedAppsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolsLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun ordinaryWidthMatrix() = matrix("411")

    @Test
    @Config(qualifiers = "w320dp-h800dp-mdpi")
    fun compactWidthMatrix() = matrix("320")

    @Test
    @Config(qualifiers = "w640dp-h320dp-mdpi")
    fun shortLandscapeMatrix() = matrix("landscape")

    private val usageState = mutableStateOf(UsageAccessState.RESULTS)
    private var previews = 0
    private var selections = 0

    @get:Rule val temporary = org.junit.rules.TemporaryFolder()
    private val fixtureRoot by lazy { temporary.newFolder("volume") }

    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    private fun matrix(width: String) {
        val scale = mutableFloatStateOf(1f)
        val dark = mutableStateOf(true)
        val current = mutableStateOf(fixture(CleanupTool.LARGE_FILES))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale.floatValue),
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides
                    AsyncImagePreviewHandler { request ->
                        (request.data as? File)?.let {
                            android.graphics.BitmapFactory
                                .decodeFile(it.path)
                                ?.asImage()
                        } ?: coil3.ColorImage(android.graphics.Color.TRANSPARENT)
                    },
            ) {
                DuplicatesTheme(dark.value) {
                    androidx.compose.material3.Surface(
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    ) {
                        FixtureScreen(current.value)
                    }
                }
            }
        }
        val variants = listOf(true, false).flatMap { theme -> listOf(1f, 2f).map { theme to it } }
        for ((theme, font) in variants) {
            for (tool in CleanupTool.entries) {
                composeRule.runOnIdle {
                    dark.value = theme
                    scale.floatValue = font
                    current.value = fixture(tool)
                }
                if (tool == CleanupTool.DUPLICATES) {
                    composeRule.onNodeWithText("Unused apps").performScrollTo().assertIsDisplayed()
                    composeRule.onNodeWithContentDescription("Settings").performScrollTo()
                } else {
                    verifyControls(current.value, width)
                }
                if (tool == CleanupTool.SCREENSHOTS && width == "411" && font == 1f) {
                    composeRule
                        .onNodeWithContentDescription(
                            "Preview ${current.value.visibleItems.last().name}",
                        ).assertIsDisplayed()
                }
                val name = if (tool == CleanupTool.DUPLICATES) "home" else tool.name.lowercase()
                capture("$name-$width-${font.toInt()}-${if (theme) "dark" else "light"}")
                verifyStates(current)
                if (tool == CleanupTool.EMPTY_FOLDERS || tool == CleanupTool.LARGE_FILES) verifyConfirmation(current)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun FixtureScreen(state: ToolScreenState) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (state.tool) {
                    CleanupTool.DUPLICATES -> {
                        com.emma.duplicates.ui.tools.DashboardScreen(
                            if (state.hasAccess) {
                                com.emma.duplicates.ui.home.HomeUiState
                                    .Ready("Internal storage", "", true)
                            } else {
                                com.emma.duplicates.ui.home.HomeUiState.PermissionRequired
                            },
                            com.emma.duplicates.ui.home.HomeConfiguration(
                                totalBytes = 128_000_000_000L,
                                freeBytes = 41_600_000_000L,
                            ),
                            {},
                            {},
                            {},
                        )
                    }

                    CleanupTool.STORAGE_USAGE -> {
                        StorageUsageScreen(state, {}, {}, {}, {}, {})
                    }

                    CleanupTool.UNUSED_APPS -> {
                        UnusedAppsFixture()
                    }

                    else -> {
                        ToolResultsScreen(
                            state,
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            { _, _ -> selections++ },
                            { previews++ },
                            {},
                            {},
                            {},
                            {},
                        )
                    }
                }
            }
            RootNavigationBar(if (state.tool == CleanupTool.DUPLICATES) RootTab.HOME else RootTab.TOOLS, {})
        }
    }

    @androidx.compose.runtime.Composable
    private fun UnusedAppsFixture() {
        val apps =
            if (usageState.value == UsageAccessState.RESULTS) {
                listOf(
                    UnusedApp("fixture", "Word game and puzzles", 1_700_000_000_000, 420_000_000),
                    UnusedApp("photos", "Photo collage", 1_690_000_000_000, 186_000_000),
                    UnusedApp("travel", "Travel guide with downloaded city maps", 1_680_000_000_000, 92_000_000),
                )
            } else {
                emptyList()
            }
        UnusedAppsScreen(UnusedAppsResult(usageState.value, apps), 90, {}, {}, {}, {}, {})
    }

    private fun verifyStates(current: androidx.compose.runtime.MutableState<ToolScreenState>) {
        val base = current.value
        if (base.tool == CleanupTool.UNUSED_APPS) {
            verifyUsageStates()
        } else if (base.tool == CleanupTool.DUPLICATES) {
            composeRule.runOnIdle { current.value = base.copy(hasAccess = false) }
            composeRule.onNodeWithText("Grant storage access").performScrollTo().assertIsDisplayed()
            assertReadableText()
        } else {
            val variants =
                listOf(
                    "Scanning" to base.copy(running = true),
                    "Scan failed" to base.copy(latest = base.results?.session?.copy(status = ScanStatuses.FAILED)),
                    "Storage access required" to base.copy(hasAccess = false),
                ) +
                    if (base.tool !=
                        CleanupTool.STORAGE_USAGE
                    ) {
                        listOf("No matching items" to base.copy(results = null))
                    } else {
                        emptyList()
                    }
            for ((label, variant) in variants) {
                composeRule.runOnIdle { current.value = variant }
                if (base.tool == CleanupTool.STORAGE_USAGE) {
                    composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                } else {
                    composeRule.onNodeWithTag("tool-results").performScrollToNode(hasText(label))
                    composeRule.onNodeWithText(label).assertIsDisplayed()
                }
                assertReadableText()
            }
        }
        composeRule.runOnIdle { current.value = base }
    }

    private fun verifyConfirmation(current: androidx.compose.runtime.MutableState<ToolScreenState>) {
        val base = current.value
        val results = requireNotNull(base.results)
        val selected = results.items.filter { it.selected }
        val directories = base.tool == CleanupTool.EMPTY_FOLDERS
        val confirmation =
            if (directories) {
                base.copy(
                    folderConfirmation =
                        com.emma.duplicates.domain.deletion.EmptyFolderBatch(
                            results.session,
                            selected,
                            emptyMap(),
                        ),
                )
            } else {
                base.copy(
                    fileConfirmation =
                        com.emma.duplicates.domain.deletion.CleanupBatch(
                            results.session,
                            selected,
                            selected.map {
                                com.emma.duplicates.domain.deletion
                                    .VerifiedCleanupFile(it, "fixture", null)
                            },
                            emptyList(),
                            1,
                        ),
                )
            }
        composeRule.runOnIdle { current.value = confirmation }
        val dialog = hasAnyAncestor(isDialog())
        composeRule.onNode(hasText("Cancel") and dialog).assertIsDisplayed()
        composeRule
            .onNode(
                hasText(if (directories) "Delete selected" else "Move to recovery") and dialog,
            ).assertIsDisplayed()
        assertReadableText()
        composeRule.runOnIdle { current.value = base }
    }

    private fun verifyUsageStates() {
        val variants =
            listOf(
                UsageAccessState.NOT_GRANTED to "Usage access required",
                UsageAccessState.REVOKED to "Usage access revoked",
                UsageAccessState.UNAVAILABLE to "Usage data unavailable",
                UsageAccessState.EMPTY to "No eligible apps",
            )
        for ((state, label) in variants) {
            composeRule.runOnIdle { usageState.value = state }
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            assertReadableText()
        }
        composeRule.runOnIdle { usageState.value = UsageAccessState.RESULTS }
    }

    private fun assertReadableText() {
        val nodes =
            composeRule
                .onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                        androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
                    ),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        composeRule.runOnIdle {
            nodes.forEach { node ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                layouts.forEach {
                    // Paragraph width may retain the available width after Text shrinks to its glyphs.
                    // Check the rendered lines, allowing only integer pixel rounding.
                    val clippedLine =
                        (0 until it.lineCount).any { line ->
                            it.getLineLeft(line) < -1f || it.getLineRight(line) > it.size.width + 1f ||
                                it.isLineEllipsized(line)
                        }
                    assertFalse("Clipped text: ${it.layoutInput.text}", it.didOverflowHeight || clippedLine)
                }
            }
        }
    }

    private fun verifyControls(
        state: ToolScreenState,
        width: String,
    ) {
        composeRule.onNodeWithContentDescription("Home tab").assertIsDisplayed()
        when (state.tool) {
            CleanupTool.UNUSED_APPS -> {
                composeRule.onAllNodesWithText("Open app details")[0].performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithContentDescription("Back").performScrollTo()
            }

            CleanupTool.STORAGE_USAGE -> {
                composeRule.onNodeWithText("Largest folders").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithContentDescription("Back").performScrollTo()
            }

            else -> {
                verifySelection(state, width)
            }
        }
    }

    private fun verifySelection(
        state: ToolScreenState,
        width: String,
    ) {
        if (width == "landscape") {
            composeRule.onNodeWithTag("tool-results").performScrollToNode(hasTestTag("tool-cleanup"))
        }
        composeRule.onNodeWithTag("tool-cleanup").assertIsDisplayed()
        val checkbox = "Select ${state.visibleItems.first { !it.keeper }.name}"
        composeRule.onNodeWithTag("tool-results").performScrollToNode(hasContentDescription(checkbox))
        val previousPreviews = previews
        val previousSelections = selections
        composeRule.onNodeWithContentDescription(checkbox).assertIsDisplayed().performClick()
        assertEquals(previousSelections + 1, selections)
        assertEquals(previousPreviews, previews)
        if (state.tool == CleanupTool.SIMILAR_PHOTOS) assertFalse(state.visibleItems.first { it.keeper }.selected)
        composeRule.onNodeWithTag("tool-results").performScrollToNode(hasContentDescription("Back"))
    }

    private fun capture(name: String) {
        val file = File("build/outputs/storage-cleanup/visual/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use {
            composeRule
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertReadableText()
    }

    private fun photo(
        file: File,
        index: Int,
    ) {
        requireNotNull(file.parentFile).mkdirs()
        val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = android.graphics.Color.rgb(185, 210, 223)
        canvas.drawPaint(paint)
        paint.color = android.graphics.Color.rgb(248, 184, 98)
        canvas.drawCircle(320f + index * 4, 104f, 48f, paint)
        paint.color = android.graphics.Color.rgb(71, 117, 127)
        canvas.drawRect(0f, 170f, 480f, 320f, paint)
        paint.color = android.graphics.Color.rgb(49, 77, 63)
        canvas.drawOval(-80f, 170f, 300f, 420f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun fixture(tool: CleanupTool): ToolScreenState {
        val root = fixtureRoot.path.replace('\\', '/')
        val scope = ToolScope(listOf(ToolVolume("primary", root)))
        val items = fixtureItems(tool, root)
        return ToolScreenState(
            tool,
            ToolResults(
                ToolScanSession(
                    "fixture",
                    tool.name,
                    1_800_000_000_000L,
                    scope.encode(),
                    ScanStatuses.COMPLETED,
                    active = true,
                    totalBytes = 128_000_000_000L,
                    freeBytes = 41_600_000_000L,
                ),
                items,
            ),
            hasAccess = true,
            volumes =
                listOf(
                    AvailableStorageVolume(
                        "primary",
                        "Internal storage",
                        File(root),
                        "external_primary",
                        true,
                        false,
                        128_000_000_000L,
                        41_600_000_000L,
                    ),
                ),
        )
    }

    private fun fixtureItems(
        tool: CleanupTool,
        root: String,
    ): List<ToolItemEntity> {
        val names = fixtureNames(tool)
        val photos = tool == CleanupTool.SIMILAR_PHOTOS || tool == CleanupTool.SCREENSHOTS
        val items =
            names.mapIndexed { index, name ->
                val folder = fixtureFolder(tool, index, photos)
                if (photos) photo(File(root, "$folder/$name"), index)
                ToolItemEntity(
                    "fixture",
                    "$index",
                    "primary",
                    "$root/$folder/$name",
                    name,
                    if (photos) 4_200_000L - index * 200_000L else 2_400_000_000L - index * 400_000_000L,
                    1_700_000_000_000L,
                    when (name.substringAfterLast('.')) {
                        "jpg", "png" -> "PHOTOS"
                        "mp4" -> "VIDEOS"
                        "mp3" -> "AUDIO"
                        else -> "DOCUMENTS"
                    },
                    directory = tool == CleanupTool.EMPTY_FOLDERS,
                    selected = index == 1,
                    groupId = if (tool == CleanupTool.SIMILAR_PHOTOS) "group" else null,
                    keeper = tool == CleanupTool.SIMILAR_PHOTOS && index == 0,
                    allocatedBytes = 1_000_000_000L,
                )
            }
        return items
    }

    private fun fixtureNames(tool: CleanupTool): List<String> {
        val names =
            when (tool) {
                CleanupTool.SCREENSHOTS -> {
                    listOf("Recipe.png", "Timetable.png", "Receipt.png", "Map.png", "Shop.png", "Notes.png")
                }

                CleanupTool.STORAGE_USAGE -> {
                    listOf("movie.mp4", "photo.jpg", "music.mp3", "archive.zip")
                }

                CleanupTool.EMPTY_FOLDERS -> {
                    listOf("Old photos", "Export", "Temp", "Drafts", "2024")
                }

                CleanupTool.LARGE_FILES -> {
                    listOf("Holiday video.mp4", "Backup.zip", "Concert.mp4", "Maps.zip")
                }

                CleanupTool.OLD_DOWNLOADS -> {
                    listOf(
                        "Installer.apk",
                        "Archive.zip",
                        "Travel tickets.pdf",
                        "Instructions.pdf",
                    )
                }

                else -> {
                    listOf("Sunset.jpg", "Sunset copy.jpg", "Shore.jpg", "Lake.jpg")
                }
            }
        return names
    }

    private fun fixtureFolder(
        tool: CleanupTool,
        index: Int,
        photos: Boolean,
    ): String =
        when {
            tool == CleanupTool.STORAGE_USAGE -> listOf("DCIM", "Pictures", "Music", "Download")[index]
            photos -> "Pictures/Screenshots"
            else -> "Download"
        }
}
