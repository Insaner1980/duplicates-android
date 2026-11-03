package com.emma.duplicates.domain.deletion

import androidx.room.withTransaction
import com.emma.duplicates.core.database.CleanupOperation
import com.emma.duplicates.core.database.CleanupOperationItem
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.storage.StorageVolumeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

data class EmptyFolderBatch(
    val session: ToolScanSession,
    val selected: List<ToolItemEntity>,
    val identities: Map<String, String?>,
)

data class EmptyFolderResult(
    val deletedCount: Int,
    val skippedCount: Int,
)

class EmptyFolderDeletionCoordinator(
    private val database: DuplicatesDatabase,
    volumes: StorageVolumeSource,
    hasAccess: () -> Boolean,
    private val operationMutex: Mutex,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = database.toolDao()
    private val access = CleanupAccess(database, volumes, hasAccess)

    suspend fun prepare(sessionId: String): EmptyFolderBatch =
        withContext(ioDispatcher) {
            val session =
                requireNotNull(
                    dao.session(sessionId)?.takeIf {
                        it.active &&
                            it.tool == CleanupTool.EMPTY_FOLDERS.name
                    },
                )
            val selected = dao.items(sessionId).filter { it.valid && it.selected && it.directory }
            val identities = selected.associate { it.id to directoryAttributes(it)?.fileKey()?.toString() }
            EmptyFolderBatch(session, selected, identities)
        }

    suspend fun execute(batch: EmptyFolderBatch): EmptyFolderResult =
        withContext(ioDispatcher) {
            operationMutex.withLock {
                val scope = ToolScope.decode(batch.session.scope)
                val operation =
                    CleanupOperation(
                        UUID.randomUUID().toString(),
                        batch.session.id,
                        batch.session.tool,
                        System.currentTimeMillis(),
                    )
                val selected = batch.selected.sortedByDescending { Paths.get(it.canonicalPath).nameCount }
                val journals =
                    selected.associate { item ->
                        item.id to
                            CleanupOperationItem(
                                operation.id,
                                item.id,
                                item.volume,
                                item.canonicalPath,
                                0,
                                item.modifiedAt,
                                true,
                                null,
                                batch.identities[item.id],
                                null,
                            )
                    }
                val began = begin(batch, scope, operation, journals.values.toList())
                if (!began) return@withLock EmptyFolderResult(0, selected.size)
                val deleted = removeSelected(batch, scope, selected, journals)
                dao.deleteOperation(operation.id)
                EmptyFolderResult(deleted, selected.size - deleted)
            }
        }

    private suspend fun begin(
        batch: EmptyFolderBatch,
        scope: ToolScope,
        operation: CleanupOperation,
        journals: List<CleanupOperationItem>,
    ): Boolean =
        database.withTransaction {
            val selected = dao.items(batch.session.id).filter { it.valid && it.selected }
            val current = dao.session(batch.session.id)
            val unchanged =
                current?.active == true && current.scope == batch.session.scope && selected == batch.selected
            if (!database.cleanupIdle() || !unchanged || !access.scopeAvailable(scope)) {
                false
            } else {
                dao.insertOperation(operation)
                dao.insertOperationItems(journals)
                true
            }
        }

    private suspend fun removeSelected(
        batch: EmptyFolderBatch,
        scope: ToolScope,
        selected: List<ToolItemEntity>,
        journals: Map<String, CleanupOperationItem>,
    ): Int {
        var deleted = 0
        val blocked = mutableListOf<Path>()
        for (item in selected) {
            currentCoroutineContext().ensureActive()
            val path = Paths.get(item.canonicalPath)
            val attributes = directoryAttributes(item)
            val sameDirectory = attributes != null && attributes.fileKey()?.toString() == batch.identities[item.id]
            val eligible = sameDirectory && blocked.none { it.startsWith(path) } && access.allows(item, scope)
            val root = Paths.get(scope.volumes.first { it.id == item.volume }.path)
            val removed = eligible && removeEmptyDirectory(root, path, batch.identities[item.id])
            if (removed) deleted++ else blocked.add(path)
            database.withTransaction {
                dao.updateOperationItem(journals.getValue(item.id).copy(status = if (removed) "deleted" else "skipped"))
                if (removed) dao.invalidate(item.volume, item.canonicalPath)
            }
        }
        return deleted
    }

    private fun directoryAttributes(item: ToolItemEntity): BasicFileAttributes? =
        try {
            Files
                .readAttributes(
                    Paths.get(item.canonicalPath),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).takeIf { it.isDirectory && !it.isSymbolicLink }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
}

internal fun removeEmptyDirectory(
    root: Path,
    path: Path,
    identityKey: String?,
): Boolean {
    if (!path.startsWith(root) || path == root) return false
    val opened = mutableListOf<SecureDirectoryStream<Path>>()
    return try {
        Files.newDirectoryStream(root).use { rootStream ->
            val secureRoot = rootStream as? SecureDirectoryStream<Path>
            if (secureRoot == null) {
                false
            } else {
                secureParent(secureRoot, root.relativize(path), opened).removeIfEmpty(path.fileName, identityKey)
            }
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    } finally {
        opened.asReversed().forEach { it.close() }
    }
}

private fun SecureDirectoryStream<Path>.removeIfEmpty(
    name: Path,
    identityKey: String?,
): Boolean {
    val attributes =
        getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            .readAttributes()
    val sameDirectory =
        attributes.isDirectory && !attributes.isSymbolicLink &&
            attributes.fileKey()?.toString() == identityKey
    val empty = sameDirectory && newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { !it.iterator().hasNext() }
    if (empty) {
        // Android's Unix implementation calls unlinkat with AT_REMOVEDIR, never file unlink.
        deleteDirectory(name)
    }
    return empty
}

private fun secureParent(
    root: SecureDirectoryStream<Path>,
    relative: Path,
    opened: MutableList<SecureDirectoryStream<Path>>,
): SecureDirectoryStream<Path> {
    var parent = root
    for (component in relative.toList().dropLast(1)) {
        parent = parent.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)
        opened.add(parent)
    }
    return parent
}
