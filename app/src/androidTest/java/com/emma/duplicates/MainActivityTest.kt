package com.emma.duplicates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emma.duplicates.app.AppContainer
import com.emma.duplicates.app.DuplicatesApplication
import com.emma.duplicates.data.preferences.AppPreferences
import com.emma.duplicates.data.preferences.AppThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsAppearanceAndBackAreUsable() {
        composeRule.onNodeWithContentDescription("Settings").performScrollTo().performClick()
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeRule.onNodeWithText("Light").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Back").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        val container = (composeRule.activity.application as DuplicatesApplication).container
        runBlocking { container.preferencesRepository.setThemeMode(AppThemeMode.DARK) }
    }

    @Test
    fun fixtureOnlyScanOpensResultsPreservesPreviewAndDeletesOnlyReviewedCopies() {
        val activity = composeRule.activity
        val container = (activity.application as DuplicatesApplication).container
        assumeTrue(
            "Fixture scan requires an empty test installation",
            runBlocking {
                container.scanStore.latestSession.first() ==
                    null
            },
        )
        val root = File(Environment.getExternalStorageDirectory(), "DuplicatesUiRedesignFixture")
        check(!root.exists()) { "Fixture directory already exists; it is not owned by this run" }
        val previousPreferences = runBlocking { container.preferencesRepository.preferences.first() }
        val display = captureDisplaySettings()
        shell("appops set ${activity.packageName} MANAGE_EXTERNAL_STORAGE allow")
        shell("pm grant ${activity.packageName} android.permission.POST_NOTIFICATIONS")
        check(root.mkdir())
        var scanStarted = false
        try {
            prepareScope(container)
            createFixtureFiles(root)
            // Refresh permission state through the real activity lifecycle.
            composeRule.activityRule.scenario.recreate()
            selectScanFolder(root, container)
            scanStarted = true
            composeRule.onNodeWithText("Scan files").performScrollTo().performClick()
            composeRule.waitUntil(
                60_000,
            ) { composeRule.onAllNodesWithText("Available to free").fetchSemanticsNodes().isNotEmpty() }
            verifyResultsAndPreview(container)
            captureLayoutEvidence(container)
            restoreDisplay(display)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Previous scan").performScrollTo().performClick()
            composeRule.onNodeWithTag("delete-selected").performClick()
            composeRule.onNodeWithText("Delete permanently").performClick()
            composeRule.waitUntil(30_000) { root.listFiles().orEmpty().size == 2 }
            assertEquals(1, root.listFiles().orEmpty().count { it.extension == "png" })
            assertEquals(1, root.listFiles().orEmpty().count { it.extension == "txt" })
        } finally {
            restoreDisplay(display)
            cleanupFixture(root, container, previousPreferences, scanStarted)
        }
    }

    private fun prepareScope(container: AppContainer) {
        runBlocking {
            container.preferencesRepository.setScanPhotos(true)
            container.preferencesRepository.setScanVideos(false)
            container.preferencesRepository.setScanAudio(false)
            container.preferencesRepository.setScanDocuments(true)
            container.preferencesRepository.setScanSelection(false, emptySet(), setOf("primary"))
            container.preferencesRepository.setAutoSelectDuplicateCopies(true)
            container.preferencesRepository.setConfirmBeforeDelete(true)
            container.preferencesRepository.setThemeMode(AppThemeMode.DARK)
        }
    }

    private fun selectScanFolder(
        root: File,
        container: AppContainer,
    ) {
        composeRule.onNodeWithContentDescription("Settings").performScrollTo().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Scan locations").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Scan locations").performScrollTo().performClick()
        composeRule.onNodeWithText("Selected folders and files").performClick()
        val volume = container.storageVolumeSource.mountedVolumes().first { it.isPrimary }
        composeRule.onNodeWithText(volume.label).performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(root.name).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(root.name).performScrollTo().performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Lake.png").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithContentDescription("Select this folder").performClick()
        screenshot("locations-dark.png")
        runBlocking { container.preferencesRepository.setThemeMode(AppThemeMode.LIGHT) }
        composeRule.waitForIdle()
        screenshot("locations-light.png")
        runBlocking { container.preferencesRepository.setThemeMode(AppThemeMode.DARK) }
        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitUntil(
            10_000,
        ) { composeRule.onAllNodesWithText("Appearance").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithContentDescription("Back").performScrollTo().performClick()
        assertEquals(
            setOf(root.canonicalPath),
            runBlocking {
                container.preferencesRepository.preferences
                    .first()
                    .selectedScanPaths
            },
        )
    }

    private fun createFixtureFiles(root: File) {
        val original = File(root, "Lake.png")
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(35, 131, 191))
        val paint = Paint().apply { color = Color.rgb(25, 92, 67) }
        canvas.drawRect(0f, 60f, 160f, 90f, paint)
        paint.color = Color.rgb(24, 62, 89)
        canvas.drawRect(0f, 90f, 160f, 120f, paint)
        original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        original.copyTo(File(root, "Lake copy.png"))
        File(root, "Notes.txt").writeText("Fixture notes for duplicate review.\n".repeat(40))
        File(root, "Notes.txt").copyTo(File(root, "Notes copy.txt"))
    }

    private fun verifyResultsAndPreview(container: AppContainer) {
        val results = runBlocking { container.scanStore.activeResults.first()!! }
        assertEquals(2, results.groups.size)
        assertEquals(4, results.groups.sumOf { it.members.size })
        screenshot("results-dark.png")
        val file =
            results.groups
                .first()
                .members
                .first()
                .file
        composeRule.onNodeWithTag("results-list").performScrollToNode(hasTestTag("preview-${file.id}"))
        composeRule.onNodeWithTag("preview-${file.id}").performClick()
        composeRule.onNodeWithContentDescription("Back").performScrollTo().performClick()
        composeRule.onNodeWithTag("preview-${file.id}").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
    }

    private fun captureLayoutEvidence(container: AppContainer) {
        screenshot("home-dark.png")
        runBlocking { container.preferencesRepository.setThemeMode(AppThemeMode.LIGHT) }
        composeRule.waitForIdle()
        screenshot("home-light.png")
        composeRule.onNodeWithText("Previous scan").performScrollTo().performClick()
        screenshot("results-light.png")
        shell("wm size 840x2100")
        shell("wm density 420")
        shell("settings put system font_scale 2.0")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("delete-selected").assertIsDisplayed()
        screenshot("results-320dp-200percent.png")
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Scan files").performScrollTo().assertIsDisplayed()
        screenshot("home-320dp-200percent.png")
    }

    private fun cleanupFixture(
        root: File,
        container: AppContainer,
        previousPreferences: AppPreferences,
        scanStarted: Boolean,
    ) {
        runBlocking {
            if (scanStarted) {
                container.scanScheduler.stopScan()
                kotlinx.coroutines.withTimeout(30_000) { container.scanScheduler.workActive.first { !it } }
            }
            container.preferencesRepository.setScanPhotos(previousPreferences.scanPhotos)
            container.preferencesRepository.setScanVideos(previousPreferences.scanVideos)
            container.preferencesRepository.setScanAudio(previousPreferences.scanAudio)
            container.preferencesRepository.setScanDocuments(previousPreferences.scanDocuments)
            container.preferencesRepository.setScanSelection(
                previousPreferences.scanSelectedItemsOnly,
                previousPreferences.selectedScanPaths,
                previousPreferences.selectedVolumeIds,
            )
            container.preferencesRepository.setAutoSelectDuplicateCopies(
                previousPreferences.autoSelectDuplicateCopies,
            )
            container.preferencesRepository.setConfirmBeforeDelete(previousPreferences.confirmBeforeDelete)
            container.preferencesRepository.setThemeMode(previousPreferences.themeMode)
            container.scanStore.clearScanMetadata()
        }
        // The directory was created by this test and contains only its four named files.
        root.listFiles().orEmpty().forEach {
            check(it.isFile && it.parentFile!!.canonicalFile == root.canonicalFile)
            it.delete()
        }
        check(root.delete())
    }

    private data class DisplaySettings(
        val font: String,
        val size: String?,
        val density: String?,
    )

    private fun captureDisplaySettings() =
        DisplaySettings(
            shell("settings get system font_scale").trim(),
            shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }?.substringAfter(": "),
            shell("wm density").lineSequence().firstOrNull { it.startsWith("Override density:") }?.substringAfter(": "),
        )

    private fun restoreDisplay(display: DisplaySettings) {
        shell("settings put system font_scale ${display.font}")
        shell(display.size?.let { "wm size $it" } ?: "wm size reset")
        shell(display.density?.let { "wm density $it" } ?: "wm density reset")
    }

    private fun shell(command: String): String =
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                android.os.ParcelFileDescriptor
                    .AutoCloseInputStream(descriptor)
                    .bufferedReader()
                    .readText()
            }

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory =
            File(
                instrumentation.targetContext.getExternalFilesDir(null),
                "ui-refinement-evidence",
            ).apply { mkdirs() }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(directory, name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
