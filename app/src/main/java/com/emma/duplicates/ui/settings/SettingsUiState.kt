package com.emma.duplicates.ui.settings

data class SettingsUiState(
    val scanLocationsSummary: String,
    val scanHiddenFolders: Boolean,
    val ignoreSystemFolders: Boolean,
    val autoSelectDuplicateCopies: Boolean,
    val confirmBeforeDelete: Boolean,
    val fileTypesSummary: String,
    val storagePermissionGranted: Boolean,
    val appVersion: String,
)
