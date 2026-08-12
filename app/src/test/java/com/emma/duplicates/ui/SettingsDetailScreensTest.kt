package com.emma.duplicates.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.ui.exclusions.BrowserEntryUiState
import com.emma.duplicates.ui.exclusions.ExclusionBrowserScreen
import com.emma.duplicates.ui.exclusions.ExclusionBrowserUiState
import com.emma.duplicates.ui.exclusions.ExclusionKind
import com.emma.duplicates.ui.model.UiFileCategory
import com.emma.duplicates.ui.settings.ScanLocationUiState
import com.emma.duplicates.ui.settings.ScanLocationsScreen
import com.emma.duplicates.ui.settings.ScanLocationsUiState
import com.emma.duplicates.ui.settings.TypesToScanScreen
import com.emma.duplicates.ui.settings.TypesToScanUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsDetailScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scanLocationsBlocksRemovingTheOnlySelectedVolume() {
        var blocked = 0
        composeRule.setContent {
            DuplicatesTheme {
                ScanLocationsScreen(
                    state =
                        ScanLocationsUiState(
                            locations =
                                listOf(
                                    ScanLocationUiState(
                                        id = "primary",
                                        displayName = "Internal storage",
                                        totalBytes = 128_000_000_000L,
                                        freeBytes = 32_000_000_000L,
                                        selected = true,
                                        mounted = true,
                                    ),
                                ),
                        ),
                    onBack = {},
                    onLocationSelectionChange = { _, _ -> },
                    onSelectionBlocked = { blocked += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Internal storage").performClick()
        composeRule.onNodeWithText("Select at least one storage location.").assertIsDisplayed()
        assertEquals(1, blocked)
    }

    @Test
    fun typesScreenUsesLargeRowsAndProtectsTheFinalEnabledType() {
        var blocked = 0
        composeRule.setContent {
            DuplicatesTheme {
                TypesToScanScreen(
                    state =
                        TypesToScanUiState(
                            enabled =
                                mapOf(
                                    UiFileCategory.PHOTOS to true,
                                    UiFileCategory.VIDEOS to false,
                                    UiFileCategory.AUDIO to false,
                                    UiFileCategory.DOCUMENTS to false,
                                ),
                        ),
                    onBack = {},
                    onCategoryEnabledChange = { _, _ -> },
                    onSelectionBlocked = { blocked += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Photos").performClick()
        composeRule.onNodeWithText("Images and screenshots").assertIsDisplayed()
        composeRule
            .onNodeWithTag("types-list")
            .performScrollToNode(hasText("Select at least one file type."))
        composeRule.onNodeWithText("Select at least one file type.").assertIsDisplayed()
        assertEquals(1, blocked)
    }

    @Test
    fun exclusionBrowserOpensDirectoriesAndSelectsAccessibleFiles() {
        var opened: String? = null
        var selected: String? = null
        composeRule.setContent {
            DuplicatesTheme {
                ExclusionBrowserScreen(
                    state =
                        ExclusionBrowserUiState(
                            kind = ExclusionKind.FILE,
                            currentPath = "/storage/emulated/0",
                            entries =
                                listOf(
                                    BrowserEntryUiState(
                                        canonicalPath = "/storage/emulated/0/DCIM",
                                        displayName = "DCIM",
                                        isDirectory = true,
                                        accessible = true,
                                    ),
                                    BrowserEntryUiState(
                                        canonicalPath = "/storage/emulated/0/note.txt",
                                        displayName = "note.txt",
                                        isDirectory = false,
                                        accessible = true,
                                    ),
                                ),
                        ),
                    onBack = {},
                    onOpenDirectory = { opened = it },
                    onSelectFile = { selected = it },
                    onSelectCurrentFolder = {},
                )
            }
        }

        composeRule.onNodeWithText("DCIM").performClick()
        composeRule.onNodeWithText("note.txt").performClick()
        assertEquals("/storage/emulated/0/DCIM", opened)
        assertEquals("/storage/emulated/0/note.txt", selected)
    }
}
