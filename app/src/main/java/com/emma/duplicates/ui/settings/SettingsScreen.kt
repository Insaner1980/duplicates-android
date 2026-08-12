package com.emma.duplicates.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.Primary
import com.emma.duplicates.ui.components.DetailHeader

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

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenScanLocations: () -> Unit,
    onScanHiddenFoldersChange: (Boolean) -> Unit,
    onIgnoreSystemFoldersChange: (Boolean) -> Unit,
    onAutoSelectChange: (Boolean) -> Unit,
    onConfirmBeforeDeleteChange: (Boolean) -> Unit,
    onOpenExclusions: () -> Unit,
    onOpenFileTypes: () -> Unit,
    onOpenStoragePermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding(),
    ) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 24.dp
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            DetailHeader(titleRes = R.string.settings, onBack = onBack)
            SettingsSection(R.string.scanning_section) {
                DetailSettingsRow(
                    title = stringResource(R.string.scan_locations),
                    summary = state.scanLocationsSummary,
                    onClick = onOpenScanLocations,
                )
                ToggleSettingsRow(
                    title = stringResource(R.string.scan_hidden_folders),
                    checked = state.scanHiddenFolders,
                    onCheckedChange = onScanHiddenFoldersChange,
                )
                ToggleSettingsRow(
                    title = stringResource(R.string.ignore_system_folders),
                    checked = state.ignoreSystemFolders,
                    onCheckedChange = onIgnoreSystemFoldersChange,
                )
            }
            SettingsSection(R.string.review_and_deletion) {
                ToggleSettingsRow(
                    title = stringResource(R.string.auto_select_duplicate_copies),
                    checked = state.autoSelectDuplicateCopies,
                    onCheckedChange = onAutoSelectChange,
                )
                ToggleSettingsRow(
                    title = stringResource(R.string.confirm_before_delete),
                    checked = state.confirmBeforeDelete,
                    onCheckedChange = onConfirmBeforeDeleteChange,
                )
                DetailSettingsRow(
                    title = stringResource(R.string.exclusions),
                    summary = stringResource(R.string.scans_skip_excluded_items),
                    onClick = onOpenExclusions,
                )
            }
            SettingsSection(R.string.file_types) {
                DetailSettingsRow(
                    title = stringResource(R.string.types_to_scan),
                    summary = state.fileTypesSummary,
                    onClick = onOpenFileTypes,
                )
            }
            SettingsSection(R.string.about) {
                DetailSettingsRow(
                    title = stringResource(R.string.storage_permission),
                    summary =
                        stringResource(
                            if (state.storagePermissionGranted) R.string.granted else R.string.required,
                        ),
                    onClick = onOpenStoragePermission,
                    emphasized = !state.storagePermissionGranted,
                )
                StaticSettingsRow(
                    title = stringResource(R.string.app_version),
                    value = state.appVersion,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    titleRes: Int,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        DuplicatesCard(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun DetailSettingsRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (emphasized) Primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleSettingsRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun StaticSettingsRow(
    title: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
