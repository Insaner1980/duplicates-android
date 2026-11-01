package com.emma.duplicates.ui.results

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
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
                DuplicatesTheme {
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
        composeRule.setContent {
            DuplicatesTheme {
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
                    onMemberSelectionChange = { id, value -> selected += id to value },
                    onRequestDelete = {
                        state.value =
                            state.value.copy(
                                deleteConfirmationVisible = true,
                                confirmationCount = 11,
                                confirmationBytes = 1100L,
                                confirmationHiddenCount = 2,
                            )
                    },
                    onConfirmDelete = { confirmed++ },
                )
            }
        }
        composeRule.onNodeWithTag("results-list").performScrollToNode(hasTestTag("selection-member-1"))
        composeRule.onNodeWithTag("selection-member-1").performClick()
        assertEquals(listOf("member-1" to false), selected)
        composeRule.onNodeWithTag("results-list").performScrollToNode(hasTestTag("keep-member-1"))
        composeRule.onNodeWithTag("keep-member-1").performClick()
        assertEquals(listOf("member-1" to false, "member-1" to false), selected)
        composeRule.onNodeWithTag("delete-selected").performClick()
        composeRule.onNodeWithText("Delete files permanently?").assertIsDisplayed()
        assertEquals(0, confirmed)
        composeRule.onNodeWithText("Delete permanently").performClick()
        assertEquals(1, confirmed)
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
            DuplicatesTheme {
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
        composeRule.onNodeWithText("Deleted: 1\nFreed: 50 MB\nNot deleted: 1").assertIsDisplayed()
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
                DuplicatesTheme {
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

    private fun checkLayout() {
        val scale = mutableFloatStateOf(1f)
        val dark = mutableStateOf(true)
        val previews = mutableListOf<Pair<String, String>>()
        val selections = mutableListOf<Pair<String, Boolean>>()
        var deletes = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.floatValue)) {
                DuplicatesTheme(dark.value) {
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
                assertCompleteLocation(font)
                assertTrue(selections.isEmpty())
                val row = composeRule.onNodeWithTag("preview-file-11").fetchSemanticsNode().boundsInRoot
                val footer = composeRule.onNodeWithTag("selection-footer").fetchSemanticsNode().boundsInRoot
                assertTrue("The final file must not overlap the footer", row.bottom <= footer.top)
                composeRule.onNodeWithTag("results-list").performScrollToNode(hasTestTag("preview-file-0"))
                composeRule.onNodeWithText("Pictures / Trips / 2025 / June", useUnmergedTree = true).assertIsDisplayed()
                composeRule.onNodeWithTag("delete-selected").performClick()
            }
        }
        assertEquals(4, deletes)
    }

    private fun assertCompleteLocation(font: Float) {
        val pathLayout = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText("Download / Trips / 2025 / June", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(pathLayout) }
        assertTrue(pathLayout.isNotEmpty())
        pathLayout.forEach { layout ->
            assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
            // The paragraph can retain a wider constraint than the Text; check actual line bounds.
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue(
                    "Location line $line must fit at font scale $font",
                    layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= layout.size.width &&
                        layout.getLineBottom(line) <= layout.size.height,
                )
            }
        }
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
