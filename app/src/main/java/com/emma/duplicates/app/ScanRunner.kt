package com.emma.duplicates.app

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.emma.duplicates.core.database.ExclusionTypes
import com.emma.duplicates.core.database.ScanCompletionMetrics
import com.emma.duplicates.core.database.ScanFailureReasons
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanProgressUpdate
import com.emma.duplicates.core.model.ExclusionType
import com.emma.duplicates.core.model.FileCategory
import com.emma.duplicates.core.model.ScanExclusion
import com.emma.duplicates.core.permissions.StorageAccessManager
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.ExactDuplicateScanPhase
import com.emma.duplicates.core.storage.ExactDuplicateScanProgress
import com.emma.duplicates.core.storage.ExactDuplicateScanResult
import com.emma.duplicates.core.storage.ExactDuplicateScanner
import com.emma.duplicates.core.storage.FileDiscovery
import com.emma.duplicates.core.storage.FileDiscoveryOptions
import com.emma.duplicates.core.storage.FileDiscoveryResult
import com.emma.duplicates.core.storage.PlatformMetadataIndex
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.ScanResultMapper
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.data.preferences.AppPreferences
import com.emma.duplicates.data.preferences.PreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

enum class ScanRunOutcome {
    COMPLETED,
    STORAGE_ACCESS_REQUIRED,
    EMPTY_SCOPE,
    FAILED,
}

