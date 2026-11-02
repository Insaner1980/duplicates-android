package com.emma.duplicates.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.storage.StorageManager
import com.emma.duplicates.core.database.RecoveryDao
import com.emma.duplicates.core.database.RecoveryFile
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.DirectDeletionResult
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
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

    suspend fun move(
        file: DeletionFile,
        verify: suspend (DeletionFile) -> Boolean,
    ): DirectDeletionResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    stage(file, verify)
                } catch (_: IOException) {
                    DirectDeletionResult.FAILED
                } catch (_: SecurityException) {
                    DirectDeletionResult.FAILED
                }
            }
        }

    private suspend fun stage(
        file: DeletionFile,
        verify: suspend (DeletionFile) -> Boolean,
    ): DirectDeletionResult {
        val source = File(file.canonicalPath)
        val root = rootFor(source)?.takeIf(available) ?: return DirectDeletionResult.FAILED
        val id = UUID.randomUUID().toString()
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
            DirectDeletionResult.DELETED
        }
    }

    /** Creates a new copy and retains the recovery payload, including after partial/canceled restores. */
    suspend fun restore(id: String): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock { dao.get(id)?.let { restore(it) } }
        }

    private fun restore(record: RecoveryFile): String? {
        return try {
            val payload =
                ownedPayload(record)?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) } ?: return null
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

    suspend fun removeExpired() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                dao.expired(now()).forEach { record ->
                    try {
                        val payload = ownedPayload(record) ?: return@forEach
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

    private fun ownedPayload(record: RecoveryFile): Path? {
        if (runCatching { UUID.fromString(record.id).toString() }.getOrNull() != record.id) return null
        return roots()
            .asSequence()
            .filter(available)
            .map { root ->
                File(File(root, record.id), "payload")
            }.firstOrNull { expected ->
                expected.path == record.storedPath && expected.canonicalPath == expected.absolutePath &&
                    !Files.isSymbolicLink(expected.toPath())
            }?.toPath()
    }

    private companion object {
        val RETENTION_MILLIS: Long = TimeUnit.DAYS.toMillis(30)
    }
}

// Android/data is a separate bind mount: atomic moves from shared storage fail with EXDEV.
// This hidden app-specific directory stays on the shared volume and is excluded from scans.
@Suppress("DEPRECATION", "kotlin:S5324")
private fun recoveryRoots(context: Context): List<File> =
    context.externalMediaDirs.filterNotNull().map { File(it, ".recovery") }
