package com.emma.duplicates.app

import com.emma.duplicates.core.permissions.StorageAccessManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionViewModelTest {
    @Test
    fun refreshReflectsPermissionGrantedInSystemSettings() {
        var granted = false
        val viewModel =
            PermissionViewModel(
                StorageAccessManager("com.emma.duplicates") { granted },
            )

        assertFalse(viewModel.storageAccessGranted.value)
        granted = true

        viewModel.refreshStorageAccess()

        assertTrue(viewModel.storageAccessGranted.value)
    }
}
