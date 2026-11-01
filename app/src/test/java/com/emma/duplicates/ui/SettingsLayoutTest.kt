package com.emma.duplicates.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.ui.settings.SettingsScreen
import com.emma.duplicates.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h800dp-mdpi")
class SettingsLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun settingsRemainReachableAt320dpInBothThemesAndFontScales() = checkLayout()

    @Test
    @Config(qualifiers = "w411dp-h900dp-mdpi")
    fun ordinaryWidthSupportsBothThemesAndFontScales() = checkLayout()

    private fun checkLayout() {
        val scale = mutableFloatStateOf(1f)
        val dark = mutableStateOf(true)
        var locations = 0
        var switches = 0
        val state =
            SettingsUiState(
                "Pictures, Downloads",
                false,
                true,
                true,
                true,
                "Photos / Videos / Audio / Documents",
                true,
                "1.0.0",
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.floatValue)) {
                DuplicatesTheme(dark.value) {
                    SettingsScreen(state, {}, { locations++ }, { switches++ }, {}, {}, {}, {}, {}, {})
                }
            }
        }
        for (theme in listOf(true, false)) {
            for (font in listOf(1f, 2f)) {
                composeRule.runOnIdle {
                    dark.value = theme
                    scale.floatValue = font
                }
                composeRule
                    .onNodeWithText("Scan locations")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .performClick()
                composeRule
                    .onNodeWithText("Scan hidden folders")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .performClick()
                composeRule.onNodeWithText("App version").performScrollTo().assertIsDisplayed()
            }
        }
        assertEquals(4, locations)
        assertEquals(4, switches)
    }
}
