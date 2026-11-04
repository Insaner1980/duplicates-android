package com.emma.duplicates.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.storage.StorageManager
import com.emma.duplicates.core.database.RecoveryDao
import com.emma.duplicates.core.database.RecoveryFile
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.DirectDeletionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Moves the selected directory entry without unlinking it; no copy-and-delete fallback is safe here. */
class RecoveryRepository internal constructor(
    private val dao: RecoveryDao,
    private val roots: () -> List<File>,
    private val rootFor: (File) -> File?,
    private val available: (File) -> Boolean,
    private val refresh: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, dao: RecoveryDao) : this(
        dao = dao,
        roots = { recoveryRoots(context) },
        rootFor = { source ->
            val manager = context.getSystemService(StorageManager::class.java)
            val volume = manager.getStorageVolume(source)
            recoveryRoots(context).firstOrNull {
                volume != null && manager.getStorageVolume(it) == volume
            }
        },
        available = { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED },
        refresh = { MediaScannerConnection.scanFile(context, arrayOf(it), null, null) },
    )

    val files = dao.observeFiles()
    private val mutex = Mutex()

    suspend fun describe(
        records: List<RecoveryFile>,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): List<RecoveryEntry> =
        withContext(ioDispatcher) {
            mutex.withLock {
                records.map { record ->
                    val unavailable = RecoveryEntry(record, null, null, false)
                    try {
                        val payload = record.ownedPayload(roots(), available)
                        if (payload != null && Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS) &&
                            Files.isReadable(payload)
                        ) {
                            RecoveryEntry(record, Files.size(payload), payload.toString(), true)
                        } else {
                            unavailable
                        }
                    } catch (_: IOException) {
                        unavailable
                    } catch (_: SecurityException) {
                        unavailable
                    }
                }
            }
        }

    suspend fun deletePermanently(
        id: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    val record = dao.get(id) ?: return@withLock false
                    val payload = record.ownedPayload(roots(), available) ?: return@withLock false
                    if (!removeOwnedPayload(
                            payload,
                        ) { record.ownedPayload(roots(), available) == payload }
                    ) {
                        return@withLock false
                    }
                    dao.delete(id)
                    true
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
        }

    suspend fun move(
        file: DeletionFile,
        verify: suspend (DeletionFile) -> Boolean,
    ): DirectDeletionResult = moveTracked(file, UUID.randomUUID().toString(), verify = verify)

    suspend fun moveTracked(
        file: DeletionFile,
        recoveryId: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        verify: suspend (DeletionFile) -> Boolean,
    ): DirectDeletionResult =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    require(UUID.fromString(recoveryId).toString() == recoveryId)
                    if (dao.get(recoveryId) != null) return@withLock DirectDeletionResult.FAILED
                    stage(file, recoveryId, verify)
                } catch (_: IOException) {
                    DirectDeletionResult.FAILED
                } catch (_: SecurityException) {
                    DirectDeletionResult.FAILED
                }
            }
        }

    private suspend fun stage(
        file: DeletionFile,
        id: String,
        verify: suspend (DeletionFile) -> Boolean,
    ): DirectDeletionResult {
        val source = File(file.canonicalPath)
        val root = rootFor(source)?.takeIf(available) ?: return DirectDeletionResult.FAILED
        val directory = File(root, id).toPath()
        Files.createDirectories(root.toPath())
        Files.createDirectory(directory)
        val payload = directory.resolve("payload")
        val record = RecoveryFile(id, source.path, payload.toString(), now())
        // The durable row precedes the rename. Cancellation/crash cannot leave an unlisted payload.
        var recorded = false
        try {
            dao.insert(record)
            recorded = true
        } finally {
            if (!recorded) Files.deleteIfExists(directory)
        }
        try {
            Files.move(source.toPath(), payload, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: IOException) {
            // Keep uncertain outcomes and unavailable volumes for recovery.
            if (available(root) && Files.notExists(payload, LinkOption.NOFOLLOW_LINKS)) {
                withContext(NonCancellable) { dao.delete(id) }
                Files.deleteIfExists(directory)
            }
            throw failure
        }
        val staged = file.copy(canonicalPath = payload.toString(), contentUri = null)
        return if (!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS) || !verify(staged)) {
            // Never expire an unverified replacement. Restoration never overwrites a newer file.
            restore(record)
            DirectDeletionResult.FAILED
        } else {
            dao.setExpiry(id, now() + RETENTION_MILLIS)
            refresh(file.canonicalPath)
            DirectDeletionResult.DELETED
        }
    }

    /** Creates a new copy and retains the recovery payload, including after partial/canceled restores. */
    suspend fun restore(
        id: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): String? =
        withContext(ioDispatcher) {
            mutex.withLock { dao.get(id)?.let { restore(it) } }
        }

    /** Reconciliation verifies only the recorded payload; it never repeats a source move. */
    suspend fun reconcileMove(
        id: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        verify: suspend (String) -> Boolean,
    ): RecoveryMoveState =
        withContext(ioDispatcher) {
            mutex.withLock {
                val record = dao.get(id) ?: return@withLock RecoveryMoveState.NOT_MOVED
                val payload = record.ownedPayload(roots(), available) ?: return@withLock RecoveryMoveState.UNAVAILABLE
                if (Files.notExists(payload, LinkOption.NOFOLLOW_LINKS)) {
                    return@withLock removeMissingRecord(id, payload)
                }
                if (!Files.isRegularFile(
                        payload,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                ) {
                    return@withLock RecoveryMoveState.UNAVAILABLE
                }
                if (verify(payload.toString())) {
                    if (record.expiresAt == null) dao.setExpiry(id, now() + RETENTION_MILLIS)
                    refresh(record.originalPath)
                    RecoveryMoveState.VERIFIED
                } else {
                    restore(record)
                    RecoveryMoveState.UNVERIFIED
                }
            }
        }

    private suspend fun removeMissingRecord(
        id: String,
        payload: Path,
    ): RecoveryMoveState =
        try {
            Files.deleteIfExists(payload.parent)
            dao.delete(id)
            RecoveryMoveState.NOT_MOVED
        } catch (_: IOException) {
            RecoveryMoveState.UNAVAILABLE
        } catch (_: SecurityException) {
            RecoveryMoveState.UNAVAILABLE
        }

    private fun restore(record: RecoveryFile): String? {
        return try {
            val payload =
                record.ownedPayload(roots(), available)?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    ?: return null
            val original = File(record.originalPath).toPath()
            val destination =
                try {
                    Files.copy(payload, original)
                } catch (_: FileAlreadyExistsException) {
                    val alternate = original.resolveSibling("${original.fileName}.restored-${UUID.randomUUID()}")
                    Files.copy(payload, alternate)
                }
            refresh(destination.toString())
            destination.toString()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    suspend fun removeExpired(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) =
        withContext(ioDispatcher) {
            mutex.withLock {
                dao.expired(now()).forEach { record ->
                    try {
                        val payload = record.ownedPayload(roots(), available) ?: return@forEach
                        Files.deleteIfExists(payload)
                        Files.deleteIfExists(payload.parent)
                        dao.delete(record.id)
                    } catch (_: IOException) {
                        // A removed/read-only volume is retried on the next visit.
                    } catch (_: SecurityException) {
                        // Preserve the journal when storage access is unavailable.
                    }
                }
            }
        }

    private companion object {
        val RETENTION_MILLIS: Long = TimeUnit.DAYS.toMillis(30)
    }
}

enum class RecoveryMoveState { VERIFIED, UNVERIFIED, NOT_MOVED, UNAVAILABLE }

private fun SecureDirectoryStream<Path>.attributesOrNull(name: Path): BasicFileAttributes? =
    try {
        getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
    } catch (_: NoSuchFileException) {
        null
    }

// Android/data is a separate bind mount: atomic moves from shared storage fail with EXDEV.
// This hidden app-specific directory stays on the shared volume and is excluded from scans.
@Suppress("DEPRECATION", "kotlin:S5324")
private fun recoveryRoots(context: Context): List<File> =
    context.externalMediaDirs.filterNotNull().map { File(it, ".recovery") }

private fun RecoveryFile.ownedPayload(
    roots: List<File>,
    available: (File) -> Boolean,
): Path? {
    if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) return null
    return roots
        .asSequence()
        .filter(available)
        .map { root ->
            File(File(root, id), "payload")
        }.firstOrNull { expected ->
            expected.path == storedPath && expected.canonicalPath == expected.absolutePath &&
                !Files.isSymbolicLink(expected.toPath())
        }?.toPath()
}

private fun removeOwnedPayload(
    payload: Path,
    stillOwned: () -> Boolean,
): Boolean {
    val root = payload.parent.parent
    val identity = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!identity.isDirectory || identity.isSymbolicLink || identity.fileKey() == null) return false
    // Verify ownership after capturing identity, then compare the opened handle.
    return stillOwned() &&
        Files.newDirectoryStream(root).use { stream ->
            val secure = stream as? SecureDirectoryStream<Path> ?: return@use false
            if (secure.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey() !=
                identity.fileKey()
            ) {
                return@use false
            }
            if (!secure.removePayloadDirectory(payload)) return@use false
            stillOwned() &&
                Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() ==
                identity.fileKey() &&
                secure.attributesOrNull(payload.parent.fileName) == null
        }
}

private fun SecureDirectoryStream<Path>.removePayloadDirectory(payload: Path): Boolean {
    val directory = payload.parent.fileName
    return try {
        val removed =
            newDirectoryStream(directory, LinkOption.NOFOLLOW_LINKS).use { copy ->
                val attributes = copy.attributesOrNull(payload.fileName)
                if (attributes?.isSymbolicLink == true || attributes?.isRegularFile == false) return@use false
                if (attributes != null) copy.deleteFile(payload.fileName)
                if (copy.attributesOrNull(payload.fileName) != null) return@use false
                val openedKey = copy.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()
                openedKey != null && attributesOrNull(directory)?.fileKey() == openedKey
            }
        if (removed) deleteDirectory(directory) // Never recurse into unexpected contents.
        removed
    } catch (_: NoSuchFileException) {
        attributesOrNull(directory) == null
    }
}
