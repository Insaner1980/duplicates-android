package com.emma.duplicates.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.R
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.domain.deletion.MediaDeletionAuthorizer
import com.emma.duplicates.ui.tools.ToolControls
import com.emma.duplicates.ui.tools.ToolOutcome
import com.emma.duplicates.ui.tools.ToolScreenState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ToolViewModel(
    private val tool: CleanupTool,
    private val requestedRun: String?,
    private val container: AppContainer,
    authorizer: MediaDeletionAuthorizer,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val store = container.toolResultStore
    private val cleanup = container.cleanupCoordinator(authorizer)
    private val folders = container.emptyFolderDeletionCoordinator
    private var pinnedRun = requestedRun
    private val local =
        MutableStateFlow(
            ToolScreenState(
                tool,
                controls =
                    ToolControls(
                        minimumBytes = saved["minimum"] ?: 100_000_000L,
                        months = saved["months"] ?: 0,
                        sort = saved["sort"] ?: "largest",
                        group = saved["group"] ?: 0,
                        folder = saved["folder"],
                    ),
            ),
        )
    val state =
        combine(
            store.active(tool),
            store.latest(tool),
            container.scanScheduler.workActive,
            container.scanScheduler.activeTool,
            local,
        ) { active, latest, workActive, activeTool, ui ->
            val matching = pinnedRun == null || latest?.id == pinnedRun || active?.session?.id == pinnedRun
            ui.copy(
                results = active?.takeIf { matching },
                latest = latest?.takeIf { matching },
                running = latest?.status == ScanStatuses.RUNNING || activeTool?.tool == tool.name,
                notice = if (!matching && !workActive && latest != null) R.string.tool_run_unavailable else ui.notice,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_TIMEOUT_MS), local.value)
    private var opened = false

    fun onResume() =
        viewModelScope.launch {
            local.update {
                it.copy(
                    hasAccess = container.storageAccessManager.hasAccess(),
                    volumes = container.storageVolumeSource.mountedVolumes(),
                )
            }
            container.scanScheduler.reconcileTools(store)
            if (!opened && local.value.hasAccess) {
                opened = true
                val hasHistory = store.active(tool).first() != null || store.latest(tool).first() != null
                if (requestedRun == null && !hasHistory) {
                    refresh()
                }
            }
        }

    fun controls(value: ToolControls) {
        saved["minimum"] = value.minimumBytes
        saved["months"] = value.months
        saved["sort"] = value.sort
        saved["group"] = value.group
        saved["folder"] = value.folder
        local.update { it.copy(controls = value) }
    }

    fun refresh(volumeId: String? = null) {
        if (local.value.busy || !container.storageAccessManager.hasAccess()) return
        viewModelScope.launch {
            try {
                container.pendingDeletionReconciler.reconcile()
                container.pendingCleanupReconciler.reconcile()
                if (store.dao.pending().isNotEmpty() || container.scanStore.pendingDeletions().isNotEmpty()) {
                    local.update { it.copy(notice = R.string.deletion_recovery_pending) }
                    return@launch
                }
                val request = container.scanScheduler.startTool(tool, volumeId ?: saved["volume"], store)
                if (request?.tool == tool.name) {
                    pinnedRun = null
                    saved["volume"] = volumeId ?: saved.get<String>("volume")
                    controls(local.value.controls.copy(folder = null))
                    local.update { it.copy(notice = null) }
                } else {
                    local.update { it.copy(notice = R.string.tool_scan_busy) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                local.update { it.copy(notice = R.string.scan_not_started) }
            }
        }
    }

    fun stop() = container.scanScheduler.stopScan()

    fun selectScreenshots(
        ids: Set<String>,
        selected: Boolean,
    ) {
        val current = state.value
        val editing = local.value.busy || current.busy || current.running
        if (tool != CleanupTool.SCREENSHOTS || editing) return
        val session = current.results?.session ?: return
        val visibleIds = current.visibleItems.filter { !it.keeper }.mapTo(HashSet()) { it.id }
        val requested = ids.intersect(visibleIds)
        if (requested.isNotEmpty()) {
            viewModelScope.launch {
                store.setScreenshotSelection(
                    session.id,
                    requested,
                    selected,
                )
            }
        }
    }

    fun select(
        id: String,
        selected: Boolean,
    ) {
        if (state.value.busy || state.value.running) return
        val session = state.value.results?.session ?: return
        viewModelScope.launch { store.setSelected(session.id, id, selected) }
    }

    fun requestCleanup() {
        val current = state.value
        val session = current.results?.session ?: return
        if (current.busy || current.running || current.selected.isEmpty()) return
        local.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                if (tool == CleanupTool.EMPTY_FOLDERS) {
                    val batch = folders.prepare(session.id)
                    local.update { it.copy(folderConfirmation = batch) }
                } else {
                    val batch = cleanup.prepare(session.id, current.visibleItems.map { it.id }.toSet())
                    if (batch.files.isEmpty()) {
                        local.update { it.copy(notice = R.string.tool_changed) }
                    } else {
                        local.update { it.copy(fileConfirmation = batch) }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: IllegalArgumentException) {
                local.update { it.copy(notice = R.string.tool_changed) }
            } catch (_: android.database.sqlite.SQLiteException) {
                local.update { it.copy(notice = R.string.deletion_recovery_pending) }
            } finally {
                local.update { it.copy(busy = false) }
            }
            if (!container.preferencesRepository.confirmBeforeDelete.first()) confirmCleanup()
        }
    }

    fun dismissConfirmation() = local.update { it.copy(fileConfirmation = null, folderConfirmation = null) }

    fun dismissOutcome() = local.update { it.copy(outcome = null, notice = null) }

    fun confirmCleanup() {
        val fileBatch = local.value.fileConfirmation
        val folderBatch = local.value.folderConfirmation
        if (fileBatch == null && folderBatch == null) return
        local.update { it.copy(fileConfirmation = null, folderConfirmation = null, busy = true) }
        viewModelScope.launch {
            try {
                val outcome =
                    if (fileBatch != null) {
                        val result = cleanup.execute(fileBatch)
                        ToolOutcome(result.movedCount, result.failedCount, false)
                    } else {
                        val result = folders.execute(requireNotNull(folderBatch))
                        ToolOutcome(result.deletedCount, result.skippedCount, true)
                    }
                local.update { it.copy(outcome = outcome) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                local.update { it.copy(notice = R.string.deletion_recovery_pending) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        const val STATE_TIMEOUT_MS = 5_000L
    }
}
