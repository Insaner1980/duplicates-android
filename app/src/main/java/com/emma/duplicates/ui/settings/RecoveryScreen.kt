package com.emma.duplicates.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.CleanupMetadata
import com.emma.duplicates.core.designsystem.CleanupRowTitle
import com.emma.duplicates.core.designsystem.CleanupTitle
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.designsystem.cleanupCheckboxColors
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.core.storage.FileCategoryResolver
import com.emma.duplicates.data.RecoveryEntry
import com.emma.duplicates.ui.components.MediaThumbnail
import com.emma.duplicates.ui.components.rememberVideoThumbnailImageLoader
import com.emma.duplicates.ui.model.UiFileCategory
import com.emma.duplicates.ui.tools.groupedRowShape
import java.io.File

@Composable
fun RecoveryScreen(
    state: RecoveryUiState,
    onSelect: (String, Boolean) -> Unit,
    onRestore: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val footerInList = maxHeight < 360.dp
        val stackedMetadata = maxWidth < 360.dp || LocalDensity.current.fontScale >= LARGE_FONT_SCALE
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.recovery), Modifier.semantics { heading() }, style = CleanupTitle)
            }
            LazyColumn(
                Modifier.weight(1f).testTag("recovery-list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            pluralStringResource(R.plurals.tool_count, state.entries.size, state.entries.size),
                            style = CleanupRowTitle,
                        )
                        Text(recoverySize(state.entries), Modifier.testTag("recovery-total"), style = CleanupRowTitle)
                    }
                }
                if (state.entries.isEmpty()) item { Text(stringResource(R.string.tool_no_results)) }
                itemsIndexed(state.entries, key = { _, entry -> entry.file.id }) { index, entry ->
                    RecoveryRow(
                        entry,
                        entry.file.id in state.selectedIds,
                        state.busy,
                        stackedMetadata,
                        index == 0,
                        index == state.entries.lastIndex,
                        { onSelect(entry.file.id, it) },
                    )
                }
                if (footerInList) item { RecoveryFooter(state, onRestore, onRequestDelete) }
            }
            if (!footerInList) RecoveryFooter(state, onRestore, onRequestDelete)
        }
    }
    RecoveryDialogs(state, onConfirmDelete, onDismissDelete, onDismissResult)
}

@Composable
private fun RecoveryRow(
    entry: RecoveryEntry,
    selected: Boolean,
    busy: Boolean,
    stackedMetadata: Boolean,
    first: Boolean,
    last: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val original = File(entry.file.originalPath)
    val description = stringResource(R.string.tool_select_item, original.name)
    val category = FileCategoryResolver().resolve(null, original.extension)
    Surface(
        Modifier
            .fillMaxWidth()
            .testTag("recovery-row-${entry.file.id}")
            .toggleable(selected, enabled = !busy, role = Role.Checkbox, onValueChange = onSelect)
            .semantics { contentDescription = description },
        shape = groupedRowShape(first, last),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                Modifier.heightIn(min = 88.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(selected, null, Modifier.size(48.dp), enabled = !busy, colors = cleanupCheckboxColors())
                MediaThumbnail(
                    UiFileCategory.valueOf(category.name),
                    entry.previewPath?.let(::File),
                    null,
                    null,
                    rememberVideoThumbnailImageLoader(),
                    false,
                    Modifier.clip(RoundedCornerShape(8.dp)),
                    size = 48.dp,
                    fileName = original.name,
                )
                RecoveryMetadata(entry, stackedMetadata, Modifier.weight(1f))
                if (!stackedMetadata) RecoveryExpiry(entry, Modifier.align(Alignment.Top))
            }
            if (!last) HorizontalDivider(Modifier.padding(start = 120.dp, end = 8.dp))
        }
    }
}

