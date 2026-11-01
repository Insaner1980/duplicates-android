package com.emma.duplicates.app

import android.app.Application
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.emma.duplicates.R
import com.emma.duplicates.core.database.DuplicateGroupEntity
import com.emma.duplicates.core.database.DuplicateMemberEntity
import com.emma.duplicates.core.database.IndexedFileEntity
import com.emma.duplicates.core.database.ScanSessionEntity
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.domain.deletion.MediaDeletionAuthorizer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h900dp-mdpi")
class AppNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer
    private val openScanningRequests = MutableSharedFlow<ScanNavigationRequest>(extraBufferCapacity = 1)
    private val fontScale = mutableFloatStateOf(1f)
    private val deletionAuthorizer = mockk<MediaDeletionAuthorizer>(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(WorkManager.Companion)
        val manager = mockk<WorkManager>(relaxed = true)
        every { manager.getWorkInfosForUniqueWorkFlow(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        every { WorkManager.getInstance(any()) } returns manager
        mockkStatic(Environment::class)
        every { Environment.isExternalStorageManager() } returns false
        container = AppContainer(application)
        runBlocking(Dispatchers.IO) {
            container.scanStore.clearScanMetadata()
            container.preferencesRepository.setScanHiddenFolders(false)
        }
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.floatValue)) {
                DuplicatesTheme {
                    AppNavigation(container, deletionAuthorizer, {}, {}, false, openScanningRequests)
                }
            }
        }
    }

    @After
    fun tearDown() {
        if (::container.isInitialized) container.database.close()
        unmockkObject(WorkManager.Companion)
        unmockkStatic(Environment::class)
    }

    @Test
    fun settingsAndExclusionsReturnThroughTheirParents() = checkSettingsNavigation()

    @Test
    @Config(qualifiers = "w320dp-h900dp-mdpi")
    fun settingsAndExclusionsAt320dpAndDoubleFontScale() = checkSettingsNavigation()

    private fun checkSettingsNavigation() {
        for (scale in listOf(1f, 2f)) {
            composeRule.runOnIdle { fontScale.floatValue = scale }
            awaitText(R.string.grant_storage_access)
            composeRule.onNodeWithContentDescription(text(R.string.settings)).performScrollTo().performClick()
            composeRule.onNodeWithText(text(R.string.scan_locations)).performScrollTo().performClick()
            awaitText(R.string.scan_locations)
            back()
            val hiddenFolders = composeRule.onNodeWithText(text(R.string.scan_hidden_folders))
            hiddenFolders.performScrollTo().performClick()
            composeRule.waitUntil(10_000) {
                hiddenFolders.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On
            }
            hiddenFolders.performClick()
            composeRule.onNodeWithText(text(R.string.types_to_scan)).performScrollTo().performClick()
            awaitText(R.string.photos_supporting)
            back()
            composeRule.onNodeWithText(text(R.string.exclusions)).performScrollTo().performClick()
            awaitText(R.string.no_excluded_folders)
            composeRule.onNodeWithContentDescription(text(R.string.add_exclusion)).performClick()
            click(R.string.choose_folder)
            awaitText(R.string.choose_folder)
            back()
            back()
            composeRule.onNodeWithContentDescription(text(R.string.back)).performScrollTo().performClick()
            awaitText(R.string.grant_storage_access)
        }
    }

    @Test
    fun completionOpensInlineResultsAndBothBackPathsLeaveScanningBehind() {
        seedSavedResults(container, application)
        for (systemBack in listOf(false, true)) {
            composeRule.runOnIdle { openScanningRequests.tryEmit(ScanNavigationRequest()) }
            awaitText(R.string.available_to_free)
            composeRule.onNodeWithTag("results-list").performScrollToNode(hasTestTag("preview-file-1"))
            composeRule.onNodeWithTag("preview-file-1").performClick()
            awaitText(R.string.file_preview)
            back()
            composeRule.onNodeWithTag("preview-file-1").assertIsDisplayed()
            if (systemBack) {
                composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
            } else {
                back()
            }
            awaitText(R.string.grant_storage_access)
            composeRule.onNodeWithText(text(R.string.scan_complete)).assertDoesNotExist()
        }
    }

    private fun text(
        resource: Int,
        vararg arguments: Any,
    ): String = application.getString(resource, *arguments)

    private fun click(resource: Int) {
        composeRule.onNodeWithText(text(resource)).performClick()
    }

    private fun back() {
        composeRule.onNodeWithContentDescription(text(R.string.back)).performClick()
    }

    private fun awaitText(resource: Int) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text(resource)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text(resource)).assertIsDisplayed()
    }
}

private fun createNavigationFiles(application: Application): List<IndexedFileEntity> =
    listOf("original.txt", "copy.txt").mapIndexed { index, name ->
        val file = File(application.filesDir, name).apply { writeText("navigation fixture") }
        IndexedFileEntity(
            id = "file-$index",
            sessionId = "navigation",
            canonicalPath = file.canonicalPath,
            displayName = name,
            extension = "txt",
            mimeType = "text/plain",
            category = "documents",
            sizeBytes = file.length(),
            lastModified = file.lastModified(),
            volume = "primary",
            parentPath = file.parent!!,
            readable = true,
            writable = true,
        )
    }

private fun seedSavedResults(
    container: AppContainer,
    application: Application,
) {
    val files = createNavigationFiles(application)
    runBlocking(Dispatchers.IO) {
        val dao = container.database.scanDao()
        dao.insertSession(
            ScanSessionEntity(
                id = "navigation",
                status = ScanStatuses.COMPLETED,
                startedAt = 1L,
                completedAt = 2L,
                scannedFileCount = 2,
                duplicateFileCount = 1,
                duplicateGroupCount = 1,
                reclaimableBytes = files.first().sizeBytes,
                active = true,
            ),
        )
        dao.insertIndexedFiles(files)
        dao.insertGroups(
            listOf(
                DuplicateGroupEntity(
                    id = "navigation-group",
                    sessionId = "navigation",
                    category = "documents",
                    contentHash = "fixture",
                    fileSize = files.first().sizeBytes,
                    copyCount = 2,
                    reclaimableBytes = files.first().sizeBytes,
                    displayTitle = "original.txt",
                ),
            ),
        )
        dao.insertMembers(
            files.mapIndexed { index, file ->
                DuplicateMemberEntity(
                    id = "member-$index",
                    groupId = "navigation-group",
                    indexedFileId = file.id,
                    recommendedKeep = index == 0,
                    selectedForDeletion = index != 0,
                    protectedFromAutoSelection = false,
                )
            },
        )
    }
}
