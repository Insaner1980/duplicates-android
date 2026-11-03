package com.emma.duplicates.app

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.Os
import com.emma.duplicates.core.database.ExclusionTypes
import com.emma.duplicates.core.database.ScanFailureReasons
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolExclusion
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.model.ToolVolume
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.EmptyFolderScanner
import com.emma.duplicates.core.storage.SimilarPhotoScanner
import com.emma.duplicates.core.storage.ToolDiscoveryResult
import com.emma.duplicates.core.storage.ToolFileScanner
import com.emma.duplicates.data.ToolCompletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

class ToolScanRunner(
    private val context: Context,
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Classify platform/worker/database failures at the run boundary, retaining cancellation.
    @Suppress("TooGenericExceptionCaught")
    suspend fun run(
        id: String,
        tool: CleanupTool,
        volumeId: String? = null,
        onProgress: suspend (String, Int) -> Unit = { _, _ -> },
    ): Boolean =
        withContext(ioDispatcher) {
            val store = container.toolResultStore
            var started = false
            try {
                require(tool != CleanupTool.DUPLICATES && tool != CleanupTool.UNUSED_APPS)
                val session =
                    ToolScanSession(id, tool.name, System.currentTimeMillis(), ToolScope(emptyList()).encode())
                container.deletionOperationMutex.withLock { store.start(session) }
                started = true
                onProgress(ScanPhases.FINDING_FILES, 0)
                scanAndStore(session, tool, volumeId, onProgress)
                true
            } catch (cancel: CancellationException) {
                if (started) withContext(NonCancellable) { store.finish(id, ScanStatuses.CANCELED, null) }
                throw cancel
            } catch (failure: Exception) {
                if (started) store.finish(id, ScanStatuses.FAILED, failureReason(failure))
                false
            }
        }

    private suspend fun scanAndStore(
        session: ToolScanSession,
        tool: CleanupTool,
        volumeId: String?,
        onProgress: suspend (String, Int) -> Unit,
    ) {
        val (scope, volumes) = captureScope(tool, volumeId)
        val store = container.toolResultStore
        store.dao.updateSession(session.copy(scope = scope.encode()))
        val metadata = container.mediaStoreMetadataIndex.refresh(volumes)
        var lastProgress = 0L
        val progress: suspend (String, Int) -> Unit = { phase, count ->
            val now = System.nanoTime()
            if (now - lastProgress >= PROGRESS_INTERVAL_NANOS) {
                lastProgress = now
                store.dao.progress(session.id, phase, count)
                onProgress(phase, count)
            }
        }
        val result = discover(session.id, tool, scope, progress)
        ensureAvailable(scope)
        val endVolume =
            if (tool == CleanupTool.STORAGE_USAGE) {
                container.storageVolumeSource.mountedVolumes().first { it.id == volumes.single().id }
            } else {
                null
            }
        val coherent =
            endVolume == null ||
                volumes.single().let {
                    it.totalBytes == endVolume.totalBytes && it.freeBytes == endVolume.freeBytes
                }
        store.complete(
            session.id,
            result.items,
            System.currentTimeMillis(),
            ToolCompletion(
                complete = result.complete && metadata.errorCount == 0,
                errorCount = result.errorCount + metadata.errorCount,
                skippedCount = result.skippedCount,
                totalBytes = endVolume?.totalBytes?.takeIf { it > 0 },
                freeBytes = endVolume?.freeBytes?.takeIf { endVolume.totalBytes > 0 },
                failureReason = if (coherent) null else "measurement_changed",
            ),
        )
    }

    private suspend fun discover(
        id: String,
        tool: CleanupTool,
        scope: ToolScope,
        progress: suspend (String, Int) -> Unit,
    ): ToolDiscoveryResult {
        val result =
            if (tool == CleanupTool.EMPTY_FOLDERS) {
                val originals =
                    container.database
                        .recoveryDao()
                        .observeFiles()
                        .first()
                        .map { it.originalPath }
                        .toSet()
                EmptyFolderScanner().scan(
                    id,
                    scope,
                    originals,
                ) { progress(ScanPhases.FINDING_FILES, it.visitedEntryCount) }
            } else {
                ToolFileScanner(container.fileDiscovery).scan(id, tool, scope) {
                    progress(ScanPhases.FINDING_FILES, it.visitedEntryCount)
                }
            }
        return when (tool) {
            CleanupTool.SIMILAR_PHOTOS -> {
                SimilarPhotoScanner(container.mediaStoreMetadataIndex).scan(result) {
                    progress(ScanPhases.COMPARING_CANDIDATES, it)
                }
            }

            CleanupTool.STORAGE_USAGE -> {
                result.copy(
                    items =
                        result.items.map {
                            currentCoroutineContext().ensureActive()
                            it.copy(allocatedBytes = allocatedBytes(it))
                        },
                )
            }

            else -> {
                result
            }
        }
    }

    private suspend fun captureScope(
        tool: CleanupTool,
        volumeId: String?,
    ): Pair<ToolScope, List<AvailableStorageVolume>> {
        requireAccess()
        val preferences = container.preferencesRepository.preferences.first()
        val mounted = container.storageVolumeSource.mountedVolumes()
        val wholeVolume = tool == CleanupTool.STORAGE_USAGE
        val roots =
            if (!wholeVolume && preferences.scanSelectedItemsOnly) {
                container.storageBrowser.scanRoots(preferences.selectedScanPaths)
                    ?: throw IOException("Selected location unavailable")
            } else {
                null
            }
        val selectedIds =
            if (wholeVolume) {
                setOf(volumeId ?: mounted.firstOrNull()?.id)
            } else {
                roots?.map { it.id }?.toSet() ?: preferences.selectedVolumeIds
            }
        val volumes = mounted.filter { it.id in selectedIds }
        if (volumes.isEmpty() || volumes.map { it.id }.toSet() != selectedIds) throw IOException("Storage unavailable")
        val exclusions =
            if (wholeVolume) {
                emptyList()
            } else {
                container.scanStore.getExclusions().map {
                    ToolExclusion(it.canonicalPath, it.type == ExclusionTypes.FOLDER)
                }
            }
        val scope =
            ToolScope(
                volumes.map { ToolVolume(it.id, it.directory.canonicalPath) },
                includedPaths = roots?.map { it.directory.canonicalPath }.orEmpty(),
                exclusions = exclusions,
                applicationDirectories = context.applicationDirectories().toList(),
                scanHiddenFolders = wholeVolume || preferences.scanHiddenFolders,
                ignoreSystemFolders = !wholeVolume && preferences.ignoreSystemFolders,
            ).withToolParameters(tool)
        ensureAvailable(scope)
        return scope to volumes
    }

    private fun requireAccess() {
        if (!container.storageAccessManager.hasAccess()) throw SecurityException("Storage access required")
    }

    private fun ensureAvailable(scope: ToolScope) {
        requireAccess()
        val mounted = container.storageVolumeSource.mountedVolumes()
        val missingVolume =
            scope.volumes.any { root ->
                mounted.none { it.id == root.id && it.directory.canonicalPath == root.path && it.directory.canRead() }
            }
        val missingPath =
            scope.includedPaths.any {
                !Files.exists(File(it).toPath(), LinkOption.NOFOLLOW_LINKS) || !File(it).canRead()
            }
        if (missingVolume || missingPath) throw IOException("Selected location unavailable")
    }
}

