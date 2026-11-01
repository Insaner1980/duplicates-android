package com.emma.duplicates.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.ui.exclusions.ExclusionItemUiState
import com.emma.duplicates.ui.exclusions.ExclusionKind
import com.emma.duplicates.ui.exclusions.ExclusionsScreen
import com.emma.duplicates.ui.exclusions.ExclusionsUiState
import com.emma.duplicates.ui.home.CompletedHomeUiState
import com.emma.duplicates.ui.home.HomeCategorySummaryUiState
import com.emma.duplicates.ui.home.HomeScreen
import com.emma.duplicates.ui.home.HomeUiState
import com.emma.duplicates.ui.model.UiFileCategory
import com.emma.duplicates.ui.permission.PermissionScreen
import com.emma.duplicates.ui.preview.GenericPreviewScreen
import com.emma.duplicates.ui.preview.GenericPreviewUiState
import com.emma.duplicates.ui.preview.PreviewAvailability
import com.emma.duplicates.ui.results.DuplicateGroupCardUiState
import com.emma.duplicates.ui.results.ResultsFilter
import com.emma.duplicates.ui.results.ResultsScreen
import com.emma.duplicates.ui.results.ResultsSort
import com.emma.duplicates.ui.results.ResultsUiState
import com.emma.duplicates.ui.scan.ScanPhase
import com.emma.duplicates.ui.scan.ScanPhaseUiState
import com.emma.duplicates.ui.scan.ScanRunStatus
import com.emma.duplicates.ui.scan.ScanStepStatus
import com.emma.duplicates.ui.scan.ScanningScreen
import com.emma.duplicates.ui.scan.ScanningUiState
import com.emma.duplicates.ui.settings.SettingsScreen
import com.emma.duplicates.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PresentationScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scanningShowsTruthfulPhasesAndConfirmationCallbacks() {
        var stopRequests = 0
        var stopConfirmations = 0
        val confirmationVisible = mutableStateOf(false)
        setContent {
            ScanningScreen(
                state =
                    ScanningUiState(
                        runStatus = ScanRunStatus.RUNNING,
                        overallProgress = 0.68f,
                        filesIndexed = 24_812,
                        groupsFound = 186,
                        groupsAreConfirmed = false,
                        reclaimableBytes = 21_600_000_000L,
                        phases =
                            listOf(
                                ScanPhaseUiState(ScanPhase.FINDING_FILES, ScanStepStatus.COMPLETED),
                                ScanPhaseUiState(ScanPhase.COMPARING_CANDIDATES, ScanStepStatus.IN_PROGRESS),
                                ScanPhaseUiState(ScanPhase.VERIFYING_DUPLICATES, ScanStepStatus.PENDING),
                            ),
                        currentOperation = "Internal storage",
                        currentPath = "/storage/emulated/0/DCIM/Camera",
                        stopConfirmationVisible = confirmationVisible.value,
                    ),
                onBack = {},
                onRequestStop = {
                    stopRequests += 1
                    confirmationVisible.value = true
                },
                onDismissStop = {},
                onConfirmStop = { stopConfirmations += 1 },
            )
        }

        composeRule.onNodeWithContentDescription("68%").assertIsDisplayed()
        composeRule.onNodeWithText("Comparing candidates").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("In progress").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Stop scan").performScrollTo().performClick()
        composeRule.onNodeWithText("Stop this scan?").assertIsDisplayed()
        composeRule.onNode(hasText("Stop scan") and hasAnyAncestor(isDialog())).performClick()

        assertEquals(1, stopConfirmations)
        assertEquals(1, stopRequests)
    }

    @Test
    fun scanPhaseRowExpandsAtDoubleFontScale() {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                ScanningScreen(
                    state =
                        ScanningUiState(
                            runStatus = ScanRunStatus.RUNNING,
                            overallProgress = 0.5f,
                            filesIndexed = 1,
                            groupsFound = 0,
                            groupsAreConfirmed = false,
                            reclaimableBytes = 0L,
                            phases =
                                listOf(
                                    ScanPhaseUiState(
                                        ScanPhase.COMPARING_CANDIDATES,
                                        ScanStepStatus.IN_PROGRESS,
                                    ),
                                ),
                            currentOperation = "Internal storage",
                            currentPath = "/storage/emulated/0",
                            stopConfirmationVisible = false,
                        ),
                    onBack = {},
                    onRequestStop = {},
                    onDismissStop = {},
                    onConfirmStop = {},
                )
            }
        }

        val rowHeight =
            composeRule
                .onNodeWithTag("scan-phase-COMPARING_CANDIDATES")
                .performScrollTo()
                .fetchSemanticsNode()
                .boundsInRoot
                .height
        assertTrue("Scan phase row height was $rowHeight px at 200% font scale", rowHeight > 56f)
    }

    @Test
    fun exclusionsUseOneAddActionAndExposeRemovalCallbacks() {
        var removed: String? = null
        var folderChoices = 0
        setContent {
            ExclusionsScreen(
                state =
                    ExclusionsUiState(
                        folders =
                            listOf(
                                ExclusionItemUiState(
                                    id = "folder",
                                    kind = ExclusionKind.FOLDER,
                                    displayName = "Screenshots",
                                    canonicalPath = "/storage/Pictures/Screenshots",
                                ),
                            ),
                        files = emptyList(),
                        addSheetVisible = true,
                    ),
                onRequestAdd = {},
                onDismissAddSheet = {},
                onChooseFolder = { folderChoices += 1 },
                onChooseFile = {},
                onRemove = { removed = it },
            )
        }

        composeRule.onNodeWithText("Screenshots").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove Screenshots").performClick()
        composeRule.onNodeWithText("Choose folder").performClick()

        assertEquals("folder", removed)
        assertEquals(1, folderChoices)
    }

    @Test
    fun settingsSwitchesAndDetailRowsInvokeCallbacks() {
        var hidden = false
        var openedLocations = 0
        setContent {
            SettingsScreen(
                state =
                    SettingsUiState(
                        scanLocationsSummary = "Internal storage",
                        scanHiddenFolders = false,
                        ignoreSystemFolders = true,
                        autoSelectDuplicateCopies = true,
                        confirmBeforeDelete = true,
                        fileTypesSummary = "Photos / Videos / Audio / Documents",
                        storagePermissionGranted = true,
                        appVersion = "1.0.0",
                    ),
                onBack = {},
                onOpenScanLocations = { openedLocations += 1 },
                onScanHiddenFoldersChange = { hidden = it },
                onIgnoreSystemFoldersChange = {},
                onAutoSelectChange = {},
                onConfirmBeforeDeleteChange = {},
                onOpenExclusions = {},
                onOpenFileTypes = {},
                onOpenStoragePermission = {},
            )
        }

        composeRule.onNodeWithText("Scan locations").performScrollTo().performClick()
        composeRule.onNodeWithText("Scan hidden folders").performScrollTo().performClick()

        assertEquals(1, openedLocations)
        assertEquals(true, hidden)
    }

    @Test
    fun genericPreviewExplainsUnavailableContentWithoutChangingTheFile() {
        var opens = 0
        setContent {
            GenericPreviewScreen(
                state =
                    GenericPreviewUiState(
                        filename = "archive.zip",
                        path = "/storage/Download/archive.zip",
                        sizeBytes = 1_000L,
                        dateEpochMillis = 1_715_949_000_000L,
                        availability = PreviewAvailability.UNSUPPORTED,
                        canOpenExternally = true,
                    ),
                onBack = {},
                onOpenExternally = { opens += 1 },
            )
        }

        composeRule.onNodeWithText("A preview is not available for this file.").assertIsDisplayed()
        composeRule.onNodeWithText("Open with another app").performClick()
        assertEquals(1, opens)
    }

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            DuplicatesTheme(content = content)
        }
    }
}
