package com.emma.duplicates

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun permissionExplanationAndRootNavigationAreUsable() {
        val activity = composeRule.activity

        composeRule
            .onNodeWithText(activity.getString(R.string.grant_storage_access))
            .assertIsDisplayed()
            .assertHasClickAction()

        composeRule
            .onNodeWithText(activity.getString(R.string.results))
            .performClick()
        composeRule
            .onNodeWithText(activity.getString(R.string.no_saved_scan_results))
            .assertIsDisplayed()

        composeRule
            .onNodeWithText(activity.getString(R.string.home))
            .performClick()
        composeRule
            .onNodeWithText(activity.getString(R.string.grant_storage_access))
            .assertIsDisplayed()
    }
}
