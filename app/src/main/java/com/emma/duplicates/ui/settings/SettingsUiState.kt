package com.emma.duplicates.ui.settings

import com.emma.duplicates.data.preferences.AppThemeMode

data class SettingsUiState(
    val scanLocationsSummary: String,
    val scanHiddenFolders: Boolean,
    val ignoreSystemFolders: Boolean,
    val autoSelectDuplicateCopies: Boolean,
    val confirmBeforeDelete: Boolean,
    val fileTypesSummary: String,
    val storagePermissionGranted: Boolean,
    val appVersion: String,
    val themeMode: AppThemeMode = AppThemeMode.DARK,
)