@Composable
private fun RecoveryExpiry(
    entry: RecoveryEntry,
    modifier: Modifier = Modifier,
) {
    val expiry = entry.file.expiresAt?.let { DisplayFormatters.date(it) } ?: stringResource(R.string.cleanup_unknown)
    val description = if (entry.file.expiresAt == null) stringResource(R.string.recovery_no_expiry) else expiry
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Icon(
            painterResource(R.drawable.ic_clock),
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(expiry, style = CleanupMetadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecoveryFooter(
    state: RecoveryUiState,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val selected = state.entries.filter { it.file.id in state.selectedIds }
    val enabled = selected.isNotEmpty() && !state.busy
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                pluralStringResource(R.plurals.tool_selected_count, selected.size, selected.size),
                style = CleanupRowTitle,
            )
            Text(recoverySize(selected), Modifier.testTag("recovery-selected-size"), style = CleanupRowTitle)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        PrimaryActionButton(onRestore, Modifier.fillMaxWidth().testTag("recovery-restore"), enabled, compact = true) {
            Text(stringResource(R.string.recovery_restore), style = CleanupRowTitle)
        }
        OutlinedButton(
            onDelete,
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recovery-delete"),
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                stringResource(R.string.recovery_delete),
                style = CleanupRowTitle,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
private fun RecoveryDialogs(
    state: RecoveryUiState,
    onConfirm: () -> Unit,
    onDismissDelete: () -> Unit,
    onDismissResult: () -> Unit,
) {
    state.pendingDeleteIds?.let { ids ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.recovery_delete_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.tool_confirm_move,
                        ids.size,
                        ids.size,
                        recoverySize(state.entries.filter { it.file.id in ids }),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onConfirm,
                    Modifier.testTag("recovery-confirm-delete"),
                ) { Text(stringResource(R.string.recovery_delete)) }
            },
            dismissButton = { TextButton(onDismissDelete) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    state.outcome?.let { outcome ->
        val result =
            when {
                outcome.action == RecoveryAction.RESTORE && outcome.failed == 0 -> {
                    stringResource(
                        R.string.recovery_restored_count,
                        outcome.succeeded,
                    )
                }

                outcome.action == RecoveryAction.RESTORE -> {
                    stringResource(
                        R.string.recovery_restore_outcome,
                        outcome.succeeded,
                        outcome.failed,
                    )
                }

                outcome.failed == 0 -> {
                    stringResource(R.string.recovery_deleted_count, outcome.succeeded)
                }

                else -> {
                    stringResource(R.string.recovery_delete_outcome, outcome.succeeded, outcome.failed)
                }
            }
        AlertDialog(
            onDismissRequest = onDismissResult,
            title = { Text(result) },
            confirmButton = { TextButton(onDismissResult) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

@Composable
private fun recoverySize(entries: List<RecoveryEntry>): String =
    if (entries.any { it.sizeBytes == null }) {
        stringResource(R.string.cleanup_unknown)
    } else {
        DisplayFormatters.size(LocalResources.current, entries.sumOf { it.sizeBytes ?: 0L })
    }

@Composable
private fun RecoveryMetadata(
    entry: RecoveryEntry,
    stackedMetadata: Boolean,
    modifier: Modifier = Modifier,
) {
    val original = File(entry.file.originalPath)
    Column(modifier) {
        Text(
            original.name,
            Modifier.testTag("file-name-${entry.file.id}").semantics {
                contentDescription = "${original.name} ${entry.file.originalPath}"
            },
            style = CleanupRowTitle,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Text(
            entry.sizeBytes?.let { DisplayFormatters.size(LocalResources.current, it) }
                ?: stringResource(R.string.cleanup_unknown),
            style = CleanupMetadata,
        )
        Text(
            entry.file.originalPath
                .substringBeforeLast(
                    '/',
                    "",
                ).removePrefix("/storage/emulated/0/")
                .trimStart('/'),
            Modifier.testTag("file-path-${entry.file.id}"),
            style = CleanupMetadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
        )
        if (stackedMetadata) RecoveryExpiry(entry)
    }
}

private const val LARGE_FONT_SCALE = 1.5f
