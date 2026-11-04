package com.emma.duplicates.ui.settings

import com.emma.duplicates.data.RecoveryEntry

enum class RecoveryAction { RESTORE, DELETE }

data class RecoveryOutcome(
    val action: RecoveryAction,
    val succeeded: Int,
    val failed: Int,
)

data class RecoveryUiState(
    val entries: List<RecoveryEntry> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val busy: Boolean = false,
    val pendingDeleteIds: Set<String>? = null,
    val outcome: RecoveryOutcome? = null,
)
