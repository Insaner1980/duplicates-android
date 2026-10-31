package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.FileMetadata
import kotlinx.coroutines.CancellationException
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
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

data class FileSnapshot(
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

enum class FileIssueReason {
    MISSING,
    CHANGED,
    UNREADABLE,
    IO_ERROR,
}

data class FileProcessingIssue(
    val fileId: String,
    val reason: FileIssueReason,
)

sealed interface FingerprintOutcome {
    data class Success(
        val sha256: String,
    ) : FingerprintOutcome

    data class Failure(
        val issue: FileProcessingIssue,
    ) : FingerprintOutcome
}

sealed interface ContentComparisonOutcome {
    data object Equal : ContentComparisonOutcome

    data object Different : ContentComparisonOutcome

    data class Failure(
        val issues: List<FileProcessingIssue>,
    ) : ContentComparisonOutcome
}

interface FileFingerprinter {
    suspend fun validate(file: FileMetadata): FileProcessingIssue?

    suspend fun quickFingerprint(file: FileMetadata): FingerprintOutcome

    suspend fun fullFingerprint(file: FileMetadata): FingerprintOutcome

    suspend fun compare(
        first: FileMetadata,
        second: FileMetadata,
    ): ContentComparisonOutcome
}

interface FileContentSource {
    @Throws(IOException::class, SecurityException::class)
    fun snapshot(file: FileMetadata): FileSnapshot?

    @Throws(IOException::class, SecurityException::class)
    fun open(file: FileMetadata): SeekableByteChannel
}

class PathFileContentSource : FileContentSource {
    override fun snapshot(file: FileMetadata): FileSnapshot? {
        val path =
            java.nio.file.Paths
                .get(file.canonicalPath)
        val attributes =
            try {
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: NoSuchFileException) {
                return null
            }
        return if (!attributes.isRegularFile || attributes.isSymbolicLink) {
            null
        } else {
            FileSnapshot(attributes.size(), attributes.lastModifiedTime().toMillis())
        }
    }

    override fun open(file: FileMetadata): SeekableByteChannel {
        val path =
            java.nio.file.Paths
                .get(file.canonicalPath)
        return Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    }
}

class Sha256FileFingerprinter(
    private val contentSource: FileContentSource = PathFileContentSource(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FileFingerprinter {
    override suspend fun validate(file: FileMetadata): FileProcessingIssue? =
        withContext(ioDispatcher) {
            validateCurrentSnapshot(file)
        }

    override suspend fun quickFingerprint(file: FileMetadata): FingerprintOutcome =
        fingerprint(file) { digest ->
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(file.sizeBytes).array())
            contentSource.open(file).use { channel ->
                if (updateQuickDigest(channel, digest, file.sizeBytes)) {
                    null
                } else {
                    changedOrMissingIssue(file)
                }
            }
        }

    override suspend fun fullFingerprint(file: FileMetadata): FingerprintOutcome =
        fingerprint(file) { digest ->
            val totalRead = contentSource.open(file).use { channel -> updateFullDigest(channel, digest) }
            if (totalRead == file.sizeBytes) null else changedOrMissingIssue(file)
        }

    override suspend fun compare(
        first: FileMetadata,
        second: FileMetadata,
    ): ContentComparisonOutcome =
        withContext(ioDispatcher) {
            if (first.sizeBytes != second.sizeBytes) return@withContext ContentComparisonOutcome.Different
            validateCurrentSnapshot(first)?.let {
                return@withContext ContentComparisonOutcome.Failure(listOf(it))
            }
            validateCurrentSnapshot(second)?.let {
                return@withContext ContentComparisonOutcome.Failure(listOf(it))
            }

            val firstChannel =
                try {
                    contentSource.open(first)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: SecurityException) {
                    return@withContext ContentComparisonOutcome.Failure(listOf(issue(first, error)))
                } catch (error: IOException) {
                    return@withContext ContentComparisonOutcome.Failure(listOf(issue(first, error)))
                }
            val secondChannel =
                try {
                    contentSource.open(second)
                } catch (cancellation: CancellationException) {
                    firstChannel.close()
                    throw cancellation
                } catch (error: SecurityException) {
                    firstChannel.close()
                    return@withContext ContentComparisonOutcome.Failure(listOf(issue(second, error)))
                } catch (error: IOException) {
                    firstChannel.close()
                    return@withContext ContentComparisonOutcome.Failure(listOf(issue(second, error)))
                }
            compareChannels(first, second, firstChannel, secondChannel)
        }

    private suspend fun fingerprint(
        file: FileMetadata,
        updateDigest: suspend (MessageDigest) -> FileProcessingIssue?,
    ): FingerprintOutcome =
        withContext(ioDispatcher) {
            validateCurrentSnapshot(file)?.let { return@withContext FingerprintOutcome.Failure(it) }
            try {
                val digest = MessageDigest.getInstance(SHA_256)
                updateDigest(digest)?.let { return@withContext FingerprintOutcome.Failure(it) }
                validateCurrentSnapshot(file)?.let { return@withContext FingerprintOutcome.Failure(it) }
                FingerprintOutcome.Success(digest.digest().toHexString())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: SecurityException) {
                FingerprintOutcome.Failure(issue(file, error))
            } catch (error: IOException) {
                FingerprintOutcome.Failure(issue(file, error))
            }
        }

    private suspend fun compareChannels(
        first: FileMetadata,
        second: FileMetadata,
        firstChannel: SeekableByteChannel,
        secondChannel: SeekableByteChannel,
    ): ContentComparisonOutcome =
        try {
            val comparison =
                firstChannel.use { left ->
                    secondChannel.use { right ->
                        compareChannelContents(first, second, left, right)
                    }
                }
            val issues =
                if (comparison is ContentComparisonOutcome.Failure) {
                    comparison.issues
                } else {
                    listOfNotNull(validateCurrentSnapshot(first), validateCurrentSnapshot(second))
                }
            if (issues.isNotEmpty()) ContentComparisonOutcome.Failure(issues) else comparison
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: SecurityException) {
            ContentComparisonOutcome.Failure(listOf(issue(first, error), issue(second, error)))
        } catch (error: IOException) {
            ContentComparisonOutcome.Failure(listOf(issue(first, error), issue(second, error)))
        }

    private suspend fun compareChannelContents(
        first: FileMetadata,
        second: FileMetadata,
        firstChannel: SeekableByteChannel,
        secondChannel: SeekableByteChannel,
    ): ContentComparisonOutcome {
        val firstBuffer = ByteBuffer.allocate(BUFFER_BYTES)
        val secondBuffer = ByteBuffer.allocate(BUFFER_BYTES)
        var remaining = first.sizeBytes
        var comparison: ContentComparisonOutcome = ContentComparisonOutcome.Equal
        while (remaining > 0L && comparison == ContentComparisonOutcome.Equal) {
            currentCoroutineContext().ensureActive()
            val chunkSize = minOf(BUFFER_BYTES.toLong(), remaining).toInt()
            comparison =
                when {
                    !readExactly(firstChannel, firstBuffer, chunkSize) -> {
                        ContentComparisonOutcome.Failure(listOf(changedOrMissingIssue(first)))
                    }

                    !readExactly(secondChannel, secondBuffer, chunkSize) -> {
                        ContentComparisonOutcome.Failure(listOf(changedOrMissingIssue(second)))
                    }

                    !firstBuffer.array().contentEquals(secondBuffer.array(), 0, chunkSize) -> {
                        ContentComparisonOutcome.Different
                    }

                    else -> {
                        ContentComparisonOutcome.Equal
                    }
                }
            remaining -= chunkSize
        }
        return comparison
    }

    private fun validateCurrentSnapshot(file: FileMetadata): FileProcessingIssue? =
        try {
            val current = contentSource.snapshot(file) ?: return FileProcessingIssue(file.id, FileIssueReason.MISSING)
            if (current.sizeBytes == file.sizeBytes && current.lastModifiedMillis == file.lastModifiedMillis) {
                null
            } else {
                FileProcessingIssue(file.id, FileIssueReason.CHANGED)
            }
        } catch (error: FileNotFoundException) {
            issue(file, error)
        } catch (error: NoSuchFileException) {
            issue(file, error)
        } catch (error: AccessDeniedException) {
            issue(file, error)
        } catch (error: SecurityException) {
            issue(file, error)
        } catch (error: IOException) {
            issue(file, error)
        }

    private fun changedOrMissingIssue(file: FileMetadata): FileProcessingIssue =
        validateCurrentSnapshot(file) ?: FileProcessingIssue(file.id, FileIssueReason.CHANGED)

    private fun issue(
        file: FileMetadata,
        error: Exception,
    ): FileProcessingIssue =
        FileProcessingIssue(
            fileId = file.id,
            reason =
                when (error) {
                    is FileNotFoundException, is NoSuchFileException -> FileIssueReason.MISSING
                    is AccessDeniedException, is SecurityException -> FileIssueReason.UNREADABLE
                    else -> FileIssueReason.IO_ERROR
                },
        )

    private companion object {
        const val SHA_256 = "SHA-256"
    }
}

private const val UNSIGNED_BYTE_MASK = 0xff
private const val BUFFER_BYTES = 64 * 1024
private const val QUICK_SAMPLE_BYTES = 64 * 1024
private const val QUICK_COMPLETE_THRESHOLD_BYTES = 128 * 1024L

private suspend fun updateQuickDigest(
    channel: SeekableByteChannel,
    digest: MessageDigest,
    sizeBytes: Long,
): Boolean =
    if (sizeBytes > QUICK_COMPLETE_THRESHOLD_BYTES) {
        updateDigest(channel, digest, 0L, QUICK_SAMPLE_BYTES) &&
            updateDigest(channel, digest, sizeBytes - QUICK_SAMPLE_BYTES, QUICK_SAMPLE_BYTES)
    } else {
        updateDigest(channel, digest, 0L, sizeBytes.toInt())
    }

private suspend fun updateFullDigest(
    channel: SeekableByteChannel,
    digest: MessageDigest,
): Long {
    var totalRead = 0L
    val buffer = ByteBuffer.allocate(BUFFER_BYTES)
    while (true) {
        currentCoroutineContext().ensureActive()
        buffer.clear()
        val count = channel.read(buffer)
        currentCoroutineContext().ensureActive()
        if (count < 0) return totalRead
        if (count > 0) {
            digest.update(buffer.array(), 0, count)
            totalRead += count
        }
    }
}

private suspend fun updateDigest(
    channel: SeekableByteChannel,
    digest: MessageDigest,
    offset: Long,
    byteCount: Int,
): Boolean {
    channel.position(offset)
    val buffer = ByteBuffer.allocate(minOf(BUFFER_BYTES, byteCount))
    var remaining = byteCount
    while (remaining > 0) {
        currentCoroutineContext().ensureActive()
        buffer.clear()
        buffer.limit(minOf(buffer.capacity(), remaining))
        val count = channel.read(buffer)
        currentCoroutineContext().ensureActive()
        if (count < 0) return false
        if (count == 0) continue
        digest.update(buffer.array(), 0, count)
        remaining -= count
    }
    return true
}

private suspend fun readExactly(
    channel: SeekableByteChannel,
    buffer: ByteBuffer,
    byteCount: Int,
): Boolean {
    buffer.clear()
    buffer.limit(byteCount)
    while (buffer.hasRemaining()) {
        currentCoroutineContext().ensureActive()
        val count = channel.read(buffer)
        currentCoroutineContext().ensureActive()
        if (count < 0) return false
    }
    return true
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK)
    }

private fun ByteArray.contentEquals(
    other: ByteArray,
    offset: Int,
    length: Int,
): Boolean {
    for (index in offset until offset + length) {
        if (this[index] != other[index]) return false
    }
    return true
}
