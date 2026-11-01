package com.emma.duplicates.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.data.StorageBrowserEntry
import com.emma.duplicates.data.StorageBrowserLocation
import com.emma.duplicates.ui.settings.ScanSelectionScreen
import com.emma.duplicates.ui.settings.ScanSelectionUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h800dp-mdpi")
class ScanSelectionScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun selectionAndDoneRemainReachableAt320dpInBothThemesAndFontScales() = checkLayout()

    @Test
    @Config(qualifiers = "w411dp-h900dp-mdpi")
    fun ordinaryWidthSupportsBothThemesAndFontScales() = checkLayout()

    private fun checkLayout() {
        val scale = mutableFloatStateOf(1f)
        val dark = mutableStateOf(true)
        val folder = "/storage/emulated/0/Pictures/Vacation photos"
        val state = mutableStateOf(folderState(folder))
        var opened = 0
        var saved = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.floatValue)) {
                DuplicatesTheme(dark.value) {
                    ScanSelectionScreen(
                        state.value,
                        {},
                        {},
                        {},
                        { _, _ -> },
                        { opened++ },
                        {},
                        { path, selected ->
                            state.value =
                                state.value.copy(selectedPaths = if (selected) setOf(path) else emptySet())
                        },
                        { saved++ },
                    )
                }
            }
        }
        for (theme in listOf(true, false)) {
            for (font in listOf(1f, 2f)) {
                composeRule.runOnIdle {
                    dark.value = theme
                    scale.floatValue = font
                    state.value = state.value.copy(selectedPaths = emptySet())
                }
                composeRule.onNodeWithText("Done").assertIsDisplayed().assertIsNotEnabled()
                composeRule.onNodeWithText("Vacation photos").performScrollTo().performClick()
                assertEquals(0, state.value.selectedPaths.size)
                composeRule.onNodeWithContentDescription("Vacation photos").performScrollTo().performClick()
                assertEquals(setOf(folder), state.value.selectedPaths)
                composeRule.onNodeWithText("1 item selected").assertIsDisplayed()
                composeRule.onNodeWithText("Done").assertIsDisplayed().performClick()
            }
        }
        assertEquals(4, opened)
        assertEquals(4, saved)
    }

    private fun folderState(folder: String) =
        ScanSelectionUiState(
            selectedItemsOnly = true,
            loading = false,
            location =
                StorageBrowserLocation(
                    "primary",
                    "Internal storage",
                    "/storage/emulated/0/Pictures",
                    "/storage/emulated/0",
                    listOf(StorageBrowserEntry("Vacation photos", folder, true, 0, true)),
                ),
        )
}
