package com.emma.duplicates.ui.permission

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PermissionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun explainsLocalReadOnlyScanBeforeOfferingStorageSettings() {
        composeRule.setContent {
            DuplicatesTheme {
                PermissionScreen(
                    onGrantAccess = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("Storage access required").assertIsDisplayed()
        composeRule
            .onNodeWithText("Duplicates needs access to shared storage to compare files across your phone.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Files stay on this phone.").assertIsDisplayed()
        composeRule.onNodeWithText("Scanning never deletes anything.").assertIsDisplayed()
        composeRule.onNodeWithText("Grant storage access").assertIsDisplayed().assertIsEnabled()
    }
}
