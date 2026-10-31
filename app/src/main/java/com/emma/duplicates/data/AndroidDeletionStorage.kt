package com.emma.duplicates.data

import android.content.ContentResolver
import android.database.sqlite.SQLiteException
import androidx.core.net.toUri
import com.emma.duplicates.domain.deletion.CurrentFileSnapshot
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.DeletionStorage
import com.emma.duplicates.domain.deletion.DirectDeletionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class AndroidDeletionStorage internal constructor(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val removeMediaStoreRow: (String) -> Unit,
) : DeletionStorage {
    constructor(
        contentResolver: ContentResolver,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        ioDispatcher = ioDispatcher,
        removeMediaStoreRow = { contentUri ->
            contentResolver.delete(contentUri.toUri(), null, null)
        },
    )

    override suspend fun snapshot(file: DeletionFile): CurrentFileSnapshot? =
        withContext(ioDispatcher) {
            try {
                val path = Paths.get(file.canonicalPath)
                val attributes =
                    Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                if (!attributes.isRegularFile || attributes.isSymbolicLink) return@withContext null
                CurrentFileSnapshot(
                    sizeBytes = attributes.size(),
                    lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
                    isReadable = Files.isReadable(path),
                    isWritable = Files.isWritable(path),
                )
            } catch (_: FileNotFoundException) {
                null
            } catch (_: NoSuchFileException) {
                null
            } catch (_: AccessDeniedException) {
                file.inaccessibleSnapshot()
            } catch (_: SecurityException) {
                file.inaccessibleSnapshot()
            } catch (_: IOException) {
                file.inaccessibleSnapshot()
            }
        }

    override suspend fun fullHash(file: DeletionFile): String? =
        withContext(ioDispatcher) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteBuffer.allocate(BUFFER_SIZE)
                Files.newByteChannel(Paths.get(file.canonicalPath), StandardOpenOption.READ).use { channel ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        buffer.clear()
                        val count = channel.read(buffer)
                        if (count < 0) break
                        if (count > 0) digest.update(buffer.array(), 0, count)
                    }
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }
            } catch (_: SecurityException) {
                null
            } catch (_: IOException) {
                null
            }
        }

    override suspend fun contentsEqual(
        first: DeletionFile,
        second: DeletionFile,
    ): Boolean =
        try {
            compareFileContents(first, second)
        } catch (_: SecurityException) {
            false
        } catch (_: IOException) {
            false
        }

    private suspend fun compareFileContents(
        first: DeletionFile,
        second: DeletionFile,
    ): Boolean =
        withContext(ioDispatcher) {
            if (first.sizeBytes != second.sizeBytes) return@withContext false
            Files.newByteChannel(Paths.get(first.canonicalPath), StandardOpenOption.READ).use { left ->
                Files.newByteChannel(Paths.get(second.canonicalPath), StandardOpenOption.READ).use { right ->
                    compareChannels(left, right)
                }
            }
        }

    private suspend fun compareChannels(
        left: SeekableByteChannel,
        right: SeekableByteChannel,
    ): Boolean {
        val leftBuffer = ByteBuffer.allocate(BUFFER_SIZE)
        val rightBuffer = ByteBuffer.allocate(BUFFER_SIZE)
        var endOfFile = false
        var equal = true
        while (equal && !endOfFile) {
            currentCoroutineContext().ensureActive()
            leftBuffer.clear()
            rightBuffer.clear()
            val leftCount = left.read(leftBuffer)
            val rightCount = right.read(rightBuffer)
            endOfFile = leftCount < 0
            equal = leftCount == rightCount &&
                (0 until leftCount).all { leftBuffer.array()[it] == rightBuffer.array()[it] }
        }
        return equal
    }

    private fun DeletionFile.inaccessibleSnapshot() =
        CurrentFileSnapshot(
            sizeBytes = sizeBytes,
            lastModifiedMillis = lastModifiedMillis,
            isReadable = false,
            isWritable = false,
        )

    override suspend fun deleteDirect(file: DeletionFile): DirectDeletionResult =
        withContext(ioDispatcher) {
            try {
                if (Files.deleteIfExists(Paths.get(file.canonicalPath))) {
                    removeMediaStoreRowSafely(file.contentUri)
                    DirectDeletionResult.DELETED
                } else {
                    DirectDeletionResult.FAILED
                }
            } catch (_: AccessDeniedException) {
                DirectDeletionResult.FAILED
            } catch (_: SecurityException) {
                DirectDeletionResult.FAILED
            } catch (_: IOException) {
                DirectDeletionResult.FAILED
            }
        }

    private fun removeMediaStoreRowSafely(contentUri: String?) {
        if (contentUri == null) return
        try {
            removeMediaStoreRow(contentUri)
        } catch (_: SecurityException) {
            // The file is already deleted; a stale MediaStore row must not change that result.
        } catch (_: IllegalArgumentException) {
            // The file is already deleted; a stale MediaStore row must not change that result.
        } catch (_: SQLiteException) {
            // The file is already deleted; a stale MediaStore row must not change that result.
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val BYTE_MASK = 0xff
    }
}
