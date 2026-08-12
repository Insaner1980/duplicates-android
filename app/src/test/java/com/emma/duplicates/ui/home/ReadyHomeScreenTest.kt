package com.emma.duplicates.ui.home

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReadyHomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsRealDefaultScopeWithoutInventingResults() {
        composeRule.setContent {
            DuplicatesTheme {
                ReadyHomeScreen(
                    locationSummary = "Internal storage",
                    typeSummary = "Photos / Videos / Audio / Documents",
                    scanEnabled = true,
                    onStartScan = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("Duplicates").assertIsDisplayed()
        composeRule.onNodeWithText("Ready to scan").assertIsDisplayed()
        composeRule.onNodeWithText("Internal storage").assertIsDisplayed()
        composeRule.onNodeWithText("Photos / Videos / Audio / Documents").assertIsDisplayed()
        composeRule.onNodeWithText("Scanning does not delete anything.").assertIsDisplayed()
        composeRule.onNodeWithText("Start scan").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun actionsInvokeTheirHandlers() {
        var starts = 0
        var settings = 0
        composeRule.setContent {
            DuplicatesTheme {
                ReadyHomeScreen(
                    locationSummary = "Internal storage",
                    typeSummary = "Photos / Videos / Audio / Documents",
                    scanEnabled = true,
                    onStartScan = { starts += 1 },
                    onOpenSettings = { settings += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Start scan").performClick()
        composeRule.onNodeWithContentDescription("Settings").performClick()

        assertEquals(1, starts)
        assertEquals(1, settings)
    }
}
