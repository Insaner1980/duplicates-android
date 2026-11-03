package com.emma.duplicates.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.data.RecoveryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RecoveryViewModel(
    private val repository: RecoveryRepository,
    private val operationMutex: Mutex = Mutex(),
    private val canMutate: suspend () -> Boolean = { true },
) : ViewModel() {
    val files = repository.files.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableResult = MutableStateFlow<String?>(null)
    val result = mutableResult.asStateFlow()

    init {
        viewModelScope.launch { operationMutex.withLock { if (canMutate()) repository.removeExpired() } }
    }

    fun restore(id: String) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                operationMutex.withLock {
                    mutableResult.value = if (canMutate()) repository.restore(id) ?: "" else ""
                }
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun dismissResult() {
        mutableResult.value = null
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
