package com.emma.duplicates.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.MediaAuthorization
import com.emma.duplicates.domain.deletion.MediaDeletionAuthorizer
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidMediaDeletionAuthorizer internal constructor(
    private val buildRequest: (List<Uri>) -> IntentSenderRequest?,
) : ViewModel(), MediaDeletionAuthorizer {
    constructor(contentResolver: ContentResolver) : this(
        buildRequest = { uris ->
            try {
                IntentSenderRequest.Builder(
                    MediaStore.createDeleteRequest(contentResolver, uris).intentSender,
                ).build()
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        },
    )

    private val requestMutex = Mutex()
    private val stateLock = Any()
    private val mutableLaunchRequest = MutableStateFlow<IntentSenderRequest?>(null)
    val launchRequest: StateFlow<IntentSenderRequest?> = mutableLaunchRequest.asStateFlow()

    private var pendingContinuation: CancellableContinuation<MediaAuthorization>? = null
    private var launchClaimed = false

    val hasPendingRequest: Boolean
        get() = synchronized(stateLock) { pendingContinuation?.isActive == true }

    override suspend fun requestDeletion(files: List<DeletionFile>): MediaAuthorization =
        requestMutex.withLock {
            val uris = files.mapNotNull { file -> file.contentUri?.let(Uri::parse) }
            if (uris.size != files.size || uris.isEmpty()) return@withLock MediaAuthorization.FAILED
            val request = buildRequest(uris) ?: return@withLock MediaAuthorization.FAILED

            suspendCancellableCoroutine { continuation ->
                synchronized(stateLock) {
                    pendingContinuation = continuation
                    launchClaimed = false
                    mutableLaunchRequest.value = request
                }
                continuation.invokeOnCancellation { clear(continuation) }
            }
        }

    fun claimLaunchRequest(): IntentSenderRequest? =
        synchronized(stateLock) {
            if (pendingContinuation == null || launchClaimed) {
                null
            } else {
                launchClaimed = true
                mutableLaunchRequest.value
            }
        }

    fun complete(result: MediaAuthorization): Boolean {
        val continuation =
            synchronized(stateLock) {
                val current = pendingContinuation ?: return false
                pendingContinuation = null
                launchClaimed = false
                mutableLaunchRequest.value = null
                current
            }
        if (!continuation.isActive) return false
        continuation.resume(result)
        return true
    }

    override fun onCleared() {
        complete(MediaAuthorization.FAILED)
    }

    private fun clear(continuation: CancellableContinuation<MediaAuthorization>) {
        synchronized(stateLock) {
            if (pendingContinuation !== continuation) return
            pendingContinuation = null
            launchClaimed = false
            mutableLaunchRequest.value = null
        }
    }
}
