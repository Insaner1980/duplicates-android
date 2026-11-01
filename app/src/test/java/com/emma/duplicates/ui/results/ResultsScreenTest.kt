package com.emma.duplicates.ui.results

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.ui.model.UiFileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h800dp-mdpi")
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
                assertTrue(selections.isEmpty())
                val row = composeRule.onNodeWithTag("preview-file-11").fetchSemanticsNode().boundsInRoot
                val footer = composeRule.onNodeWithTag("selection-footer").fetchSemanticsNode().boundsInRoot
                assertTrue("The final file must not overlap the footer", row.bottom <= footer.top)
                composeRule.onNodeWithTag("delete-selected").performClick()
            }
        }
        assertEquals(4, deletes)
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
                    "Documents / Folder $index",
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
