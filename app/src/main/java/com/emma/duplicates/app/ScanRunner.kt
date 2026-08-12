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
import com.emma.duplicates.core.storage.ExactDuplicateScanner
import com.emma.duplicates.core.storage.ExactDuplicateScanPhase
import com.emma.duplicates.core.storage.ExactDuplicateScanProgress
import com.emma.duplicates.core.storage.FileDiscovery
import com.emma.duplicates.core.storage.FileDiscoveryOptions
import com.emma.duplicates.core.storage.PlatformMetadataIndex
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.ScanResultMapper
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.data.preferences.AppPreferences
import com.emma.duplicates.data.preferences.PreferencesRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class ScanRunOutcome {
    COMPLETED,
    STORAGE_ACCESS_REQUIRED,
    EMPTY_SCOPE,
    FAILED,
}

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
        var sessionStarted = false
        var errorCount = 0
        return try {
            scanStore.startSession(sessionId, currentTimeMillis())
            sessionStarted = true
            onSessionStarted()
            if (!storageAccessManager.hasAccess()) {
                throw ScanScopeUnavailableException(
                    outcome = ScanRunOutcome.STORAGE_ACCESS_REQUIRED,
                    failureReason = ScanFailureReasons.PERMISSION_REVOKED,
                )
            }
            val preferences = preferencesRepository.preferences.first()
            val categories = preferences.enabledCategories()
            if (categories.isEmpty()) {
                throw ScanScopeUnavailableException(
                    outcome = ScanRunOutcome.EMPTY_SCOPE,
                    failureReason = ScanFailureReasons.GENERIC,
                )
            }
            val mountedSelectedVolumes =
                storageVolumeSource.mountedVolumes().filter { it.id in preferences.selectedVolumeIds }
            val capturedRoots =
                captureSelectedRoots(mountedSelectedVolumes)
                    ?: throw ScanScopeUnavailableException(
                        outcome = ScanRunOutcome.EMPTY_SCOPE,
                        failureReason = ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE,
                    )
            val volumes = mountedSelectedVolumes.filter { it.id in capturedRoots }

            val mediaStoreRefresh = mediaStoreMetadataIndex.refresh(volumes)
            errorCount += mediaStoreRefresh.errorCount
            val exclusions =
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
            var lastProgressAt = 0L
            val discovery =
                fileDiscovery.discover(
                    roots = volumes.map { it.toScanRoot() },
                    options =
                        FileDiscoveryOptions(
                            scanHiddenFolders = preferences.scanHiddenFolders,
                            ignoreSystemFolders = preferences.ignoreSystemFolders,
                            enabledCategories = categories,
                            exclusions = exclusions,
                            applicationDirectories = context.applicationDirectories(),
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
                                    errorCount = errorCount,
                                ),
                            onProgress = onProgress,
                        )
                    }
                }
            ensureScopeAvailable(capturedRoots)
            errorCount += discovery.errorCount
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
                        discovery.files.groupingBy { it.sizeBytes }.eachCount().count { it.value > 1 },
                    skippedFileCount = discovery.skippedFileCount,
                    errorCount = errorCount,
                ),
                onProgress,
            )

            var lastScannerProgressAt = 0L
            var lastScannerPhase: ExactDuplicateScanPhase? = null
            val candidateGroupCount =
                discovery.files.groupingBy { it.sizeBytes }.eachCount().count { it.value > 1 }
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
                                errorCount = errorCount,
                            ),
                            onProgress,
                        )
                    }
                }
            errorCount += scanResult.errorCount
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
                    errorCount = errorCount,
                ),
                onProgress,
            )
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
                            errorCount = errorCount,
                        ),
                ),
            )
            ScanRunOutcome.COMPLETED
        } catch (cancellation: CancellationException) {
            if (sessionStarted) {
                withContext(NonCancellable) {
                    scanStore.cancelSession(sessionId, currentTimeMillis())
                }
            }
            throw cancellation
        } catch (scopeUnavailable: ScanScopeUnavailableException) {
            failStartedSession(sessionStarted, sessionId, errorCount, scopeUnavailable.failureReason)
            scopeUnavailable.outcome
        } catch (_: ForegroundWorkerFailureException) {
            failStartedSession(
                sessionStarted,
                sessionId,
                errorCount,
                ScanFailureReasons.FOREGROUND_WORKER,
            )
            ScanRunOutcome.FAILED
        } catch (_: SQLiteFullException) {
            failStartedSession(sessionStarted, sessionId, errorCount, ScanFailureReasons.STORAGE_FULL)
            ScanRunOutcome.FAILED
        } catch (_: SQLiteException) {
            failStartedSession(sessionStarted, sessionId, errorCount, ScanFailureReasons.DATABASE)
            ScanRunOutcome.FAILED
        } catch (_: Exception) {
            failStartedSession(sessionStarted, sessionId, errorCount, ScanFailureReasons.GENERIC)
            ScanRunOutcome.FAILED
        }
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

    private fun captureSelectedRoots(volumes: List<AvailableStorageVolume>): Map<String, String>? {
        val roots = volumes.mapNotNull { volume -> volume.availableRoot()?.let { volume.id to it } }.toMap()
        return roots.takeIf { it.isNotEmpty() }
    }

    private fun ensureScopeAvailable(capturedRoots: Map<String, String>) {
        if (!storageAccessManager.hasAccess()) {
            throw ScanScopeUnavailableException(
                outcome = ScanRunOutcome.STORAGE_ACCESS_REQUIRED,
                failureReason = ScanFailureReasons.PERMISSION_REVOKED,
            )
        }
        val mountedRoots =
            storageVolumeSource.mountedVolumes().mapNotNull { volume ->
                volume.availableRoot()?.let { volume.id to it }
            }.toMap()
        if (capturedRoots.any { (id, root) -> mountedRoots[id] != root }) {
            throw ScanScopeUnavailableException(
                outcome = ScanRunOutcome.FAILED,
                failureReason = ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE,
            )
        }
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
                ExactDuplicateScanPhase.QUICK_HASHING ->
                    FINDING_FILES_COMPLETE to QUICK_HASH_PROGRESS_SPAN
                ExactDuplicateScanPhase.FULL_HASHING ->
                    QUICK_HASH_COMPLETE to FULL_HASH_PROGRESS_SPAN
                ExactDuplicateScanPhase.VERIFYING ->
                    COMPARING_COMPLETE to VERIFY_PROGRESS_SPAN
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

    @Suppress("DEPRECATION")
    private fun Context.applicationDirectories(): Set<String> =
        buildSet {
            add(filesDir.path)
            add(cacheDir.path)
            add(noBackupFilesDir.path)
            externalCacheDirs.filterNotNull().mapTo(this) { it.path }
            getExternalFilesDirs(null).filterNotNull().mapTo(this) { it.path }
            externalMediaDirs.filterNotNull().mapTo(this) { it.path }
        }

    private companion object {
        val PROGRESS_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250)
        const val OVERALL_PROGRESS_TOTAL = 600_000L
        const val FINDING_FILES_COMPLETE = 200_000L
        const val QUICK_HASH_PROGRESS_SPAN = 100_000L
        const val QUICK_HASH_COMPLETE = FINDING_FILES_COMPLETE + QUICK_HASH_PROGRESS_SPAN
        const val FULL_HASH_PROGRESS_SPAN = 100_000L
        const val COMPARING_COMPLETE = QUICK_HASH_COMPLETE + FULL_HASH_PROGRESS_SPAN
        const val VERIFY_PROGRESS_SPAN = OVERALL_PROGRESS_TOTAL - COMPARING_COMPLETE
    }
}

private class ScanScopeUnavailableException(
    val outcome: ScanRunOutcome,
    val failureReason: String,
) : Exception()
