package com.emma.duplicates.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.ui.components.CategoryIcon
import com.emma.duplicates.ui.components.DetailHeader
import com.emma.duplicates.ui.components.labelRes
import com.emma.duplicates.ui.components.screenListPadding
import com.emma.duplicates.ui.model.UiFileCategory

data class ScanLocationUiState(
    val id: String,
    val displayName: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val selected: Boolean,
    val mounted: Boolean,
)

data class ScanLocationsUiState(
    val locations: List<ScanLocationUiState>,
)

@Composable
fun ScanLocationsScreen(
    state: ScanLocationsUiState,
    onBack: () -> Unit,
    onLocationSelectionChange: (String, Boolean) -> Unit,
    onSelectionBlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedCount = state.locations.count { it.selected && it.mounted }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 24.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("scan-locations-list"),
            contentPadding = screenListPadding(horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { DetailHeader(titleRes = R.string.scan_locations, onBack = onBack) }
            items(state.locations, key = { it.id }) { location ->
                ScanLocationCard(location) { selected ->
                    if (!selected && selectedCount == 1 && location.selected) {
                        onSelectionBlock()
                    } else {
                        onLocationSelectionChange(location.id, selected)
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.select_at_least_one_location),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

data class TypesToScanUiState(
    val enabled: Map<UiFileCategory, Boolean>,
)

@Composable
fun TypesToScanScreen(
    state: TypesToScanUiState,
    onBack: () -> Unit,
    onCategoryEnabledChange: (UiFileCategory, Boolean) -> Unit,
    onSelectionBlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabledCount = UiFileCategory.entries.count { state.enabled[it] == true }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 24.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("types-list"),
            contentPadding = screenListPadding(horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { DetailHeader(titleRes = R.string.types_to_scan, onBack = onBack) }
            items(UiFileCategory.entries, key = { it.name }) { category ->
                val checked = state.enabled[category] == true
                FileTypeCard(category, checked) { enabled ->
                    if (!enabled && checked && enabledCount == 1) {
                        onSelectionBlock()
                    } else {
                        onCategoryEnabledChange(category, enabled)
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.select_at_least_one_type),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun UiFileCategory.supportingRes(): Int =
    when (this) {
        UiFileCategory.PHOTOS -> R.string.photos_supporting
        UiFileCategory.VIDEOS -> R.string.videos_supporting
        UiFileCategory.AUDIO -> R.string.audio_supporting
        UiFileCategory.DOCUMENTS -> R.string.documents_supporting
    }

@Composable
private fun ScanLocationCard(
    location: ScanLocationUiState,
    onSelectionChange: (Boolean) -> Unit,
) {
    SelectableSettingsCard(
        checked = location.selected,
        enabled = location.mounted,
        onSelectionChange = onSelectionChange,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(location.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text =
                    stringResource(
                        R.string.total_size,
                        DisplayFormatters.size(LocalResources.current, location.totalBytes),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text =
                    if (location.mounted) {
                        stringResource(
                            R.string.free_size,
                            DisplayFormatters.size(LocalResources.current, location.freeBytes),
                        )
                    } else {
                        stringResource(R.string.not_mounted)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileTypeCard(
    category: UiFileCategory,
    checked: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    SelectableSettingsCard(checked = checked, onSelectionChange = onEnabledChange) {
        CategoryIcon(category)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(category.labelRes()),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(category.supportingRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectableSettingsCard(
    checked: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    DuplicatesCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onSelectionChange,
                ).semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}
