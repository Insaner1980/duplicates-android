package com.emma.duplicates.app

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.MediaAuthorization
import com.emma.duplicates.domain.deletion.MediaDeletionAuthorizer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

class AndroidMediaDeletionAuthorizer internal constructor(
    private val buildRequest: (List<DeletionFile>) -> IntentSenderRequest?,
) : ViewModel(),
    MediaDeletionAuthorizer {
    constructor(contentResolver: ContentResolver) : this(
        buildRequest = request@{ files ->
            try {
                val mediaUris = files.map { contentResolver.mediaDeletionUri(it) ?: return@request null }
                IntentSenderRequest
                    .Builder(
                        MediaStore.createWriteRequest(contentResolver, mediaUris).intentSender,
                    ).build()
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IOException) {
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

    // The deletion coordinator awaits this lifecycle-retained bridge; views only consume launchRequest.
    @Suppress("kotlin:S6313")
    override suspend fun requestDeletion(files: List<DeletionFile>): MediaAuthorization =
        requestMutex.withLock {
            if (files.isEmpty() || files.any { it.contentUri == null }) return@withLock MediaAuthorization.FAILED
            val request =
                withContext(Dispatchers.IO) { buildRequest(files) } ?: return@withLock MediaAuthorization.FAILED

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
                val current = pendingContinuation
                pendingContinuation = null
                launchClaimed = false
                mutableLaunchRequest.value = null
                current
            }
        if (continuation?.isActive != true) return false
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

// Persisted scan results use Files URIs, but deletion approval requires typed media URIs.
internal fun ContentResolver.mediaDeletionUri(file: DeletionFile): Uri? {
    val uri = Uri.parse(requireNotNull(file.contentUri))
    if (
        uri.scheme != ContentResolver.SCHEME_CONTENT ||
        uri.authority != MediaStore.AUTHORITY || uri.pathSegments.size < FILE_URI_SEGMENT_COUNT
    ) {
        return null
    }
    val segments = uri.pathSegments
    val id = ContentUris.parseId(uri)
    val collection =
        when (mediaTypeAtPath(uri, file.canonicalPath)) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(segments[0])
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(segments[0])
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.getContentUri(segments[0])
            else -> null
        }
    return collection?.let { ContentUris.withAppendedId(it, id) }
}

private fun ContentResolver.mediaTypeAtPath(
    uri: Uri,
    canonicalPath: String,
): Int? =
    query(
        uri,
        arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DATA),
        null,
        null,
        null,
    )?.use { cursor ->
        val currentPath = if (cursor.moveToFirst()) cursor.getString(1) else null
        if (currentPath != null && File(currentPath).canonicalPath == canonicalPath) cursor.getInt(0) else null
    }

private const val FILE_URI_SEGMENT_COUNT = 3
