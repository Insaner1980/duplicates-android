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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    private val openScanningRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val fontScale = mutableFloatStateOf(1f)
    private val deletionAuthorizer = mockk<MediaDeletionAuthorizer>(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any()) } returns mockk(relaxed = true)
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
    fun settingsAndRootNavigationRemainUsableAtNormalAndDoubleFontScale() {
        checkSettingsAndRootNavigation()
    }

    @Test
    @Config(qualifiers = "w320dp-h900dp-mdpi")
    fun settingsAndRootNavigationRemainUsableAtSmallWidthAndBothFontScales() {
        checkSettingsAndRootNavigation()
    }

    private fun checkSettingsAndRootNavigation() {
        for (scale in listOf(1f, 2f)) {
            composeRule.runOnIdle { fontScale.floatValue = scale }

            awaitText(R.string.grant_storage_access)
            click(R.string.results)
            awaitText(R.string.no_saved_scan_results)
            click(R.string.home)
            composeRule.onNodeWithContentDescription(text(R.string.settings)).performClick()

            click(R.string.scan_locations)
            awaitText(R.string.scan_locations)
            back()
            val hiddenFolders = composeRule.onNodeWithText(text(R.string.scan_hidden_folders))
            hiddenFolders.performScrollTo().performClick()
            composeRule.waitUntil(10_000) {
                hiddenFolders.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On
            }
            hiddenFolders.performClick()
            composeRule.waitUntil(10_000) {
                hiddenFolders.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.Off
            }
            composeRule.onNodeWithText(text(R.string.types_to_scan)).performScrollTo().performClick()
            awaitText(R.string.photos_supporting)
            back()
            back()

            click(R.string.exclusions)
            awaitText(R.string.no_excluded_folders)
            for (kind in listOf(R.string.choose_folder, R.string.choose_file)) {
                composeRule.onNodeWithContentDescription(text(R.string.add_exclusion)).performClick()
                click(kind)
                awaitText(kind)
                back()
                awaitText(R.string.no_excluded_files)
            }
            click(R.string.home)
            awaitText(R.string.grant_storage_access)
        }
    }

    @Test
    fun savedResultsOpenReviewAndPreviewAndReturnToTheirOrigin() {
        seedSavedResults(container, application)

        click(R.string.results)
        awaitText(R.string.review)
        composeRule
            .onNode(
                hasClickAction() and hasAnySibling(hasText("original.txt")) and !hasText(text(R.string.review)),
            ).performClick()
        awaitText(R.string.file_preview)
        back()
        awaitText(R.string.review)
        click(R.string.review)
        awaitText(R.string.review_duplicates)
        composeRule
            .onNodeWithContentDescription(text(R.string.preview_item_description, "original.txt"))
            .performClick()
        awaitText(R.string.file_preview)
        back()
        awaitText(R.string.review_duplicates)
        back()
        awaitText(R.string.review)

        every { Environment.isExternalStorageManager() } returns true
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        click(R.string.home)
        composeRule.onNodeWithText(text(R.string.review_duplicates)).performScrollTo().performClick()
        awaitText(R.string.review)
        click(R.string.home)
        composeRule.onNodeWithText(text(R.string.documents)).performScrollTo().performClick()
        awaitText(R.string.review)
        composeRule.onNodeWithText(text(R.string.documents)).performScrollTo().assertIsSelected()

        composeRule.runOnIdle { openScanningRequests.tryEmit(Unit) }
        awaitText(R.string.scan_complete)
        back()
        awaitText(R.string.home)
        composeRule.onNodeWithText(text(R.string.home)).assertIsSelected()
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
