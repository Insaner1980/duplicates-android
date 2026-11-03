package com.emma.duplicates.domain.deletion

import com.emma.duplicates.core.database.ToolItemEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal suspend fun verifyCleanupFile(item: ToolItemEntity): VerifiedCleanupFile? =
    try {
        val path = Paths.get(item.canonicalPath)
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!before.matches(item) || !Files.isReadable(path)) {
            null
        } else {
            val digest = contentDigest(path)
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!after.matches(item) || before.lastModifiedTime() != after.lastModifiedTime() ||
                before.fileKey() != after.fileKey()
            ) {
                null
            } else {
                VerifiedCleanupFile(
                    item,
                    digest,
                    after.fileKey()?.toString(),
                )
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

private fun BasicFileAttributes.matches(item: ToolItemEntity): Boolean =
    isRegularFile && size() == item.sizeBytes && lastModifiedTime().toMillis() == item.modifiedAt

private suspend fun contentDigest(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteBuffer.allocate(HASH_BUFFER_BYTES)
    Files.newByteChannel(path, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
        var read: Int
        do {
            currentCoroutineContext().ensureActive()
            buffer.clear()
            read = channel.read(buffer)
            if (read > 0) digest.update(buffer.array(), 0, read)
        } while (read >= 0)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
}

private const val HASH_BUFFER_BYTES = 65_536
private const val BYTE_MASK = 255

internal fun VerifiedCleanupFile.matches(other: VerifiedCleanupFile?): Boolean =
    other != null && digest == other.digest && identityKey == other.identityKey

internal fun ToolItemEntity.deletionFile() =
    DeletionFile(
        id = id,
        canonicalPath = canonicalPath,
        sizeBytes = sizeBytes,
        lastModifiedMillis = modifiedAt,
        contentUri = contentUri,
        selectedForDeletion = true,
        requiresMediaAuthorization = contentUri != null && category != "DOCUMENTS",
    )
