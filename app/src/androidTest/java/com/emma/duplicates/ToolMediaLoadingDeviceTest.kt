package com.emma.duplicates

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolResults
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.ui.components.buildVideoThumbnailImageLoader
import com.emma.duplicates.ui.components.buildVideoThumbnailRequest
import com.emma.duplicates.ui.tools.ToolResultsScreen
import com.emma.duplicates.ui.tools.ToolScreenState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ToolMediaLoadingDeviceTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun screenshotGridLoadsFileWithoutPreviewHandler() {
        val file = File.createTempFile("screenshot-render-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 120, 130))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            showScreenshots(file)
            composeRule.waitUntil(10_000) {
                val image = composeRule.onNodeWithContentDescription("Preview ${file.name}").captureToImage()
                val pixel = image.asAndroidBitmap().getPixel(image.width / 2, image.height / 2)
                // Check the teal content rather than exact RGB values after device color conversion.
                Color.green(pixel) > Color.red(pixel) + 20 && Color.blue(pixel) > Color.red(pixel) + 20
            }
        } finally {
            check(file.delete())
        }
    }

    @Test fun missingScreenshotShowsPreviewUnavailable() {
        val file = File.createTempFile("missing-screenshot-", ".png", context.cacheDir)
        check(file.delete())
        showScreenshots(file)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Preview unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Preview unavailable").assertIsDisplayed()
    }

    @Test fun videoThumbnailSkipsBlackOpening() {
        val file = File.createTempFile("video-render-", ".mp4", context.cacheDir)
        instrumentation.context.assets.open("black-intro-video.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }
        val loader = buildVideoThumbnailImageLoader(context)
        try {
            val diagnostic = InstrumentationRegistry.getArguments().getString("diagnosticVideo")?.let(::File)
            for (video in listOfNotNull(file, diagnostic)) {
                val request = buildVideoThumbnailRequest(context, video, 96).newBuilder().allowHardware(false).build()
                val result = runBlocking { loader.execute(request) }
                assertTrue("Video decode failed: ${(result as? ErrorResult)?.throwable}", result is SuccessResult)
                val image = (result as SuccessResult).image.toBitmap()
                val pixel = image.getPixel(image.width / 2, image.height / 2)
                assertTrue(
                    "Thumbnail contains the black opening frame",
                    Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > 30,
                )
            }
        } finally {
            loader.shutdown()
            check(file.delete())
        }
    }

    private fun showScreenshots(file: File) {
        val item = ToolItemEntity("run", "photo", "fixture", file.path, file.name, file.length(), 1L, "PHOTOS")
        val state =
            ToolScreenState(
                CleanupTool.SCREENSHOTS,
                ToolResults(
                    ToolScanSession(
                        "run",
                        CleanupTool.SCREENSHOTS.name,
                        System.currentTimeMillis(),
                        ToolScope(emptyList()).encode(),
                    ),
                    listOf(item),
                ),
                hasAccess = true,
            )
        composeRule.setContent {
            DuplicatesTheme {
                ToolResultsScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {})
            }
        }
    }
}
