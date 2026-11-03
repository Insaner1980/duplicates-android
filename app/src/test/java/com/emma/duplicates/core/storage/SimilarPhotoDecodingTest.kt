package com.emma.duplicates.core.storage

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import com.emma.duplicates.core.database.ToolItemEntity
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SimilarPhotoDecodingTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Before
    fun bridgeImageSource() {
        // Robolectric cannot decode Android file descriptors on Windows; use the same image bytes.
        mockkStatic(ImageDecoder::class)
        every { ImageDecoder.createSource(any<File>()) } answers {
            ImageDecoder.createSource(ByteBuffer.wrap(firstArg<File>().readBytes()))
        }
    }

    @After
    fun restoreImageSource() = unmockkStatic(ImageDecoder::class)

    @Test
    fun actualImagesAreFingerprintedGroupedAndFavoriteBecomesKeeper() =
        runTest {
            val first = image("first.png", detailed = true)
            val favorite = image("favorite.png", detailed = true)
            val flat = image("flat.png", detailed = false)
            val broken = first.copy(id = "broken", canonicalPath = first.canonicalPath + ".missing")
            val progress = mutableListOf<Int>()
            val metadata = FileMetadataReader { PlatformFileMetadata(isFavorite = it.name == favorite.name) }
            val result =
                SimilarPhotoScanner(metadata).scan(
                    ToolDiscoveryResult(listOf(first, favorite, flat, broken), true, 0, 0),
                ) { progress += it }
            assertEquals(setOf(first.id, favorite.id), result.items.map { it.id }.toSet())
            assertEquals(favorite.id, result.items.single { it.keeper }.id)
            assertEquals(2, result.skippedCount)
            assertEquals(listOf(1, 2, 3, 4), progress)
            assertTrue(result.items.none { it.selected })
            assertTrue(
                result.items.all { it.width == 144 && it.height == 128 && it.fingerprintVersion == PHOTO_HASH_VERSION },
            )
        }

    @Test
    fun lowDetailAndInvalidImagesAreNotReportedAsMatchingPhotos() {
        assertNull(fingerprintPhoto(image("plain.png", detailed = false)))
        val detailed = image("detail.png", detailed = true)
        assertNotNull(fingerprintPhoto(detailed))
        java.io.File(detailed.canonicalPath).writeText("not an image")
        assertNull(fingerprintPhoto(detailed))
    }

    private fun image(
        name: String,
        detailed: Boolean,
    ): ToolItemEntity {
        val file = temporaryFolder.newFile(name)
        val bitmap = Bitmap.createBitmap(144, 128, Bitmap.Config.ARGB_8888)
        try {
            for (index in 0 until bitmap.width * bitmap.height) {
                val x = index % bitmap.width
                val y = index / bitmap.width
                val shade = if (detailed && (x / 16 + y / 16) % 2 == 0) 0 else 255
                bitmap.setPixel(x, y, Color.rgb(shade, shade, shade))
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return ToolItemEntity(
            "run",
            name,
            "primary",
            file.canonicalPath,
            name,
            file.length(),
            file.lastModified(),
            "PHOTOS",
        )
    }
}
