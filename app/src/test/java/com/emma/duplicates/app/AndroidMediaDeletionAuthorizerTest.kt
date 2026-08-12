package com.emma.duplicates.app

import android.app.Application
import androidx.activity.result.IntentSenderRequest
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.MediaAuthorization
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidMediaDeletionAuthorizerTest {
    @Test
    fun pendingAuthorizationIsClaimedOnceAndCompletedAfterRecreation() = runTest {
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
    fun invalidMediaRequestFailsWithoutLaunching() = runTest {
        val authorizer = AndroidMediaDeletionAuthorizer { mockk() }

        val result = authorizer.requestDeletion(listOf(mediaFile(contentUri = null)))

        assertEquals(MediaAuthorization.FAILED, result)
        assertNull(authorizer.launchRequest.value)
    }

    private fun mediaFile(contentUri: String? = "content://media/external/images/media/1") =
        DeletionFile(
            id = "file-1",
            canonicalPath = "/storage/emulated/0/Pictures/copy.jpg",
            sizeBytes = 100L,
            lastModifiedMillis = 200L,
            contentUri = contentUri,
            selectedForDeletion = true,
        )
}
