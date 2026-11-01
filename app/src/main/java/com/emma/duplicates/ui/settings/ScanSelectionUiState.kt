package com.emma.duplicates.ui.settings

import com.emma.duplicates.data.StorageBrowserLocation

data class ScanSelectionUiState(
    val selectedItemsOnly: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val locations: List<ScanLocationUiState> = emptyList(),
    val location: StorageBrowserLocation? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val messageRes: Int? = null,
) {
    val canSave: Boolean
        get() =
            !loading && !saving &&
                if (selectedItemsOnly) selectedPaths.isNotEmpty() else locations.any { it.selected }
}
