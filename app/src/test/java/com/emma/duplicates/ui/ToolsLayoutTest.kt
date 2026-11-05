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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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

    @Test
    fun homeGroupsSevenToolsAndOpensStorageFromTheCard() {
        var opened: CleanupTool? = null
        composeRule.setContent {
            DuplicatesTheme(false) {
                com.emma.duplicates.ui.tools.DashboardScreen(
                    com.emma.duplicates.ui.home.HomeUiState
                        .Ready("Internal storage", "", true),
                    com.emma.duplicates.ui.home.HomeConfiguration(
                        totalBytes = 100_000_000_000,
                        freeBytes = 40_000_000_000,
                    ),
                    {},
                    {},
                    { opened = it },
                )
            }
        }
        composeRule.onNodeWithText("Tools").assertDoesNotExist()
        composeRule.onNodeWithText("View storage usage").assertDoesNotExist()
        val tools =
            listOf(
                "DUPLICATES",
                "EMPTY_FOLDERS",
                "LARGE_FILES",
                "SIMILAR_PHOTOS",
                "SCREENSHOTS",
                "OLD_DOWNLOADS",
                "UNUSED_APPS",
            )
        val tops =
            tools.map {
                composeRule
                    .onNodeWithTag(
                        "tool-$it",
                    ).assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        org.junit.Assert.assertTrue(tops.zipWithNext().all { (first, next) -> first < next })
        composeRule.onNodeWithText("Storage").performClick()
        assertEquals(CleanupTool.STORAGE_USAGE, opened)
    }

    @Test fun ordinaryWidthMatrix() = matrix("411")

    @Test
    fun allFileToolsShowSelectionCountAndBytesWithoutScopeOrSkippedRows() {
        val state = mutableStateOf(fixture(CleanupTool.LARGE_FILES))
        composeRule.setContent { DuplicatesTheme { FixtureScreen(state.value) } }
        for (tool in listOf(
            CleanupTool.LARGE_FILES,
            CleanupTool.SIMILAR_PHOTOS,
            CleanupTool.SCREENSHOTS,
            CleanupTool.OLD_DOWNLOADS,
        )) {
            composeRule.runOnIdle { state.value = fixture(tool) }
            composeRule.onNodeWithText("1 selected").assertIsDisplayed()
            val expected = if (tool in listOf(CleanupTool.SIMILAR_PHOTOS, CleanupTool.SCREENSHOTS)) "4 MB" else "2 GB"
            val footer = hasAnyAncestor(hasTestTag("tool-footer"))
            composeRule.onNode(hasText(expected) and footer).assertIsDisplayed()
            composeRule.onNodeWithText("Internal storage").assertDoesNotExist()
        }
    }

    @Test
    fun filePreviewDoesNotSelectItem() {
        val state = fixture(CleanupTool.LARGE_FILES)
        composeRule.setContent { DuplicatesTheme { FixtureScreen(state) } }
        composeRule.onNodeWithContentDescription("Preview ${state.visibleItems.first().name}").performClick()
        assertEquals(1, previews)
        assertEquals(0, selections)
    }

    @Test
    fun protectedPhotoShowsKeptOutcomeBelowTheImageWithoutDeleteCheckbox() {
        val base = fixture(CleanupTool.SIMILAR_PHOTOS)
        val state = base.copy(results = base.results?.copy(items = base.visibleItems.take(2)))
        val keeper = state.visibleItems.first { it.keeper }
        composeRule.setContent { DuplicatesTheme { FixtureScreen(state) } }
        val label =
            composeRule
                .onNodeWithText("Will be kept")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val preview =
            composeRule
                .onNodeWithContentDescription(
                    "Preview ${keeper.name}",
                ).fetchSemanticsNode()
                .boundsInRoot
        org.junit.Assert.assertTrue(label.top >= preview.bottom)
        composeRule.onNodeWithContentDescription("Preview ${keeper.name}").assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                "Will be kept",
            ),
        )
        composeRule.onNodeWithContentDescription("Select ${keeper.name}").assertDoesNotExist()
    }

    @Test
    fun monthCheckboxReflectsPartialSelectionAndDoesNotSelectAnotherMonth() {
        val base = fixture(CleanupTool.SCREENSHOTS)
        val results = requireNotNull(base.results)
        val state =
            base.copy(
                results =
                    results.copy(
                        items =
                            results.items.map {
                                if (it.id == "5") it.copy(modifiedAt = 1_696_118_400_000L) else it
                            },
                    ),
            )
        composeRule.setContent { DuplicatesTheme { FixtureScreen(state) } }
        monthCheckbox(ToggleableState.Indeterminate).performClick()
        assertEquals(listOf(setOf("0", "1", "2", "3", "4") to true), monthSelections)
        assertEquals(0, previews)
    }

    @Test
    fun screenshotPreviewAndCheckboxRemainIndependent() {
        val state = fixture(CleanupTool.SCREENSHOTS)
        val item = state.visibleItems.first()
        composeRule.setContent { DuplicatesTheme { FixtureScreen(state) } }
        val preview = composeRule.onNodeWithContentDescription("Preview ${item.name}")
        val checkbox = composeRule.onNodeWithContentDescription("Select ${item.name}")
        org.junit.Assert.assertTrue(
            checkbox.fetchSemanticsNode().boundsInRoot.top >= preview.fetchSemanticsNode().boundsInRoot.bottom,
        )
        preview.performClick()
        assertEquals(1, previews)
        assertEquals(0, selections)
        checkbox.performClick()
        assertEquals(1, previews)
        assertEquals(1, selections)
    }

    @Test
    fun unusedAppRowOpensCorrectPackageOnce() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            DuplicatesTheme {
                UnusedAppsScreen(
                    UnusedAppsResult(
                        UsageAccessState.RESULTS,
                        listOf(UnusedApp("example.app", "Example", 1_700_000_000_000, null)),
                    ),
                    90,
                    {},
                    {},
                    {},
                    {},
                    { opened += it },
                )
            }
        }
        composeRule.onNodeWithText("Example").performClick()
        assertEquals(listOf("example.app"), opened)
        composeRule.onNodeWithText("Open app details").assertDoesNotExist()
        composeRule.onNodeWithText("—").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h800dp-mdpi")
    fun compactWidthMatrix() = matrix("320")

    @Test
    @Config(qualifiers = "w640dp-h320dp-mdpi")
    fun shortLandscapeMatrix() = matrix("landscape")

    @Test
    @Config(qualifiers = "w320dp-h800dp-mdpi")
    fun partialFolderSizesRemainUnknownInBothThemesAndFontSizes() {
        val scale = mutableFloatStateOf(1f)
        val dark = mutableStateOf(true)
        val original = fixture(CleanupTool.STORAGE_USAGE)
        val results = requireNotNull(original.results)
        val state = original.copy(results = results.copy(session = results.session.copy(complete = false)))
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.floatValue)) {
                DuplicatesTheme(dark.value) {
                    StorageUsageScreen(state, {}, {}, {}, {}, {})
                }
            }
        }
        for ((theme, font) in layoutAppearances()) {
            composeRule.runOnIdle {
                dark.value = theme
                scale.floatValue = font
            }
            composeRule.onNode(hasText("DCIM") and hasText("Unknown")).performScrollTo().assertIsDisplayed()
            assertReadableText()
        }
    }

    @Test
    fun longFilenameRemainsAccessibleAndPreviewAndCheckboxHaveSeparateTouchTargets() {
        val base = fixture(CleanupTool.LARGE_FILES)
        val original = requireNotNull(base.results)
        val item = original.items.first().copy(name = "A very long holiday recording with an identifiable ending.mp4")
        val state = base.copy(results = original.copy(items = listOf(item)))
        composeRule.setContent { DuplicatesTheme { FixtureScreen(state) } }
        composeRule
            .onNodeWithContentDescription(
                "${item.name} ${item.canonicalPath}",
                useUnmergedTree = true,
            ).assertExists()
        val preview = composeRule.onNodeWithContentDescription("Preview ${item.name}")
        val checkbox = composeRule.onNodeWithContentDescription("Select ${item.name}")
        for (node in listOf(preview, checkbox)) {
            val bounds = node.fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(bounds.width >= 48f && bounds.height >= 48f)
        }
        preview.performClick()
        assertEquals(1, previews)
        assertEquals(0, selections)
    }

    @Test
    fun monthCheckboxSelectsAllAndClearsOnlyItsMonth() {
        val base = fixture(CleanupTool.SCREENSHOTS)
        val original = requireNotNull(base.results)
        val state =
            mutableStateOf(base.copy(results = original.copy(items = original.items.map { it.copy(selected = false) })))
        composeRule.setContent { DuplicatesTheme { FixtureScreen(state.value) } }
        monthCheckbox(ToggleableState.Off).performClick()
        composeRule.runOnIdle {
            state.value =
                base.copy(results = original.copy(items = original.items.map { it.copy(selected = true) }))
        }
        monthCheckbox(ToggleableState.On).performClick()
        val ids = original.items.mapTo(mutableSetOf()) { it.id }
        assertEquals(listOf(ids to true, ids to false), monthSelections)
    }

    private fun monthCheckbox(expected: ToggleableState) =
        composeRule
            .onNodeWithContentDescription("Select November 2023")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, expected))

    private val usageState = mutableStateOf(UsageAccessState.RESULTS)
    private var previews = 0
    private var selections = 0
    private val monthSelections = mutableListOf<Pair<Set<String>, Boolean>>()

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
                            "Preview ${current.value.visibleItems.first().name}",
                        ).assertIsDisplayed()
                }
                val name = if (tool == CleanupTool.DUPLICATES) "home" else tool.name.lowercase()
                capture("$name-$width-${font.toInt()}-${if (theme) "dark" else "light"}")
                if (tool == CleanupTool.SIMILAR_PHOTOS) {
                    verifyPhotoOptions(current.value)
                    verifyGroupOverview(width)
                }
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
                            { previews++ },
                            {},
                            {},
                            {},
                            {},
                            onSelectVisible = { ids, selected ->
                                monthSelections += ids to selected
                                if (ids.size == 1) selections++
                            },
                            onKeep = {},
                        )
                    }
                }
            }
            RootNavigationBar(RootTab.HOME, {}, emphasizeSelection = state.tool == CleanupTool.DUPLICATES)
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

    private fun verifyPhotoOptions(state: ToolScreenState) {
        val photo = state.visibleItems.first { !it.keeper }
        val options = hasContentDescription("Photo options for ${photo.name}")
        composeRule.onNodeWithTag("tool-results").performScrollToNode(options)
        composeRule.onNode(options).performClick()
        composeRule.onNodeWithText("Keep this photo").assertIsDisplayed()
        assertReadableText()
        composeRule.onNodeWithText("Keep this photo").performClick()
    }

    private fun verifyGroupOverview(width: String) {
        if (width == "landscape") {
            composeRule.onNodeWithTag("tool-results").performScrollToNode(hasTestTag("tool-browse-groups"))
        }
        composeRule.onNodeWithTag("tool-browse-groups").performClick()
        composeRule.onNodeWithText("Browse groups").assertIsDisplayed()
        composeRule.onNodeWithTag("tool-group-list").performScrollToNode(hasText("Group 1"))
        composeRule.onNodeWithText("Group 1").assertIsDisplayed()
        assertReadableText()
        composeRule.onNodeWithText("Close groups").performClick()
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
                        listOf(
                            (if (base.tool == CleanupTool.EMPTY_FOLDERS) "No folders" else "No files") to
                                base.copy(results = null),
                        )
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
                hasText(if (directories) "Delete folders" else "Move to Recovery") and dialog,
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
                UsageAccessState.EMPTY to "No apps",
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
                val tag = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag).orEmpty()
                val metadata = tag.startsWith("file-name-") || tag.startsWith("file-path-")
                layouts.forEach {
                    // Paragraph width may retain the available width after Text shrinks to its glyphs.
                    // Check the rendered lines, allowing only integer pixel rounding.
                    val clippedLine =
                        (0 until it.lineCount).any { line ->
                            it.getLineLeft(line) < -1f || it.getLineRight(line) > it.size.width + 1f ||
                                (!metadata && it.isLineEllipsized(line))
                        }
                    assertFalse(
                        "Clipped text: ${it.layoutInput.text}",
                        (
                            if (metadata) {
                                it.getLineBottom(
                                    it.lineCount - 1,
                                ) > it.size.height + 1f
                            } else {
                                it.didOverflowHeight
                            }
                        ) ||
                            clippedLine,
                    )
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
                composeRule
                    .onNodeWithContentDescription(
                        "Open app details for Word game and puzzles",
                    ).performScrollTo()
                    .assertIsDisplayed()
                composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
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
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule
            .onNodeWithTag(
                "tool-results",
            ).performScrollToNode(hasContentDescription("Preview ${state.visibleItems.first().name}"))
    }

    private fun capture(name: String) {
        val file = File("build/outputs/tool-review/visual/$name.png")
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

    private fun fixture(tool: CleanupTool) = toolLayoutFixture(tool, fixtureRoot)
}
