package com.emma.duplicates.ui.components

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import coil3.size.Size
import coil3.video.VideoFrameDecoder
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.ui.model.UiFileCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaThumbnailTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `video thumbnail pipeline registers frame decoder and requests thumbnail dimensions`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val imageLoader = buildVideoThumbnailImageLoader(context)
            val request = buildVideoThumbnailRequest(context, "content://media/external/video/media/42", 144)

            assertTrue(imageLoader.components.decoderFactories.any { it is VideoFrameDecoder.Factory })
            assertEquals(Size(144, 144), request.sizeResolver.size())
            assertNotNull(request.decoderFactory)

            imageLoader.shutdown()
        }

    @Test
    fun `video thumbnail has a decorative play indicator`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imageLoader = buildVideoThumbnailImageLoader(context)
        composeRule.setContent {
            DuplicatesTheme {
                MediaThumbnail(
                    category = UiFileCategory.VIDEOS,
                    thumbnailModel = "content://media/external/video/media/42",
                    audioContentUri = null,
                    contentDescription = "Video preview",
                    videoImageLoader = imageLoader,
                    audioArtworkVisible = true,
                )
            }
        }

        val indicator = composeRule.onNodeWithTag("video-play-indicator").fetchSemanticsNode()
        assertFalse(indicator.config.contains(SemanticsProperties.ContentDescription))
        imageLoader.shutdown()
    }
}