// Explicit dependencies keep the scan pipeline independently replaceable in tests.
@Suppress("LongParameterList")
class ScanRunner(
    private val context: Context,
    private val storageAccessManager: StorageAccessManager,
    private val storageVolumeSource: StorageVolumeSource,
    private val mediaStoreMetadataIndex: PlatformMetadataIndex,
    private val preferencesRepository: PreferencesRepository,
    private val scanStore: ScanStore,
    private val fileDiscovery: FileDiscovery,
    private val exactDuplicateScanner: ExactDuplicateScanner,
    private val scanResultMapper: ScanResultMapper,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        sessionId: String,
        onSessionStarted: suspend () -> Unit = {},
        onProgress: suspend (ScanProgressUpdate) -> Unit = {},
    ): ScanRunOutcome {
        val attempt = ScanAttempt()
        return try {
            scanStore.startSession(sessionId, currentTimeMillis())
            attempt.sessionStarted = true
            onSessionStarted()
            scanSession(sessionId, onProgress, attempt)
        } catch (cancellation: CancellationException) {
            if (attempt.sessionStarted) {
                withContext(NonCancellable) {
                    scanStore.cancelSession(sessionId, currentTimeMillis())
                }
            }
            throw cancellation
        } catch (scopeUnavailable: ScanScopeUnavailableException) {
            failStartedSession(attempt.sessionStarted, sessionId, attempt.errorCount, scopeUnavailable.failureReason)
            scopeUnavailable.outcome
        } catch (_: ForegroundWorkerFailureException) {
            failStartedSession(
                attempt.sessionStarted,
                sessionId,
                attempt.errorCount,
                ScanFailureReasons.FOREGROUND_WORKER,
            )
            ScanRunOutcome.FAILED
        } catch (_: SQLiteFullException) {
            failStartedSession(attempt.sessionStarted, sessionId, attempt.errorCount, ScanFailureReasons.STORAGE_FULL)
            ScanRunOutcome.FAILED
        } catch (_: SQLiteException) {
            failStartedSession(attempt.sessionStarted, sessionId, attempt.errorCount, ScanFailureReasons.DATABASE)
            ScanRunOutcome.FAILED
        } catch (_: Exception) {
            failStartedSession(attempt.sessionStarted, sessionId, attempt.errorCount, ScanFailureReasons.GENERIC)
            ScanRunOutcome.FAILED
        }
    }

    private suspend fun scanSession(
        sessionId: String,
        onProgress: suspend (ScanProgressUpdate) -> Unit,
        attempt: ScanAttempt,
    ): ScanRunOutcome {
        val scope = captureScope()
        val mediaStoreRefresh = mediaStoreMetadataIndex.refresh(scope.volumes)
        attempt.errorCount += mediaStoreRefresh.errorCount
        val exclusions = scanExclusions()
        val discovery = discoverFiles(sessionId, scope, exclusions, attempt, onProgress)
        ensureScopeAvailable(scope.capturedRoots)
        attempt.errorCount += discovery.errorCount
        val scannedBytes = discovery.files.sumOf { it.sizeBytes }
        publishProgress(
            sessionId,
            ScanProgressUpdate(
                phase = ScanPhases.COMPARING_CANDIDATES,
                completedWork = FINDING_FILES_COMPLETE,
                totalWork = OVERALL_PROGRESS_TOTAL,
                scannedFileCount = discovery.files.size,
                scannedByteCount = scannedBytes,
                candidateGroupCount =
                    discovery.files
                        .groupingBy { it.sizeBytes }
                        .eachCount()
                        .count { it.value > 1 },
                skippedFileCount = discovery.skippedFileCount,
                errorCount = attempt.errorCount,
            ),
            onProgress,
        )

        val scanResult = compareFiles(sessionId, discovery, scannedBytes, attempt, onProgress)
        attempt.errorCount += scanResult.errorCount
        publishProgress(
            sessionId,
            ScanProgressUpdate(
                phase = ScanPhases.VERIFYING_DUPLICATES,
                completedWork = OVERALL_PROGRESS_TOTAL,
                totalWork = OVERALL_PROGRESS_TOTAL,
                scannedFileCount = discovery.files.size,
                scannedByteCount = scannedBytes,
                duplicateGroupCount = scanResult.duplicateGroupCount,
                reclaimableBytes = scanResult.reclaimableBytes,
                skippedFileCount = discovery.skippedFileCount + scanResult.skippedFileCount,
                errorCount = attempt.errorCount,
            ),
            onProgress,
        )
        return persistResults(sessionId, scope, discovery, scanResult, attempt)
    }

    private suspend fun scanExclusions(): Set<ScanExclusion> =
        scanStore.getExclusions().mapTo(linkedSetOf()) { exclusion ->
            ScanExclusion(
                canonicalPath = exclusion.canonicalPath,
                type =
                    if (exclusion.type == ExclusionTypes.FOLDER) {
                        ExclusionType.FOLDER
                    } else {
                        ExclusionType.FILE
                    },
            )
        }

    private suspend fun captureScope(): ScanScope {
        storageAccessManager.ensureStorageAccess()
        val preferences = preferencesRepository.preferences.first()
        val categories = preferences.enabledCategories()
        if (categories.isEmpty()) {
            throw ScanScopeUnavailableException(
                outcome = ScanRunOutcome.EMPTY_SCOPE,
                failureReason = ScanFailureReasons.GENERIC,
            )
        }
        val selectedRoots =
            if (preferences.scanSelectedItemsOnly) {
                com.emma.duplicates.data
                    .StorageBrowser(storageVolumeSource, context.applicationDirectories())
                    .scanRoots(preferences.selectedScanPaths)
            } else {
                null
            }
        val selectedIds = selectedRoots?.map { it.id }?.toSet() ?: preferences.selectedVolumeIds
        val mountedSelectedVolumes = storageVolumeSource.mountedVolumes().filter { it.id in selectedIds }
        val capturedRoots =
            captureSelectedRoots(mountedSelectedVolumes)
                ?.takeIf { !preferences.scanSelectedItemsOnly || selectedRoots != null }
                ?: throw ScanScopeUnavailableException(
                    outcome = ScanRunOutcome.EMPTY_SCOPE,
                    failureReason = ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE,
                )
        val volumes = mountedSelectedVolumes.filter { it.id in capturedRoots }
        return ScanScope(
            preferences,
            categories,
            capturedRoots,
            volumes,
            selectedRoots ?: volumes.map { it.toScanRoot() },
        )
    }

    private suspend fun discoverFiles(
        sessionId: String,
        scope: ScanScope,
        exclusions: Set<ScanExclusion>,
        attempt: ScanAttempt,
        onProgress: suspend (ScanProgressUpdate) -> Unit,
    ): FileDiscoveryResult {
        val preferences = scope.preferences
        var lastProgressAt = 0L
        val discovery =
            fileDiscovery.discover(
                roots = scope.volumes.map { it.toScanRoot() },
                options =
                    FileDiscoveryOptions(
                        scanHiddenFolders = preferences.scanHiddenFolders,
                        ignoreSystemFolders = preferences.ignoreSystemFolders,
                        enabledCategories = scope.categories,
                        exclusions = exclusions,
                        applicationDirectories = context.applicationDirectories(),
                        includedPaths =
                            if (preferences.scanSelectedItemsOnly) {
                                scope.scanRoots.map { it.directory.path }.toSet()
                            } else {
                                emptySet()
                            },
                    ),
            ) { progress ->
                val now = System.nanoTime()
                if (now - lastProgressAt >= PROGRESS_INTERVAL_NANOS) {
                    lastProgressAt = now
                    publishProgress(
                        sessionId = sessionId,
                        update =
                            ScanProgressUpdate(
                                phase = ScanPhases.FINDING_FILES,
                                currentPath = progress.currentPath,
                                completedWork = progress.visitedEntryCount.toLong(),
                                totalWork = null,
                                scannedFileCount = progress.discoveredFileCount,
                                scannedByteCount = progress.scannedByteCount,
                                skippedFileCount = 0,
                                errorCount = attempt.errorCount,
                            ),
                        onProgress = onProgress,
                    )
                }
            }
        return discovery
    }

    private suspend fun compareFiles(
        sessionId: String,
        discovery: FileDiscoveryResult,
        scannedBytes: Long,
        attempt: ScanAttempt,
        onProgress: suspend (ScanProgressUpdate) -> Unit,
    ): ExactDuplicateScanResult {
        var lastScannerProgressAt = 0L
        var lastScannerPhase: ExactDuplicateScanPhase? = null
        val candidateGroupCount =
            discovery.files
                .groupingBy { it.sizeBytes }
                .eachCount()
                .count { it.value > 1 }
        val scanResult =
            exactDuplicateScanner.scan(discovery.files) { progress ->
                val now = System.nanoTime()
                if (
                    progress.phase != lastScannerPhase ||
                    progress.completedWork == progress.totalWork ||
                    now - lastScannerProgressAt >= PROGRESS_INTERVAL_NANOS
                ) {
                    lastScannerProgressAt = now
                    lastScannerPhase = progress.phase
                    val overallProgress = progress.toOverallProgress()
                    publishProgress(
                        sessionId,
                        ScanProgressUpdate(
                            phase =
                                if (progress.phase == ExactDuplicateScanPhase.VERIFYING) {
                                    ScanPhases.VERIFYING_DUPLICATES
                                } else {
                                    ScanPhases.COMPARING_CANDIDATES
                                },
                            completedWork = overallProgress,
                            totalWork = OVERALL_PROGRESS_TOTAL,
                            scannedFileCount = discovery.files.size,
                            scannedByteCount = scannedBytes,
                            candidateGroupCount = candidateGroupCount,
                            skippedFileCount = discovery.skippedFileCount,
                            errorCount = attempt.errorCount,
                        ),
                        onProgress,
                    )
                }
            }
        return scanResult
    }

    private suspend fun persistResults(
        sessionId: String,
        scope: ScanScope,
        discovery: FileDiscoveryResult,
        scanResult: ExactDuplicateScanResult,
        attempt: ScanAttempt,
    ): ScanRunOutcome {
        val preferences = scope.preferences
        val capturedRoots = scope.capturedRoots
        val scannedBytes = discovery.files.sumOf { it.sizeBytes }
        val persisted =
            scanResultMapper.map(
                sessionId = sessionId,
                discoveredFiles = discovery.files,
                scanResult = scanResult,
                autoSelect = preferences.autoSelectDuplicateCopies,
            )
        check(
            scanStore.replaceStagedResults(
                sessionId = sessionId,
                files = persisted.files,
                groups = persisted.groups,
                members = persisted.members,
            ),
        )
        val reclaimableBytes = persisted.groups.sumOf { it.reclaimableBytes }
        ensureScopeAvailable(capturedRoots)
        check(
            scanStore.completeSession(
                sessionId = sessionId,
                completedAt = currentTimeMillis(),
                metrics =
                    ScanCompletionMetrics(
                        scannedFileCount = discovery.files.size,
                        scannedByteCount = scannedBytes,
                        duplicateFileCount = scanResult.duplicateFileCount,
                        duplicateGroupCount = scanResult.duplicateGroupCount,
                        reclaimableBytes = reclaimableBytes,
                        skippedFileCount = discovery.skippedFileCount + scanResult.skippedFileCount,
                        errorCount = attempt.errorCount,
                    ),
            ),
        )
        return ScanRunOutcome.COMPLETED
    }

    private data class ScanScope(
        val preferences: AppPreferences,
        val categories: Set<FileCategory>,
        val capturedRoots: Map<String, String>,
        val volumes: List<AvailableStorageVolume>,
        val scanRoots: List<com.emma.duplicates.core.model.StorageVolumeRoot>,
    )

    private class ScanAttempt {
        var sessionStarted = false
        var errorCount = 0
    }

    private suspend fun failStartedSession(
        sessionStarted: Boolean,
        sessionId: String,
        errorCount: Int,
        failureReason: String,
    ) {
        if (!sessionStarted) return
        withContext(NonCancellable) {
            scanStore.failSession(
                sessionId = sessionId,
                completedAt = currentTimeMillis(),
                errorCount = errorCount + 1,
                failureReason = failureReason,
            )
        }
    }

    private suspend fun publishProgress(
        sessionId: String,
        update: ScanProgressUpdate,
        onProgress: suspend (ScanProgressUpdate) -> Unit,
    ) {
        scanStore.updateProgress(sessionId, update)
        onProgress(update)
    }

    private fun ensureScopeAvailable(capturedRoots: Map<String, String>) {
        storageAccessManager.ensureStorageAccess()
        val mountedRoots =
            storageVolumeSource
                .mountedVolumes()
                .mapNotNull { volume ->
                    volume.availableRoot()?.let { volume.id to it }
                }.toMap()
        if (capturedRoots.any { (id, root) -> mountedRoots[id] != root }) {
            throw ScanScopeUnavailableException(
                outcome = ScanRunOutcome.FAILED,
                failureReason = ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE,
            )
        }
    }
}

