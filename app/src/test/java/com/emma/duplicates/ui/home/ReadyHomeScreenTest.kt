package com.emma.duplicates.ui.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.ui.model.UiFileCategory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h800dp-mdpi")
class ReadyHomeScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun configurationAndSavedResultsRemainReachableAt320dpAndBothFontScales() = checkLayout()

    @Test
    @Config(qualifiers = "w411dp-h900dp-mdpi")
    fun ordinaryWidthSupportsBothThemesAndBothFontScales() = checkLayout()

    private fun checkLayout() {
        val fontScale = mutableFloatStateOf(1f)
        val dark = mutableStateOf(true)
        var starts = 0
        var previous = 0
        var settings = 0
        var locations = 0
        val changes = mutableListOf<Pair<UiFileCategory, Boolean>>()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale.floatValue)) {
                DuplicatesTheme(dark.value) {
                    HomeScreen(
                        HomeUiState.Ready("Internal storage", "", true),
                        { settings++ },
                        {},
                        { starts++ },
                        {},
                        { previous++ },
                        configuration =
                            configuration(),
                        onOpenScanLocations = { locations++ },
                        onCategoryEnabledChange = { category, selected ->
                            changes +=
                                category to selected
                        },
                    )
                }
            }
        }
        for (theme in listOf(true, false)) {
            composeRule.runOnIdle { dark.value = theme }
            for (scale in listOf(1f, 2f)) {
                composeRule.runOnIdle { fontScale.floatValue = scale }
                composeRule.onNodeWithContentDescription("Settings").performScrollTo().performClick()
                composeRule.onNodeWithText("87.4 GB of 128 GB used").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithText("Internal storage").performScrollTo().performClick()
                composeRule.onNodeWithText("Documents").performScrollTo().performClick()
                composeRule.onNodeWithText("Previous scan").performScrollTo().performClick()
                composeRule
                    .onNodeWithText("Scan files")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .performClick()
                composeRule.onNodeWithText("Find duplicate files").assertDoesNotExist()
                composeRule.onNodeWithText("Files stay on your device.").assertDoesNotExist()
            }
        }
        assertEquals(4, starts)
        assertEquals(4, previous)
        assertEquals(4, settings)
        assertEquals(4, locations)
        assertEquals(List(4) { UiFileCategory.DOCUMENTS to false }, changes)
    }

    private fun configuration() =
        HomeConfiguration(
            "Internal storage",
            false,
            128_000_000_000L,
            40_600_000_000L,
            UiFileCategory.entries.associateWith { true },
            true,
            52,
            189_500_000L,
            true,
        )
}
