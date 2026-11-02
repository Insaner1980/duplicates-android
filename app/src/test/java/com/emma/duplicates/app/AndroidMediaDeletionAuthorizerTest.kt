package com.emma.duplicates.app

import android.app.Application
import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.MediaAuthorization
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidMediaDeletionAuthorizerTest {
    @Test
    fun storedFileUrisResolveToTypedMediaUrisOnTheSameVolume() {
        val resolver = mockk<ContentResolver>()
        val uri = Uri.parse("content://media/external_primary/file/42")
        val types =
            listOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE to "images",
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO to "video",
                MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO to "audio",
            )
        types.forEach { (mediaType, collection) ->
            every { resolver.query(uri, any<Array<String>>(), null, null, null) } returns
                mediaRow(mediaType)

            assertEquals(
                Uri.parse("content://media/external_primary/$collection/media/42"),
                resolver.mediaDeletionUri(mediaFile(uri.toString())),
            )
        }
    }

    @Test
    fun typedMediaUriIsAlsoBoundToTheValidatedPath() {
        val resolver = mockk<ContentResolver>()
        val uri = Uri.parse("content://media/external/images/media/7")
        every { resolver.query(uri, any<Array<String>>(), null, null, null) } returns
            mediaRow(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)

        assertEquals(uri, resolver.mediaDeletionUri(mediaFile(uri.toString())))
    }

    @Test
    fun missingOrNonMediaRowsDoNotBecomeMediaUris() {
        val resolver = mockk<ContentResolver>()
        val uri = Uri.parse("content://media/external_primary/file/42")
        every { resolver.query(uri, any<Array<String>>(), null, null, null) } returns null
        assertNull(resolver.mediaDeletionUri(mediaFile(uri.toString())))

        every { resolver.query(uri, any<Array<String>>(), null, null, null) } returns
            MatrixCursor(arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DATA))
        assertNull(resolver.mediaDeletionUri(mediaFile(uri.toString())))

        every { resolver.query(uri, any<Array<String>>(), null, null, null) } returns
            mediaRow(0)
        assertNull(resolver.mediaDeletionUri(mediaFile(uri.toString())))
    }

    @Test
    fun movedOrUnresolvableMediaRowsCannotLaunchDeletion() =
        runTest {
            val resolver = mockk<ContentResolver>()
            val authorizer = AndroidMediaDeletionAuthorizer(resolver)
            val uris = listOf("content://media/external/file/42", "content://media/external/images/media/42")
            for (uri in uris) {
                for (path in listOf("/storage/emulated/0/Pictures/moved.jpg", null)) {
                    every { resolver.query(Uri.parse(uri), any<Array<String>>(), null, null, null) } returns
                        mediaRow(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, path)

                    assertEquals(MediaAuthorization.FAILED, authorizer.requestDeletion(listOf(mediaFile(uri))))
                    assertNull(authorizer.launchRequest.value)
                }
            }
        }

    @Test
    fun inaccessibleMediaRowFailsWithoutLaunching() =
        runTest {
            val resolver = mockk<ContentResolver>()
            every { resolver.query(any(), any<Array<String>>(), null, null, null) } throws SecurityException()
            val authorizer = AndroidMediaDeletionAuthorizer(resolver)

            assertEquals(MediaAuthorization.FAILED, authorizer.requestDeletion(listOf(mediaFile())))
            assertNull(authorizer.launchRequest.value)
        }

    private fun mediaRow(
        type: Int,
        path: String? = mediaFile().canonicalPath,
    ) = MatrixCursor(arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DATA)).apply {
        addRow(arrayOf<Any?>(type, path))
    }

    @Test
    fun pendingAuthorizationIsClaimedOnceAndCompletedAfterRecreation() =
        runTest {
            val launchRequest = mockk<IntentSenderRequest>()
            val authorizer = AndroidMediaDeletionAuthorizer { launchRequest }

            val result = async { authorizer.requestDeletion(listOf(mediaFile())) }
            yield()

            assertTrue(authorizer.hasPendingRequest)
            assertSame(launchRequest, authorizer.claimLaunchRequest())
            assertNull(authorizer.claimLaunchRequest())

            assertTrue(authorizer.complete(MediaAuthorization.APPROVED))

            assertEquals(MediaAuthorization.APPROVED, result.await())
            assertFalse(authorizer.hasPendingRequest)
            assertNull(authorizer.launchRequest.value)
        }

    @Test
    fun activityResultWithoutLiveContinuationIsReportedForRecovery() {
        val recreatedAuthorizer = AndroidMediaDeletionAuthorizer { mockk() }

        assertFalse(recreatedAuthorizer.complete(MediaAuthorization.APPROVED))
        assertFalse(recreatedAuthorizer.hasPendingRequest)
    }

    @Test
    fun invalidMediaRequestFailsWithoutLaunching() =
        runTest {
            val authorizer = AndroidMediaDeletionAuthorizer { mockk() }

            val result = authorizer.requestDeletion(listOf(mediaFile(contentUri = null)))

            assertEquals(MediaAuthorization.FAILED, result)
            assertNull(authorizer.launchRequest.value)
        }

    private fun mediaFile(contentUri: String? = "content://media/external/images/media/1") =
        DeletionFile(
            id = "file-1",
            canonicalPath = File("/storage/emulated/0/Pictures/copy.jpg").canonicalPath,
            sizeBytes = 100L,
            lastModifiedMillis = 200L,
            contentUri = contentUri,
            selectedForDeletion = true,
        )
}
