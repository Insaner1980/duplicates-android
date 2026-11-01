package com.emma.duplicates.app

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.R
import com.emma.duplicates.core.database.ScanFailureReasons
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanSessionEntity
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.ui.scan.ScanPhase
import com.emma.duplicates.ui.scan.ScanPhaseUiState
import com.emma.duplicates.ui.scan.ScanRunStatus
import com.emma.duplicates.ui.scan.ScanStepStatus
import com.emma.duplicates.ui.scan.ScanningUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class ScanViewModel(
    context: Context,
    scanStore: ScanStore,
    private val scanScheduler: ScanScheduler,
    storageVolumeSource: StorageVolumeSource = StorageVolumeSource { emptyList() },
    private val savedStateHandle: SavedStateHandle,
    workActive: Flow<Boolean> = flowOf(true),
) : ViewModel() {
    private val request =
        ScanNavigationRequest(
            savedStateHandle["baselineSessionId"],
            savedStateHandle["awaitNewSession"] ?: false,
        )
    private val resources = context.applicationContext.resources
    private val mountedVolumes = storageVolumeSource.mountedVolumes()
    private val stopConfirmationVisible = MutableStateFlow(false)

    val state: StateFlow<ScanningUiState> =
        combine(scanStore.latestSession, scanStore.activeCompletedSession, stopConfirmationVisible, workActive) {
            session,
            active,
            confirmationVisible,
            activeWork,
            ->
            val boundId = savedStateHandle.get<String>("boundSessionId")
            val relevant =
                session?.takeIf {
                    if (boundId != null) {
                        it.id == boundId
                    } else {
                        !request.awaitNewSession || it.id != request.baselineSessionId
                    }
                }
            if (relevant != null) {
                savedStateHandle["boundSessionId"] = relevant.id
                relevant.toUiState(confirmationVisible).copy(
                    sessionId = relevant.id,
                    completionReady = relevant.status == ScanStatuses.COMPLETED && active?.id == relevant.id,
                )
            } else if (!activeWork) {
                initialState(false).copy(
                    runStatus = ScanRunStatus.CANCELED,
                    failureMessage = resources.getString(R.string.scan_not_started),
                )
            } else {
                initialState(confirmationVisible)
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MILLIS),
            initialState(false),
        )

    fun requestStop() {
        stopConfirmationVisible.value = true
    }

    fun dismissStop() {
        stopConfirmationVisible.value = false
    }

    fun confirmStop() {
        stopConfirmationVisible.value = false
        scanScheduler.stopScan()
    }

    private fun ScanSessionEntity.toUiState(showConfirmation: Boolean): ScanningUiState {
        val runStatus =
            when (status) {
                ScanStatuses.COMPLETED -> ScanRunStatus.COMPLETED
                ScanStatuses.CANCELED -> ScanRunStatus.CANCELED
                ScanStatuses.FAILED -> ScanRunStatus.FAILED
                else -> ScanRunStatus.RUNNING
            }
        return ScanningUiState(
            runStatus = runStatus,
            overallProgress = progressFraction(runStatus),
            filesIndexed = scannedFileCount,
            groupsFound =
                if (phase == ScanPhases.VERIFYING_DUPLICATES || runStatus == ScanRunStatus.COMPLETED) {
                    duplicateGroupCount
                } else {
                    candidateGroupCount
                },
            groupsAreConfirmed =
                phase == ScanPhases.VERIFYING_DUPLICATES || runStatus == ScanRunStatus.COMPLETED,
            reclaimableBytes = reclaimableBytes,
            phases = phaseStates(phase, runStatus),
            currentOperation = currentPath?.let(::locationLabel),
            currentPath = currentPath,
            stopConfirmationVisible = showConfirmation && runStatus == ScanRunStatus.RUNNING,
            skippedFileCount = skippedFileCount,
            failureMessage =
                when (runStatus) {
                    ScanRunStatus.FAILED -> resources.getString(failureReason.messageResource())
                    ScanRunStatus.CANCELED -> resources.getString(R.string.scan_canceled_message)
                    else -> null
                },
        )
    }

    private fun String?.messageResource(): Int =
        when (this) {
            ScanFailureReasons.PERMISSION_REVOKED -> R.string.scan_failure_permission_revoked
            ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE -> R.string.scan_failure_volume_unavailable
            ScanFailureReasons.STORAGE_FULL -> R.string.scan_failure_storage_full
            ScanFailureReasons.DATABASE -> R.string.scan_failure_database
            ScanFailureReasons.FOREGROUND_WORKER -> R.string.scan_failure_foreground_worker
            else -> R.string.scan_failure_generic
        }

    private fun ScanSessionEntity.progressFraction(runStatus: ScanRunStatus): Float? =
        if (runStatus == ScanRunStatus.COMPLETED) {
            1f
        } else {
            progressTotalWork?.takeIf { it > 0L }?.let { total ->
                (progressCompletedWork.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
            }
        }

    private fun locationLabel(path: String): String {
        val normalizedPath = path.normalizedPath()
        return mountedVolumes
            .filter { volume ->
                val root = volume.directory.path.normalizedPath()
                normalizedPath == root || normalizedPath.startsWith("$root/")
            }.maxByOrNull { it.directory.path.length }
            ?.label
            ?: resources.getString(R.string.current_location)
    }

    private fun String.normalizedPath(): String = replace('\\', '/').trimEnd('/')

    private fun phaseStates(
        currentPhase: String?,
        runStatus: ScanRunStatus,
    ): List<ScanPhaseUiState> {
        if (runStatus == ScanRunStatus.COMPLETED) {
            return ScanPhase.entries.map { ScanPhaseUiState(it, ScanStepStatus.COMPLETED) }
        }
        val currentIndex =
            when (currentPhase) {
                ScanPhases.COMPARING_CANDIDATES -> 1
                ScanPhases.VERIFYING_DUPLICATES -> 2
                else -> 0
            }
        return ScanPhase.entries.mapIndexed { index, phase ->
            ScanPhaseUiState(
                phase = phase,
                status =
                    when {
                        index < currentIndex -> ScanStepStatus.COMPLETED
                        index == currentIndex && runStatus == ScanRunStatus.RUNNING -> ScanStepStatus.IN_PROGRESS
                        else -> ScanStepStatus.PENDING
                    },
            )
        }
    }

    private fun initialState(showConfirmation: Boolean) =
        ScanningUiState(
            runStatus = ScanRunStatus.RUNNING,
            overallProgress = null,
            filesIndexed = 0,
            groupsFound = 0,
            groupsAreConfirmed = false,
            reclaimableBytes = 0L,
            phases =
                listOf(
                    ScanPhaseUiState(ScanPhase.FINDING_FILES, ScanStepStatus.IN_PROGRESS),
                    ScanPhaseUiState(ScanPhase.COMPARING_CANDIDATES, ScanStepStatus.PENDING),
                    ScanPhaseUiState(ScanPhase.VERIFYING_DUPLICATES, ScanStepStatus.PENDING),
                ),
            currentOperation = null,
            currentPath = null,
            stopConfirmationVisible = showConfirmation,
        )

    private companion object {
        const val STATE_STOP_TIMEOUT_MILLIS = 5_000L
    }
}
