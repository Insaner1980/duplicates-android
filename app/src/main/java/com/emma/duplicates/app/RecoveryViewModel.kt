package com.emma.duplicates.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.data.RecoveryRepository
import com.emma.duplicates.ui.settings.RecoveryAction
import com.emma.duplicates.ui.settings.RecoveryOutcome
import com.emma.duplicates.ui.settings.RecoveryUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RecoveryViewModel(
    private val repository: RecoveryRepository,
    private val operationMutex: Mutex = Mutex(),
    private val canMutate: suspend () -> Boolean = { true },
) : ViewModel() {
    private val mutableState = MutableStateFlow(RecoveryUiState())
    val state = mutableState.asStateFlow()
    private val refresh = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(repository.files, refresh) { files, _ -> files }.collectLatest { files ->
                val entries = repository.describe(files)
                val ids = entries.mapTo(mutableSetOf()) { it.file.id }
                mutableState.update { it.copy(entries = entries, selectedIds = it.selectedIds.intersect(ids)) }
            }
        }
        viewModelScope.launch { operationMutex.withLock { if (canMutate()) repository.removeExpired() } }
    }

    fun select(
        id: String,
        selected: Boolean,
    ) {
        mutableState.update { current ->
            if (current.busy || current.entries.none { it.file.id == id }) {
                current
            } else {
                current.copy(selectedIds = if (selected) current.selectedIds + id else current.selectedIds - id)
            }
        }
    }

    fun restoreSelected() {
        if (state.value.pendingDeleteIds != null) return
        runBatch(state.value.selectedIds.toSet(), RecoveryAction.RESTORE)
    }

    fun requestDelete() {
        mutableState.update {
            if (it.busy || it.selectedIds.isEmpty() || it.pendingDeleteIds != null) {
                it
            } else {
                it.copy(pendingDeleteIds = it.selectedIds.toSet())
            }
        }
    }

    fun confirmDelete() {
        val ids = state.value.pendingDeleteIds ?: return
        if (state.value.busy) return
        mutableState.update { it.copy(pendingDeleteIds = null) }
        runBatch(ids, RecoveryAction.DELETE)
    }

    fun dismissDelete() {
        mutableState.update { it.copy(pendingDeleteIds = null) }
    }

    fun dismissResult() {
        mutableState.update { it.copy(outcome = null) }
    }

    fun onResume() {
        refresh.update { it + 1 }
    }

    private fun runBatch(
        ids: Set<String>,
        action: RecoveryAction,
    ) {
        if (state.value.busy || ids.isEmpty()) return
        mutableState.update { it.copy(busy = true, outcome = null) }
        viewModelScope.launch {
            var succeeded = 0
            val restoredPaths = mutableListOf<String>()
            try {
                operationMutex.withLock {
                    if (canMutate()) {
                        for (id in ids) {
                            currentCoroutineContext().ensureActive()
                            if (performAction(id, action, restoredPaths)) {
                                succeeded++
                                mutableState.update { it.copy(selectedIds = it.selectedIds - id) }
                            }
                        }
                    }
                }
                mutableState.update {
                    it.copy(outcome = RecoveryOutcome(action, succeeded, ids.size - succeeded, restoredPaths.toList()))
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
                onResume()
            }
        }
    }

    private suspend fun performAction(
        id: String,
        action: RecoveryAction,
        restoredPaths: MutableList<String>,
    ): Boolean =
        try {
            when (action) {
                RecoveryAction.RESTORE -> {
                    repository.restore(id)?.let { path ->
                        restoredPaths += path
                        true
                    } ?: false
                }

                RecoveryAction.DELETE -> {
                    repository.deletePermanently(id)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.io.IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
}
