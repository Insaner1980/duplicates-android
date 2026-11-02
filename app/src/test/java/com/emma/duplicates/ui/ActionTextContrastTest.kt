package com.emma.duplicates.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.data.StorageBrowserLocation
import com.emma.duplicates.ui.exclusions.BrowserBreadcrumbUiState
import com.emma.duplicates.ui.exclusions.ExclusionBrowserScreen
import com.emma.duplicates.ui.exclusions.ExclusionBrowserUiState
import com.emma.duplicates.ui.exclusions.ExclusionKind
import com.emma.duplicates.ui.results.ResultsFilter
import com.emma.duplicates.ui.results.ResultsScreen
import com.emma.duplicates.ui.results.ResultsSort
import com.emma.duplicates.ui.results.ResultsUiState
import com.emma.duplicates.ui.settings.ScanSelectionScreen
import com.emma.duplicates.ui.settings.ScanSelectionUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h800dp-mdpi")
class ActionTextContrastTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun removeSelectionRemainsReadable() =
        checkContrast("Remove") {
            ScanSelectionScreen(selectionState(false), {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})
        }

    @Test
    fun storageNavigationRemainsReadable() =
        checkContrast("Storage") {
            ScanSelectionScreen(selectionState(true), {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})
        }

    @Test
    fun browserBreadcrumbRemainsReadable() =
        checkContrast("Phone") {
            ExclusionBrowserScreen(
                ExclusionBrowserUiState(
                    kind = ExclusionKind.FILE,
                    currentPath = "/storage/emulated/0",
                    entries = emptyList(),
                    breadcrumbs = listOf(BrowserBreadcrumbUiState("/storage/emulated/0", "Phone")),
                ),
                {},
                {},
                {},
                {},
            )
        }

    @Test
    fun outcomeDismissalRemainsReadable() =
        checkContrast("OK", dialog = true) {
            ResultsScreen(
                ResultsUiState(
                    hasCompletedScan = false,
                    duplicateFileCount = 0,
                    duplicateGroupCount = 0,
                    reclaimableBytes = 0L,
                    selectedFilter = ResultsFilter.ALL,
                    selectedSort = ResultsSort.LARGEST_RECLAIMABLE,
                    searchVisible = false,
                    searchQuery = "",
                    clearConfirmationVisible = false,
                    groups = emptyList(),
                    messageRes = R.string.deletion_incomplete,
                ),
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

    private fun checkContrast(
        label: String,
        dialog: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val dark = mutableStateOf(true)
        val font = mutableFloatStateOf(1f)
        var background = Color.Unspecified
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font.floatValue)) {
                DuplicatesTheme(dark.value) {
                    background =
                        if (dialog) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MaterialTheme.colorScheme.background
                        }
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                        content = content,
                    )
                }
            }
        }
        for (theme in listOf(true, false)) {
            for (scale in listOf(1f, 2f)) {
                composeRule.runOnIdle {
                    dark.value = theme
                    font.floatValue = scale
                }
                val action = composeRule.onNodeWithText(label, useUnmergedTree = true)
                action.assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                action.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val text =
                    layouts
                        .single()
                        .layoutInput.style.color
                val lighter = maxOf(text.luminance(), background.luminance())
                val darker = minOf(text.luminance(), background.luminance())
                val contrast = (lighter + 0.05f) / (darker + 0.05f)
                assertTrue("$label contrast=$contrast dark=$theme font=$scale", contrast >= 4.5f)
            }
        }
    }

    private fun selectionState(inFolder: Boolean) =
        ScanSelectionUiState(
            selectedItemsOnly = true,
            selectedPaths = setOf("/storage/emulated/0/Pictures"),
            loading = false,
            location =
                if (inFolder) {
                    StorageBrowserLocation("primary", "Phone", "/storage/emulated/0", null, emptyList())
                } else {
                    null
                },
        )
}
