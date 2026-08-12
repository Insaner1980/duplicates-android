package com.emma.duplicates.app

import androidx.lifecycle.ViewModel
import com.emma.duplicates.core.permissions.StorageAccessManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PermissionViewModel(
    private val storageAccessManager: StorageAccessManager,
) : ViewModel() {
    private val _storageAccessGranted = MutableStateFlow(storageAccessManager.hasAccess())
    val storageAccessGranted: StateFlow<Boolean> = _storageAccessGranted.asStateFlow()

    fun refreshStorageAccess() {
        _storageAccessGranted.value = storageAccessManager.hasAccess()
    }
}
