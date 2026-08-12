package com.emma.duplicates.ui.review

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.ui.model.UiFileCategory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReviewDuplicatesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `deletion result shows deleted and not deleted counts with freed space`() {
        composeRule.setContent {
            DuplicatesTheme {
                ReviewDuplicatesScreen(
                    state =
                        ReviewUiState(
                            groupId = "group",
                            title = "document.pdf",
                            category = UiFileCategory.DOCUMENTS,
                            identicalFileCount = 3,
                            reclaimableBytes = 50_000_000L,
                            selectedCount = 0,
                            selectedBytes = 0,
                            members = emptyList(),
                            deletionResult =
                                DeletionResultUiState(
                                    deletedCount = 1,
                                    failedCount = 2,
                                    reclaimedBytes = 50_000_000L,
                                ),
                        ),
                    onBack = {},
                    onPreviewMember = {},
                    onMemberSelectionChange = { _, _ -> },
                    onSelectionBlocked = {},
                    onRequestDelete = {},
                    onDismissDelete = {},
                    onConfirmDelete = {},
                    onDismissDeletionResult = {},
                )
            }
        }

        composeRule.onNodeWithText("Deleted: 1 / Not deleted: 2").assertIsDisplayed()
        composeRule.onNodeWithText("Space freed: 50 MB").assertIsDisplayed()
    }

    @Test
    fun `delete action opens confirmation and requires explicit confirmation`() {
        val state =
            mutableStateOf(
                ReviewUiState(
                    groupId = "group",
                    title = "document.pdf",
                    category = UiFileCategory.DOCUMENTS,
                    identicalFileCount = 2,
                    reclaimableBytes = 50_000_000L,
                    selectedCount = 1,
                    selectedBytes = 50_000_000L,
                    members = emptyList(),
                ),
            )
        var confirmations = 0
        composeRule.setContent {
            DuplicatesTheme {
                ReviewDuplicatesScreen(
                    state = state.value,
                    onBack = {},
                    onPreviewMember = {},
                    onMemberSelectionChange = { _, _ -> },
                    onSelectionBlocked = {},
                    onRequestDelete = {
                        state.value = state.value.copy(deleteConfirmationVisible = true)
                    },
                    onDismissDelete = {
                        state.value = state.value.copy(deleteConfirmationVisible = false)
                    },
                    onConfirmDelete = { confirmations += 1 },
                    onDismissDeletionResult = {},
                )
            }
        }

        composeRule
            .onNodeWithText("Delete selected duplicates (1) / 50 MB")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Delete selected duplicates?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete selected").performClick()

        assertEquals(1, confirmations)
    }
}
