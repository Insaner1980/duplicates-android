package com.emma.duplicates.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.ui.tools.ToolControls
import com.emma.duplicates.ui.tools.ToolResultsScreen
import com.emma.duplicates.ui.tools.ToolScreenState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolSelectionScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @get:Rule val temporary = TemporaryFolder()
    private val fixtureRoot by lazy { temporary.newFolder("volume") }
    private val monthSelections = mutableListOf<Pair<Set<String>, Boolean>>()
    private val keepers = mutableListOf<String>()
    private var previews = 0

    @Test
    @Config(qualifiers = "w320dp-h800dp-mdpi")
    fun largeTextKeepsDownloadsTitleOnOneLine() {
        val state = fixture(CleanupTool.OLD_DOWNLOADS)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                DuplicatesTheme { FixtureScreen(state) }
            }
        }
        val heading = composeRule.onNodeWithText("Downloads").assertIsDisplayed().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.runOnIdle {
            heading.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertEquals(1, layouts.single().lineCount)
        }
    }

    @Test
    fun bulkSelectionIncludesOffscreenFilesAndZeroStateStaysCompact() {
        val base = fixture(CleanupTool.OLD_DOWNLOADS)
        val results = requireNotNull(base.results)
        val items = (0 until 30).map { index -> results.items.first().copy(id = "$index", selected = false) }
        val state = mutableStateOf(base.copy(results = results.copy(items = items)))
        composeRule.setContent { DuplicatesTheme { FixtureScreen(state.value) } }
        composeRule.onNodeWithTag("tool-cleanup").assertDoesNotExist()
        composeRule.onNodeWithTag("tool-footer").assertDoesNotExist()
        composeRule.onNodeWithTag("tool-select-all").performClick()
        val ids = items.mapTo(HashSet()) { it.id }
        assertEquals(listOf(ids to true), monthSelections)
        composeRule.runOnIdle {
            state.value = base.copy(results = results.copy(items = items.map { it.copy(selected = true) }))
        }
        composeRule.onNodeWithText("30 selected").assertIsDisplayed()
        composeRule.onNodeWithTag("tool-clear-selection").performClick()
        assertEquals(listOf(ids to true, ids to false), monthSelections)
    }

    @Test
    fun groupOverviewNavigatesWithoutLosingSelectionAndKeepDoesNotPreview() {
        val base = fixture(CleanupTool.SIMILAR_PHOTOS)
        val results = requireNotNull(base.results)
        val next = results.items.take(2).map { it.copy(id = "next-${it.id}", groupId = "next", selected = false) }
        val state = mutableStateOf(base.copy(results = results.copy(items = results.items + next)))
        composeRule.setContent {
            DuplicatesTheme {
                FixtureScreen(state.value, onControls = { state.value = state.value.copy(controls = it) })
            }
        }
        composeRule.onNodeWithTag("tool-select-all").performClick()
        assertEquals(listOf(setOf("1", "2", "3") to true), monthSelections)
        composeRule.onNodeWithText("Keep this photo").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Photo options for ${results.items[1].name}").performClick()
        composeRule.onNodeWithText("Keep this photo").performClick()
        assertEquals(listOf("1"), keepers)
        assertEquals(0, previews)
        composeRule.onNodeWithTag("tool-browse-groups").performClick()
        composeRule.onNodeWithTag("tool-group-list").performScrollToNode(hasText("Group 2"))
        composeRule.onNodeWithText("Group 2").performClick()
        composeRule.onNodeWithText("Group 2 of 2").assertIsDisplayed()
        composeRule.onNodeWithText("1 selected outside this view").assertIsDisplayed()
        composeRule.onNodeWithTag("tool-select-all").performClick()
        assertEquals(setOf("next-1") to true, monthSelections.last())
    }

    @Test
    fun similarPhotoOutcomeFollowsSelectionWithoutConfusingItWithKeepAction() {
        val base = fixture(CleanupTool.SIMILAR_PHOTOS)
        val results = requireNotNull(base.results)
        val items = results.items.take(2)
        val photo = items[1]
        val state = mutableStateOf(base.copy(results = results.copy(items = items)))
        composeRule.setContent { DuplicatesTheme { FixtureScreen(state.value) } }
        val preview = composeRule.onNodeWithContentDescription("Preview ${photo.name}")
        val selection = composeRule.onNodeWithContentDescription("Select ${photo.name}")
        preview.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected for Recovery"))
        selection.performClick()
        assertEquals(listOf(setOf(photo.id) to false), monthSelections)
        composeRule.runOnIdle {
            state.value = base.copy(results = results.copy(items = items.map { it.copy(selected = false) }))
        }
        preview.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Will be kept"))
        selection.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Will be kept"))
        composeRule.onNodeWithText("Keep this photo").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Select ${items[0].name}").assertDoesNotExist()
    }

    @Composable
    private fun FixtureScreen(
        state: ToolScreenState,
        onControls: (ToolControls) -> Unit = {},
    ) {
        ToolResultsScreen(
            state,
            {},
            {},
            {},
            {},
            onControls,
            { previews++ },
            {},
            {},
            {},
            {},
            onSelectVisible = { ids, selected -> monthSelections += ids to selected },
            onKeep = { keepers += it },
        )
    }

    private fun fixture(tool: CleanupTool) = toolLayoutFixture(tool, fixtureRoot)
}
