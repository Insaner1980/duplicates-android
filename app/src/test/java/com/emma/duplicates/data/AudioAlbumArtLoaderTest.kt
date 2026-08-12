package com.emma.duplicates.data

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class AudioAlbumArtLoaderTest {
    @Test
    fun `loads requested album thumbnail from the audio content URI album ID`() =
        runTest {
            val resolver = mockk<ContentResolver>()
            val indexedFileUri = Uri.parse("content://media/external_primary/file/42")
            val audioUri = MediaStore.Audio.Media.getContentUri("external_primary", 42L)
            val albumUri =
                ContentUris.withAppendedId(
                    MediaStore.Audio.Albums.getContentUri("external_primary"),
                    7L,
                )
            val projection = arrayOf(MediaStore.Audio.Media.ALBUM_ID)
            val cursor = MatrixCursor(projection).apply { addRow(arrayOf(7L)) }
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            every { resolver.query(audioUri, any<Array<String>>(), null, null, null) } returns cursor
            every { resolver.loadThumbnail(albumUri, Size(72, 72), null) } returns bitmap

            val result = AudioAlbumArtLoader(resolver, Dispatchers.Unconfined).load(indexedFileUri.toString(), 72)

            assertSame(bitmap, result)
            verify(exactly = 1) {
                resolver.query(
                    audioUri,
                    match<Array<String>> { it.contentEquals(projection) },
                    null,
                    null,
                    null,
                )
            }
            verify(exactly = 1) { resolver.loadThumbnail(albumUri, Size(72, 72), null) }
        }

    @Test
    fun `returns no album art when the media provider denies access`() =
        runTest {
            val resolver = mockk<ContentResolver>()
            val audioUri = Uri.parse("content://media/external/audio/media/42")
            every { resolver.query(audioUri, any<Array<String>>(), null, null, null) } throws SecurityException()

            val result = AudioAlbumArtLoader(resolver, Dispatchers.Unconfined).load(audioUri.toString(), 72)

            assertNull(result)
            verify(exactly = 0) { resolver.loadThumbnail(any(), any(), null) }
        }
}
