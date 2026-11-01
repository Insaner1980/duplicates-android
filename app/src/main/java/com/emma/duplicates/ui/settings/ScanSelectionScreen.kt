package com.emma.duplicates.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.data.StorageBrowserLocation
import com.emma.duplicates.ui.components.DetailHeader
import com.emma.duplicates.ui.components.ScreenColumn
import java.nio.file.Paths

@Composable
fun ScanSelectionScreen(
    state: ScanSelectionUiState,
    onBack: () -> Unit,
    onModeChange: (Boolean) -> Unit,
    onOpenVolume: (String) -> Unit,
    onVolumeSelectionChange: (String, Boolean) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onShowStorage: () -> Unit,
    onPathSelectionChange: (String, Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(modifier, scrollable = false, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailHeader(R.string.scan_locations, onBack)
        LazyColumn(Modifier.weight(1f).testTag("scan-selection-list")) {
            val location = state.location
            if (location == null) {
                volumeEntries(state, onModeChange, onOpenVolume, onVolumeSelectionChange, onPathSelectionChange)
            } else {
                folderEntries(state, location, onOpenDirectory, onShowStorage, onPathSelectionChange)
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.messageRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        Text(
            if (state.selectedItemsOnly) {
                pluralStringResource(R.plurals.scan_items_selected, state.selectedPaths.size, state.selectedPaths.size)
            } else {
                stringResource(R.string.entire_storage)
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("scan-selection-summary"),
        )
        PrimaryActionButton(onDone, Modifier.fillMaxWidth(), enabled = state.canSave) {
            Text(stringResource(R.string.done))
        }
    }
}

@Composable
private fun SelectionMode(
    label: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SelectionPathRow(
    state: ScanSelectionUiState,
    path: String,
    label: String,
    directory: Boolean,
    onOpen: (() -> Unit)?,
    onPathSelectionChange: (String, Boolean) -> Unit,
) {
    val inherited = state.selectedPaths.any { it != path && Paths.get(path).startsWith(Paths.get(it)) }
    SelectionEntry(
        label,
        directory,
        inherited,
        inherited || path in state.selectedPaths,
        !state.loading && !state.saving,
        onOpen,
        { onPathSelectionChange(path, it) },
    )
}

@Composable
private fun SelectionEntry(
    label: String,
    directory: Boolean,
    inherited: Boolean,
    checked: Boolean?,
    enabled: Boolean,
    onOpen: (() -> Unit)?,
    onSelectionChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clickable(enabled && (onOpen != null || !inherited), role = Role.Button) {
                    if (onOpen != null) onOpen() else onSelectionChange(checked != true)
                }.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (directory) Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (inherited) {
                    Text(
                        stringResource(R.string.included_by_parent),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (onOpen != null) Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp))
        }
        if (checked != null) {
            Checkbox(
                checked,
                onSelectionChange,
                enabled = enabled && !inherited,
                modifier =
                    Modifier.semantics {
                        contentDescription = label
                    },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.volumeEntries(
    state: ScanSelectionUiState,
    onModeChange: (Boolean) -> Unit,
    onOpenVolume: (String) -> Unit,
    onVolumeSelectionChange: (String, Boolean) -> Unit,
    onPathSelectionChange: (String, Boolean) -> Unit,
) {
    item {
        SelectionMode(R.string.entire_storage, !state.selectedItemsOnly, !state.loading && !state.saving) {
            onModeChange(false)
        }
        SelectionMode(
            R.string.selected_folders_files,
            state.selectedItemsOnly,
            !state.loading && !state.saving,
        ) {
            onModeChange(true)
        }
    }
    items(state.locations, key = { it.id }) { volume ->
        SelectionEntry(
            volume.displayName,
            true,
            false,
            if (state.selectedItemsOnly) null else volume.selected,
            !state.loading && !state.saving,
            { onOpenVolume(volume.id) },
            { onVolumeSelectionChange(volume.id, it) },
        )
    }
    if (state.selectedItemsOnly) {
        items(state.selectedPaths.sorted(), key = { it }) { path ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onPathSelectionChange(path, false) }, enabled = !state.saving) {
                    Text(stringResource(R.string.remove))
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.folderEntries(
    state: ScanSelectionUiState,
    location: StorageBrowserLocation,
    onOpenDirectory: (String) -> Unit,
    onShowStorage: () -> Unit,
    onPathSelectionChange: (String, Boolean) -> Unit,
) {
    item {
        TextButton(
            onClick = onShowStorage,
            enabled = !state.saving,
        ) { Text(stringResource(R.string.storage)) }
        Text(
            location.currentPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionPathRow(
            state,
            location.currentPath,
            stringResource(R.string.select_this_folder),
            true,
            onOpen = null,
            onPathSelectionChange = onPathSelectionChange,
        )
    }
    items(location.entries, key = { it.canonicalPath }) { entry ->
        SelectionPathRow(
            state,
            entry.canonicalPath,
            entry.displayName,
            entry.isDirectory,
            onOpen = if (entry.isDirectory) ({ onOpenDirectory(entry.canonicalPath) }) else null,
            onPathSelectionChange = onPathSelectionChange,
        )
    }
    if (location.entries.isEmpty() && !state.loading) {
        item { Text(stringResource(R.string.empty_folder), style = MaterialTheme.typography.bodyMedium) }
    }
}