private class ScanScopeUnavailableException(
    val outcome: ScanRunOutcome,
    val failureReason: String,
) : Exception()

private fun StorageAccessManager.ensureStorageAccess() {
    if (!hasAccess()) {
        throw ScanScopeUnavailableException(
            outcome = ScanRunOutcome.STORAGE_ACCESS_REQUIRED,
            failureReason = ScanFailureReasons.PERMISSION_REVOKED,
        )
    }
}

private fun captureSelectedRoots(volumes: List<AvailableStorageVolume>): Map<String, String>? {
    val roots = volumes.mapNotNull { volume -> volume.availableRoot()?.let { volume.id to it } }.toMap()
    return roots.takeIf { it.isNotEmpty() }
}

private fun AvailableStorageVolume.availableRoot(): String? =
    runCatching { directory.canonicalFile }
        .getOrNull()
        ?.takeIf { it.isDirectory && it.canRead() }
        ?.path

private fun AppPreferences.enabledCategories(): Set<FileCategory> =
    buildSet {
        if (scanPhotos) add(FileCategory.PHOTOS)
        if (scanVideos) add(FileCategory.VIDEOS)
        if (scanAudio) add(FileCategory.AUDIO)
        if (scanDocuments) add(FileCategory.DOCUMENTS)
    }