private fun allocatedBytes(item: ToolItemEntity): Long? =
    try {
        val path = File(item.canonicalPath).toPath()
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val stat = Os.lstat(item.canonicalPath)
        if (stat.st_size == item.sizeBytes && attributes.lastModifiedTime().toMillis() == item.modifiedAt) {
            Math.multiplyExact(stat.st_blocks, POSIX_BLOCK_BYTES).takeIf { it >= 0L }
        } else {
            null
        }
    } catch (_: ErrnoException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

private fun failureReason(failure: Exception): String =
    when (failure) {
        is SecurityException -> ScanFailureReasons.PERMISSION_REVOKED
        is IOException -> ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE
        is SQLiteFullException -> ScanFailureReasons.STORAGE_FULL
        is SQLiteException -> ScanFailureReasons.DATABASE
        is ForegroundWorkerFailureException -> ScanFailureReasons.FOREGROUND_WORKER
        else -> ScanFailureReasons.GENERIC
    }

private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
private const val POSIX_BLOCK_BYTES = 512L

private fun ToolScope.withToolParameters(tool: CleanupTool): ToolScope =
    when (tool) {
        CleanupTool.LARGE_FILES -> {
            copy(minimumFileBytes = com.emma.duplicates.core.storage.LARGE_FILE_MINIMUM)
        }

        CleanupTool.SIMILAR_PHOTOS -> {
            copy(
                maximumPhotoDistance = com.emma.duplicates.core.storage.MAX_HASH_DISTANCE,
                maximumAspectDifference = com.emma.duplicates.core.storage.MAX_RATIO_DIFFERENCE,
                photoFingerprintVersion = com.emma.duplicates.core.storage.PHOTO_HASH_VERSION,
            )
        }

        else -> {
            this
        }
    }
