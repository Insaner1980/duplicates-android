package com.emma.duplicates.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.ui.components.RootDestination
import com.emma.duplicates.ui.components.RootNavigationBar
import com.emma.duplicates.ui.exclusions.ExclusionItemUiState
import com.emma.duplicates.ui.exclusions.ExclusionKind
import com.emma.duplicates.ui.exclusions.ExclusionsScreen
import com.emma.duplicates.ui.exclusions.ExclusionsUiState
import com.emma.duplicates.ui.home.CompletedHomeUiState
import com.emma.duplicates.ui.home.HomeCategorySummaryUiState
import com.emma.duplicates.ui.home.HomeScreen
import com.emma.duplicates.ui.home.HomeUiState
import com.emma.duplicates.ui.model.UiFileCategory
import com.emma.duplicates.ui.preview.GenericPreviewScreen
import com.emma.duplicates.ui.preview.GenericPreviewUiState
import com.emma.duplicates.ui.preview.PreviewAvailability
import com.emma.duplicates.ui.results.DuplicateGroupCardUiState
import com.emma.duplicates.ui.results.ResultsFilter
import com.emma.duplicates.ui.results.ResultsScreen
import com.emma.duplicates.ui.results.ResultsSort
import com.emma.duplicates.ui.results.ResultsUiState
import com.emma.duplicates.ui.review.ReviewDuplicatesScreen
import com.emma.duplicates.ui.review.ReviewMemberUiState
import com.emma.duplicates.ui.review.ReviewUiState
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
    fun rootNavigationAnnouncesSelectionAndInvokesDestinationCallback() {
        var selected: RootDestination? = null
        setContent {
            RootNavigationBar(
                selectedDestination = RootDestination.RESULTS,
                onDestinationSelected = { selected = it },
            )
        }

        composeRule.onNodeWithText("Results").assertIsSelected()
        composeRule.onNodeWithText("Exclusions").performClick()

        assertEquals(RootDestination.EXCLUSIONS, selected)
    }

    @Test
    fun homeRendersRealActiveCompletedAndNoDuplicateStates() {
        val homeState =
            mutableStateOf<HomeUiState>(
                HomeUiState.Completed(
                    CompletedHomeUiState(
                        reclaimableBytes = 1_500_000_000L,
                        duplicateFileCount = 12,
                        duplicateGroupCount = 4,
                        totalStorageBytes = 256_000_000_000L,
                        freeStorageBytes = 80_000_000_000L,
                        lastScanEpochMillis = 1_715_949_000_000L,
                        categories =
                            listOf(
                                HomeCategorySummaryUiState(UiFileCategory.PHOTOS, 8, 1_000_000_000L),
                                HomeCategorySummaryUiState(UiFileCategory.DOCUMENTS, 4, 500_000_000L),
                            ),
                    ),
                ),
            )
        setContent {
            HomeScreen(
                state = homeState.value,
                onOpenSettings = {},
                onGrantAccess = {},
                onStartScan = {},
                onViewScan = {},
                onReviewDuplicates = {},
                onCategorySelected = {},
                onScanAgain = {},
            )
        }

        composeRule.onNodeWithText("Space you can reclaim").assertIsDisplayed()
        composeRule.onAllNodesWithText("1.5 GB")[0].assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(
                "256 GB total storage. 1.5 GB can be reclaimed. 80 GB is free.",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("256 GB").assertDoesNotExist()
        composeRule.onNodeWithText("Review duplicates").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Duplicates by type").performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle {
            homeState.value = HomeUiState.NoDuplicates(lastScanEpochMillis = 1_715_949_000_000L)
        }
        composeRule.onNodeWithText("No duplicates found").assertIsDisplayed()
        composeRule.onNodeWithText("Scan again").assertIsDisplayed()

        composeRule.runOnIdle {
            homeState.value = HomeUiState.NoMatchingFiles(lastScanEpochMillis = 1_715_949_000_000L)
        }
        composeRule.onNodeWithText("No files matching selected types").assertIsDisplayed()
        composeRule
            .onNodeWithText("Check the selected file types and scan locations, then scan again.")
            .assertIsDisplayed()
    }

    @Test
    fun activeHomeOpensScanningFromViewScanAction() {
        var openedScanning = false
        setContent {
            HomeScreen(
                state =
                    HomeUiState.ActiveScan(
                        phase = "Comparing candidates",
                        progress = 0.5f,
                        progressLabel = "50%",
                        filesIndexed = 24,
                    ),
                onOpenSettings = {},
                onGrantAccess = {},
                onStartScan = {},
                onViewScan = { openedScanning = true },
                onReviewDuplicates = {},
                onCategorySelected = {},
                onScanAgain = {},
            )
        }

        composeRule.onNodeWithText("View scan").performClick()

        assertTrue(openedScanning)
    }

    @Test
    fun completedHomeReflowsWithoutOverlappingStorageValuesAtDoubleFontScale() {
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                HomeScreen(
                    state =
                        HomeUiState.Completed(
                            CompletedHomeUiState(
                                reclaimableBytes = 2_000_000_000L,
                                duplicateFileCount = 4,
                                duplicateGroupCount = 2,
                                totalStorageBytes = 10_000_000_000L,
                                freeStorageBytes = 3_000_000_000L,
                                lastScanEpochMillis = 1_715_949_000_000L,
                                categories = emptyList(),
                            ),
                        ),
                    onOpenSettings = {},
                    onGrantAccess = {},
                    onStartScan = {},
                    onViewScan = {},
                    onReviewDuplicates = {},
                    onCategorySelected = {},
                    onScanAgain = {},
                )
            }
        }

        composeRule.onNodeWithText("Other used storage").performScrollTo().assertIsDisplayed()
        val labelBounds =
            composeRule.onNodeWithText("Other used storage").fetchSemanticsNode().boundsInRoot
        val valueBounds = composeRule.onNodeWithText("5 GB").fetchSemanticsNode().boundsInRoot

        assertTrue("Storage label and value overlap at 200% font scale", labelBounds.right <= valueBounds.left)
        composeRule.onNodeWithText("Review duplicates").performScrollTo().assertIsDisplayed()
    }

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
        composeRule.onNodeWithText("Stop scan").performClick()

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
    fun resultsExposeSearchFiltersReviewAndClearConfirmation() {
        var selectedFilter: ResultsFilter? = null
        var query = ""
        var reviewedId: String? = null
        var clears = 0
        val clearVisible = mutableStateOf(false)
        setContent {
            ResultsScreen(
                state =
                    ResultsUiState(
                        hasCompletedScan = true,
                        duplicateFileCount = 3,
                        duplicateGroupCount = 1,
                        reclaimableBytes = 48_000_000L,
                        selectedFilter = ResultsFilter.ALL,
                        selectedSort = ResultsSort.LARGEST_RECLAIMABLE,
                        searchVisible = true,
                        searchQuery = "",
                        clearConfirmationVisible = clearVisible.value,
                        groups =
                            listOf(
                                DuplicateGroupCardUiState(
                                    id = "group-1",
                                    title = "mountain.jpg",
                                    category = UiFileCategory.PHOTOS,
                                    copyCount = 3,
                                    reclaimableBytes = 48_000_000L,
                                    paths = listOf("/storage/DCIM/Camera", "/storage/Pictures"),
                                    thumbnailModel = null,
                                ),
                            ),
                    ),
                onSearchVisibilityChange = {},
                onSearchQueryChange = { query = it },
                onFilterSelected = { selectedFilter = it },
                onSortSelected = {},
                onOverflowVisibilityChange = {},
                onSortVisibilityChange = {},
                onPreviewFile = { _, _ -> },
                onReviewGroup = { reviewedId = it },
                onScanAgain = {},
                onRequestClear = {},
                onDismissClear = {},
                onConfirmClear = { clears += 1 },
            )
        }

        composeRule.onNodeWithText("Results").assertIsDisplayed()
        composeRule.onNodeWithText("Scan results").assertIsDisplayed()
        composeRule.onNodeWithTag("results-list").performScrollToNode(hasText("mountain.jpg"))
        composeRule.onNodeWithText("mountain.jpg").assertIsDisplayed()
        composeRule.onNodeWithTag("results-list").performScrollToNode(hasText("Photos"))
        composeRule.onNodeWithText("Photos").performClick()
        composeRule.onNodeWithTag("results-list").performScrollToNode(hasText("Review"))
        composeRule.onNodeWithText("Review").performClick()
        composeRule
            .onNodeWithTag("results-list")
            .performScrollToNode(hasText("Search duplicate groups"))
        composeRule.onNodeWithText("Search duplicate groups").performTextInput("mountain")
        composeRule.runOnIdle { clearVisible.value = true }
        composeRule.onNodeWithText("Clear results").performClick()

        assertEquals(ResultsFilter.PHOTOS, selectedFilter)
        assertEquals("group-1", reviewedId)
        assertEquals("mountain", query)
        assertEquals(1, clears)
    }

    @Test
    fun resultsThumbnailOpensItsExactPreviewFile() {
        var previewedFile: Pair<String, String>? = null
        setContent {
            ResultsScreen(
                state =
                    ResultsUiState(
                        hasCompletedScan = true,
                        duplicateFileCount = 2,
                        duplicateGroupCount = 1,
                        reclaimableBytes = 24_000_000L,
                        selectedFilter = ResultsFilter.ALL,
                        selectedSort = ResultsSort.LARGEST_RECLAIMABLE,
                        searchVisible = false,
                        searchQuery = "",
                        clearConfirmationVisible = false,
                        groups =
                            listOf(
                                DuplicateGroupCardUiState(
                                    id = "group-1",
                                    title = "mountain.jpg",
                                    category = UiFileCategory.PHOTOS,
                                    copyCount = 2,
                                    reclaimableBytes = 24_000_000L,
                                    paths = listOf("/storage/DCIM/Camera"),
                                    thumbnailModel = "content://media/photo-file",
                                    previewFileId = "photo-file",
                                ),
                            ),
                    ),
                onSearchVisibilityChange = {},
                onSearchQueryChange = {},
                onFilterSelected = {},
                onSortSelected = {},
                onOverflowVisibilityChange = {},
                onSortVisibilityChange = {},
                onPreviewFile = { groupId, fileId -> previewedFile = groupId to fileId },
                onReviewGroup = {},
                onScanAgain = {},
                onRequestClear = {},
                onDismissClear = {},
                onConfirmClear = {},
            )
        }

        composeRule.onNodeWithTag("results-list").performScrollToNode(hasText("mountain.jpg"))
        composeRule.onNodeWithContentDescription("Preview of mountain.jpg").performClick()

        assertEquals("group-1" to "photo-file", previewedFile)
    }

    @Test
    fun reviewBlocksSelectingTheFinalCopyAndAllowsOtherChanges() {
        var blocked = 0
        val changes = mutableListOf<Pair<String, Boolean>>()
        setContent {
            ReviewDuplicatesScreen(
                state =
                    ReviewUiState(
                        groupId = "group",
                        title = "photo.jpg",
                        category = UiFileCategory.PHOTOS,
                        identicalFileCount = 2,
                        reclaimableBytes = 100_000_000L,
                        selectedCount = 1,
                        selectedBytes = 50_000_000L,
                        members =
                            listOf(
                                ReviewMemberUiState(
                                    id = "kept",
                                    filename = "kept.jpg",
                                    sizeBytes = 50_000_000L,
                                    dateEpochMillis = 1_715_949_000_000L,
                                    path = "/storage/DCIM/Camera/kept.jpg",
                                    thumbnailModel = null,
                                    selectedForDeletion = false,
                                    recommendedKeep = true,
                                    canSelectForDeletion = false,
                                ),
                                ReviewMemberUiState(
                                    id = "selected",
                                    filename = "selected.jpg",
                                    sizeBytes = 50_000_000L,
                                    dateEpochMillis = 1_715_949_000_000L,
                                    path = "/storage/Download/selected.jpg",
                                    thumbnailModel = null,
                                    selectedForDeletion = true,
                                    recommendedKeep = false,
                                    canSelectForDeletion = true,
                                ),
                            ),
                    ),
                onBack = {},
                onPreviewMember = {},
                onMemberSelectionChange = { id, selected -> changes += id to selected },
                onSelectionBlocked = { blocked += 1 },
                onRequestDelete = {},
                onDismissDelete = {},
                onConfirmDelete = {},
                onDismissDeletionResult = {},
            )
        }

        composeRule.onNodeWithTag("review-member-kept").performClick()
        composeRule
            .onNodeWithTag("review-list")
            .performScrollToNode(hasTestTag("review-member-selected"))
        composeRule.onNodeWithTag("review-member-selected").performClick()

        assertEquals(1, blocked)
        assertEquals(listOf("selected" to false), changes)
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

        composeRule.onNodeWithText("Scan locations").performClick()
        composeRule.onNodeWithText("Scan hidden folders").performClick()

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