private fun ExactDuplicateScanProgress.toOverallProgress(): Long {
    val (phaseStart, phaseSpan) =
        when (phase) {
            ExactDuplicateScanPhase.QUICK_HASHING -> {
                FINDING_FILES_COMPLETE to QUICK_HASH_PROGRESS_SPAN
            }

            ExactDuplicateScanPhase.FULL_HASHING -> {
                QUICK_HASH_COMPLETE to FULL_HASH_PROGRESS_SPAN
            }

            ExactDuplicateScanPhase.VERIFYING -> {
                COMPARING_COMPLETE to VERIFY_PROGRESS_SPAN
            }
        }
    val phaseFraction =
        if (totalWork <= 0L) {
            1.0
        } else {
            completedWork.toDouble().div(totalWork.toDouble()).coerceIn(0.0, 1.0)
        }
    return (phaseStart + (phaseSpan * phaseFraction).toLong())
        .coerceIn(phaseStart, phaseStart + phaseSpan)
}

// These paths are excluded from scanning; no data is stored in external directories here.
@Suppress("DEPRECATION", "kotlin:S5324")
private fun Context.applicationDirectories(): Set<String> =
    buildSet {
        add(filesDir.path)
        add(cacheDir.path)
        add(noBackupFilesDir.path)
        externalCacheDirs.filterNotNull().mapTo(this) { it.path }
        getExternalFilesDirs(null).filterNotNull().mapTo(this) { it.path }
        externalMediaDirs.filterNotNull().mapTo(this) { it.path }
    }

private const val PROGRESS_INTERVAL_MILLIS = 250L
private val PROGRESS_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(PROGRESS_INTERVAL_MILLIS)
private const val OVERALL_PROGRESS_TOTAL = 600_000L
private const val FINDING_FILES_COMPLETE = 200_000L
private const val QUICK_HASH_PROGRESS_SPAN = 100_000L
private const val QUICK_HASH_COMPLETE = FINDING_FILES_COMPLETE + QUICK_HASH_PROGRESS_SPAN
private const val FULL_HASH_PROGRESS_SPAN = 100_000L
private const val COMPARING_COMPLETE = QUICK_HASH_COMPLETE + FULL_HASH_PROGRESS_SPAN
private const val VERIFY_PROGRESS_SPAN = OVERALL_PROGRESS_TOTAL - COMPARING_COMPLETE
