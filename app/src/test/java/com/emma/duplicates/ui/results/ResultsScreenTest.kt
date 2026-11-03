package com.emma.duplicates.ui.results

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.domain.deletion.DeletionStatus
import com.emma.duplicates.domain.deletion.FileDeletionOutcome
import com.emma.duplicates.ui.model.UiFileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResultsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun inlineControlsAndFooterRemainSeparateAt320dpInBothThemesAndFontScales() = checkLayout()

    @Test
    @Config(qualifiers = "w411dp-h900dp-mdpi")
    fun ordinaryWidthSupportsBothThemesAndDoubleFontScale() = checkLayout()

    @Test
    @Config(qualifiers = "w640dp-h320dp-mdpi")
    fun shortLandscapeKeepsListAndHiddenSelectionActionsReachableAtDoubleFont() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ResultsTestTheme {
                    ResultsScreen(
                        fixture().copy(hiddenSelectedCount = 3),
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        { _, _ -> },
                        {},
                        {},
                        {},
                        {},
                    )
                }
            }
        }
        val listBounds = composeRule.onNodeWithTag("results-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Results must retain space below the toolbar", listBounds.height >= 64f)
        composeRule.onNodeWithTag("results-list").performScrollToNode(hasTestTag("preview-file-11"))
        composeRule.onNodeWithTag("preview-file-11").assertIsDisplayed()
        composeRule.onNodeWithText("Show all").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("delete-selected").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun selectionAndGlobalConfirmationAreIndependentOfPreview() {
        val state = mutableStateOf(fixture())
        var confirmed = 0
        val selected = mutableListOf<Pair<String, Boolean>>()
        setSelectionContent(
            state,
            onMemberSelectionChange = { id, value -> selected += id to value },
            onRequestDelete = {
                state.value =
                    state.value.copy(
                        deleteConfirmationVisible = true,
                        confirmationCount = 11,
                        confirmationBytes = 1100L,
                        confirmationHiddenCount = 2,
                        selectedCount = 7,
                    )
            },
            onConfirmDelete = { confirmed++ },
        )
        composeRule.onNodeWithTag("results-list").performScrollToNode(hasTestTag("selection-member-1"))
        composeRule.onNodeWithTag("selection-member-1").performClick()
        assertEquals(listOf("member-1" to false), selected)
        composeRule.onNodeWithText("Keep this copy").assertDoesNotExist()
        composeRule.onNodeWithTag("delete-selected").performClick()
        composeRule.onNodeWithText("Move files to recovery?").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Move 11 selected files (1.1 KB) to recovery for at least 30 days? " +
                    "Restore copies in Settings before removing the app or clearing its data.\n\n" +
                    "Includes 2 selected files not shown.",
            ).assertIsDisplayed()
        assertEquals(0, confirmed)
        composeRule.onNode(hasText("Delete 11 files") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    @Config(qualifiers = "w411dp-h900dp-mdpi")
    fun visibleStatesTrackTheExactCopySelectedForDeletion() {
        val state = mutableStateOf(twoCopyFixture())
        val selected = mutableListOf<Pair<String, Boolean>>()
        setSelectionContent(
            state,
            onMemberSelectionChange = { id, value -> selected += id to value },
        )
        composeRule.onNodeWithTag("selection-state-member-0").assertTextEquals("Will be kept")
        composeRule.onNodeWithTag("selection-member-0").assertIsNotEnabled()
        composeRule.onNodeWithTag("selection-state-member-1").assertTextEquals("Selected for deletion")
        composeRule.onNodeWithText("Delete 1 file").assertIsDisplayed()
        composeRule.onNodeWithText("duplicate group").assertIsDisplayed()
        val pixels = composeRule.onNodeWithTag("delete-selected").captureToImage().toPixelMap()
        assertEquals(Color(0xFFEB5E28), pixels[8, pixels.height / 2])
        captureReviewScreenshot("results-dark-411")

        composeRule.onNodeWithTag("selection-member-1").performClick()
        assertEquals(listOf("member-1" to false), selected)
        composeRule.runOnIdle { state.value = twoCopyFixture(selectedIndex = null) }
        composeRule.onNodeWithTag("selection-state-member-1").assertTextEquals("Will be kept")
        composeRule.onNodeWithTag("delete-selected").assertIsNotEnabled()
        composeRule.onNodeWithTag("selection-member-0").performClick()
        assertEquals("member-0" to true, selected.last())
        composeRule.runOnIdle { state.value = twoCopyFixture(selectedIndex = 0) }
        composeRule.onNodeWithTag("selection-state-member-0").assertTextEquals("Selected for deletion")
        composeRule.onNodeWithTag("selection-state-member-1").assertTextEquals("Will be kept")
        composeRule.onNodeWithText("Delete 1 file").assertIsDisplayed()
    }

    @Test
    fun filteredEmptyResultsExplainGlobalSelectionsAndRevealTheSelectedChip() {
        val state =
            mutableStateOf(
                fixture().copy(
                    groups = emptyList(),
                    selectedFilter = ResultsFilter.DOCUMENTS,
                    hiddenSelectedCount = 11,
                ),
            )
        var showAll = 0
        composeRule.setContent {
            ResultsTestTheme {
                ResultsScreen(
                    state.value,
                    {},
                    {},
                    { state.value = state.value.copy(selectedFilter = it) },
                    {},
                    {},
                    {},
                    { _, _ -> },
                    {},
                    {},
                    {},
                    {},
                    onShowAll = { showAll++ },
                )
            }
        }
        composeRule.onNodeWithText("Available to free across all results").assertIsDisplayed()
        composeRule.onNodeWithText("No duplicate documents found.").assertIsDisplayed()
        composeRule.onNodeWithText("Includes 11 selected files not shown.").assertIsDisplayed()
        composeRule.onNodeWithText("Delete 11 files").assertIsDisplayed()
        composeRule.onNodeWithText("Show all").performClick()
        assertEquals(1, showAll)
        val filters = composeRule.onNodeWithTag("results-filters")
        filters.performScrollToNode(hasTestTag("filter-PHOTOS"))
        composeRule.onNodeWithTag("filter-PHOTOS").performClick()
        composeRule.waitForIdle()
        val selectedChip = composeRule.onNodeWithTag("filter-PHOTOS").fetchSemanticsNode().boundsInRoot
        val filterBounds = filters.fetchSemanticsNode().boundsInRoot
        assertTrue(selectedChip.left >= filterBounds.left && selectedChip.right <= filterBounds.right)
        composeRule.onNodeWithText("No duplicate photos found.").assertIsDisplayed()
        composeRule.runOnIdle { state.value = state.value.copy(searchQuery = "missing") }
        composeRule.onNodeWithText("No duplicates match this search.").assertIsDisplayed()
    }

    @Test
    fun deletionOutcomeSurvivesAnEmptyResultList() {
        val result =
            com.emma.duplicates.domain.deletion.DeletionResult(
                listOf(
                    com.emma.duplicates.domain.deletion.FileDeletionOutcome(
                        "group",
                        "one",
                        com.emma.duplicates.domain.deletion.DeletionStatus.DELETED,
                        50_000_000L,
                    ),
                    com.emma.duplicates.domain.deletion.FileDeletionOutcome(
                        "group",
                        "two",
                        com.emma.duplicates.domain.deletion.DeletionStatus.AUTHORIZATION_CANCELED,
                        0L,
                    ),
                ),
                com.emma.duplicates.domain.deletion.DeletionDatabaseUpdate(
                    emptySet(),
                    emptySet(),
                    emptySet(),
                    emptyList(),
                ),
            )
        composeRule.setContent {
            ResultsTestTheme {
                ResultsScreen(
                    fixture().copy(groups = emptyList(), duplicateGroupCount = 0, deletionResult = result),
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    { _, _ -> },
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
        composeRule
            .onNodeWithText(
                "Moved to recovery: 1\nFreed now: 50 MB\nNot moved: 1",
            ).assertIsDisplayed()
        composeRule
            .onNodeWithText("Android deletion approval was canceled. The affected files were kept.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun retainedFileReasonsRemainReadableAt320dpAndDoubleFontScale() {
        val result =
            com.emma.duplicates.domain.deletion.DeletionResult(
                DeletionStatus.entries
                    .filter { it != DeletionStatus.DELETED }
                    .map { status ->
                        FileDeletionOutcome("group", status.name, status)
                    },
                com.emma.duplicates.domain.deletion.DeletionDatabaseUpdate(
                    emptySet(),
                    emptySet(),
                    emptySet(),
                    emptyList(),
                ),
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ResultsTestTheme {
                    ResultsScreen(
                        fixture().copy(deletionResult = result),
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        { _, _ -> },
                        {},
                        {},
                        {},
                        {},
                    )
                }
            }
        }
        listOf(
            "Some files were already missing. They were not counted as deleted or freed space.",
            "Some files changed since the scan and were kept. Scan again before reviewing them.",
            "Some files could not be verified as duplicates and were kept for safety.",
            "Some files could not be accessed. Check storage access before trying again.",
            "Android deletion approval was canceled. The affected files were kept.",
            "Some files could not be deleted. Review the remaining copies before trying again.",
            "Some files were kept because no other valid copy could be confirmed.",
        ).forEach { reason -> composeRule.onNodeWithText(reason).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithText("OK").assertIsDisplayed()
    }

    private fun setSelectionContent(
        state: State<ResultsUiState>,
        onMemberSelectionChange: (String, Boolean) -> Unit,
        onRequestDelete: () -> Unit = {},
        onConfirmDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            ResultsTestTheme {
                ResultsScreen(
                    state.value,
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    { _, _ -> error("Selection must not preview") },
                    {},
                    {},
                    {},
                    {},
                    onMemberSelectionChange = onMemberSelectionChange,
                    onRequestDelete = onRequestDelete,
                    onConfirmDelete = onConfirmDelete,
                )
            }
        }
    }

    private fun checkLayout() {
        val scale = mutableFloatStateOf(1f)
        val dark = mutableStateOf(true)
        val previews = mutableListOf<Pair<String, String>>()
        val selections = mutableListOf<Pair<String, Boolean>>()
        var deletes = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.floatValue)) {
                ResultsTestTheme(dark.value) {
                    ResultsScreen(
                        fixture(),
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        { group, file -> previews += group to file },
                        {},
                        {},
                        {},
                        {},
                        onMemberSelectionChange = {
                            id,
                            selected,
                            ->
                            selections += id to selected
                        },
                        onRequestDelete = { deletes++ },
                    )
                }
            }
        }
        for (theme in listOf(true, false)) {
            for (font in listOf(1f, 2f)) {
                composeRule.runOnIdle {
                    dark.value = theme
                    scale.floatValue = font
                }
                composeRule.onNodeWithTag("results-list").performScrollToIndex(0)
                composeRule.onNodeWithText("Available to free").assertIsDisplayed()
                composeRule.onNodeWithTag("delete-selected").assertIsDisplayed()
                composeRule.onNodeWithTag("results-list").performScrollToNode(hasTestTag("preview-file-11"))
                composeRule.onNodeWithTag("preview-file-11").performClick()
                assertEquals("group" to "file-11", previews.last())
                assertFinalMemberLayout(font)
                assertTrue(selections.isEmpty())
                composeRule.onNodeWithTag("results-list").performScrollToNode(hasTestTag("preview-file-0"))
                composeRule.onNodeWithText("Pictures / Trips / 2025 / June", useUnmergedTree = true).assertIsDisplayed()
                captureLayoutScreenshot(theme, font)
                composeRule.onNodeWithTag("delete-selected").performClick()
            }
        }
        assertEquals(4, deletes)
    }

    private fun assertFinalMemberLayout(font: Float) {
        assertCompactLocation(font)
        composeRule.onNodeWithTag("results-list").performScrollToNode(hasTestTag("selection-state-member-11"))
        composeRule
            .onNodeWithTag("selection-state-member-11")
            .assertIsDisplayed()
            .assertTextEquals("Selected for deletion")
        val row = composeRule.onNodeWithTag("preview-file-11").fetchSemanticsNode().boundsInRoot
        val footer = composeRule.onNodeWithTag("selection-footer").fetchSemanticsNode().boundsInRoot
        assertTrue("The final file must not overlap the footer", row.bottom <= footer.top)
    }

    private fun assertCompactLocation(font: Float) {
        val pathLayout = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithTag("location-member-11", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(pathLayout) }
        assertTrue(pathLayout.isNotEmpty())
        pathLayout.forEach { layout ->
            assertEquals(1, layout.lineCount)
            assertEquals(TextOverflow.MiddleEllipsis, layout.layoutInput.overflow)
            // The paragraph can retain a wider constraint than the Text; check actual line bounds.
            for (line in 0 until layout.lineCount) {
                assertTrue(
                    "Location line $line must fit at font scale $font",
                    layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= layout.size.width &&
                        layout.getLineBottom(line) <= layout.size.height,
                )
            }
        }
    }

    private fun captureLayoutScreenshot(
        dark: Boolean,
        font: Float,
    ) {
        val width =
            composeRule
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.width
                .toInt()
        val name =
            when {
                dark && font == 2f && width == 320 -> "results-dark-320-font200"
                !dark && font == 1f && width == 411 -> "results-light-411-font100"
                else -> return
            }
        if (dark) composeRule.onNodeWithTag("results-list").performScrollToIndex(3)
        captureReviewScreenshot(name)
    }

    private fun captureReviewScreenshot(name: String) {
        val output = File("build/outputs/results-clarity", "$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { stream ->
            assertTrue(
                composeRule
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, stream),
            )
        }
    }

    private fun twoCopyFixture(selectedIndex: Int? = 1): ResultsUiState {
        val state = fixture()
        val group = state.groups.single()
        val members =
            group.members.take(2).mapIndexed { index, member ->
                member.copy(
                    selected = index == selectedIndex,
                    canSelect =
                        selectedIndex == null || index == selectedIndex,
                )
            }
        return state.copy(
            groups = listOf(group.copy(copyCount = 2, reclaimableBytes = 100L, members = members)),
            duplicateFileCount = 2,
            selectedCount = if (selectedIndex == null) 0 else 1,
            selectedBytes = if (selectedIndex == null) 0L else 100L,
            availableBytes = 100L,
        )
    }

    private fun fixture(): ResultsUiState {
        val members =
            (0..11).map { index ->
                ResultMemberUiState(
                    "member-$index",
                    "file-$index",
                    "Long shared document name with non-ASCII ääkköset $index.txt",
                    UiFileCategory.DOCUMENTS,
                    100L,
                    "/storage/emulated/0/Documents/Folder $index/long.txt",
                    when (index) {
                        0 -> "Pictures / Trips / 2025 / June"
                        11 -> "Download / Trips / 2025 / June"
                        else -> "Documents / Folder $index"
                    },
                    null,
                    null,
                    index > 0,
                    index == 0,
                    true,
                    index > 0,
                )
            }
        return ResultsUiState(
            true,
            12,
            1,
            1100L,
            ResultsFilter.ALL,
            ResultsSort.LARGEST_RECLAIMABLE,
            false,
            "",
            false,
            listOf(
                DuplicateGroupCardUiState(
                    "group",
                    "document",
                    UiFileCategory.DOCUMENTS,
                    12,
                    1100L,
                    emptyList(),
                    null,
                    members = members,
                ),
            ),
            sessionId = "session",
            availableBytes = 1100L,
            selectedCount = 11,
            selectedBytes = 1100L,
            scannedFileCount = 12,
        )
    }
}

@Composable
private fun ResultsTestTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    DuplicatesTheme(dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
