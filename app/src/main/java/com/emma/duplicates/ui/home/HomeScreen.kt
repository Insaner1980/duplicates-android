package com.emma.duplicates.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.ui.components.CategoryIcon
import com.emma.duplicates.ui.components.HomeHeader
import com.emma.duplicates.ui.components.LARGE_FONT_SCALE
import com.emma.duplicates.ui.components.ScreenColumn
import com.emma.duplicates.ui.components.labelRes
import com.emma.duplicates.ui.model.UiFileCategory
import com.emma.duplicates.ui.permission.PermissionScreen

sealed interface HomeUiState {
    data object PermissionRequired : HomeUiState

    data class Ready(
        val locationSummary: String,
        val typeSummary: String,
        val scanEnabled: Boolean,
    ) : HomeUiState

    data class ActiveScan(
        val phase: String,
        val progress: Float?,
        val progressLabel: String?,
        val filesIndexed: Int,
    ) : HomeUiState

    data class Completed(
        val summary: CompletedHomeUiState,
    ) : HomeUiState

    data class NoDuplicates(
        val lastScanEpochMillis: Long,
        val skippedFileCount: Int = 0,
    ) : HomeUiState

    data class NoMatchingFiles(
        val lastScanEpochMillis: Long,
        val skippedFileCount: Int = 0,
    ) : HomeUiState
}

data class CompletedHomeUiState(
    val reclaimableBytes: Long,
    val duplicateFileCount: Int,
    val duplicateGroupCount: Int,
    val totalStorageBytes: Long,
    val freeStorageBytes: Long,
    val lastScanEpochMillis: Long,
    val categories: List<HomeCategorySummaryUiState>,
    val skippedFileCount: Int = 0,
)

data class HomeCategorySummaryUiState(
    val category: UiFileCategory,
    val duplicateCount: Int,
    val reclaimableBytes: Long,
)

data class HomeConfiguration(
    val locationSummary: String = "",
    val multipleVolumes: Boolean = false,
    val totalBytes: Long? = null,
    val freeBytes: Long = 0L,
    val categories: Map<UiFileCategory, Boolean> = emptyMap(),
    val hasPreviousScan: Boolean = false,
    val previousGroups: Int = 0,
    val previousAvailableBytes: Long = 0L,
    val scanEnabled: Boolean = false,
    val selectedItemsOnly: Boolean = false,
    val selectedItemCount: Int = 0,
)

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenSettings: () -> Unit,
    onGrantAccess: () -> Unit,
    onStartScan: () -> Unit,
    onViewScan: () -> Unit,
    onReviewDuplicates: () -> Unit,
    modifier: Modifier = Modifier,
    configuration: HomeConfiguration = HomeConfiguration(),
    onOpenScanLocations: () -> Unit = {},
    onCategoryEnabledChange: (UiFileCategory, Boolean) -> Unit = { _, _ -> },
    scanStartPending: Boolean = false,
) {
    if (state == HomeUiState.PermissionRequired) {
        PermissionScreen(onGrantAccess, onOpenSettings, modifier)
        return
    }
    val running = state as? HomeUiState.ActiveScan
    val scopeEnabled = running == null && !scanStartPending
    ScreenColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        HomeHeader(onOpenSettings)
        StorageSummaryCard(configuration.totalBytes, configuration.freeBytes, configuration.multipleVolumes)
        ScanLocationSection(configuration, scopeEnabled, onOpenScanLocations)
        FileTypesSection(configuration, scopeEnabled, onCategoryEnabledChange)
        if (running != null) {
            Text(running.phase, style = MaterialTheme.typography.bodyMedium)
            val progress = running.progress
            if (progress == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        PrimaryActionButton(
            onClick = if (running != null) onViewScan else onStartScan,
            enabled = running != null || (configuration.scanEnabled && !scanStartPending),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (running != null) R.string.view_scan else R.string.scan_files))
        }
        if (configuration.hasPreviousScan) PreviousScanCard(configuration, onReviewDuplicates)
    }
}

@Composable
private fun HomeSectionTitle(resource: Int) {
    Text(
        stringResource(resource),
        style = MaterialTheme.typography.titleMedium,
        modifier =
            Modifier.semantics {
                heading()
            },
    )
}

@Composable
private fun FileTypeTile(
    category: UiFileCategory,
    selected: Boolean,
    enabled: Boolean,
    onChange: (UiFileCategory, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    DuplicatesCard(
        modifier.fillMaxWidth().toggleable(
            value = selected,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { onChange(category, it) },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryIcon(category, Modifier.size(24.dp))
            Text(
                stringResource(category.labelRes()),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun ScanLocationSection(
    configuration: HomeConfiguration,
    scopeEnabled: Boolean,
    onOpenScanLocations: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionTitle(R.string.scan_location)
        DuplicatesCard(Modifier.fillMaxWidth().clickable(enabled = scopeEnabled, onClick = onOpenScanLocations)) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(32.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        configuration.locationSummary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (configuration.selectedItemsOnly) {
                            pluralStringResource(
                                R.plurals.scan_items_selected,
                                configuration.selectedItemCount,
                                configuration.selectedItemCount,
                            )
                        } else {
                            stringResource(R.string.all_accessible_folders)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun FileTypesSection(
    configuration: HomeConfiguration,
    scopeEnabled: Boolean,
    onCategoryEnabledChange: (UiFileCategory, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionTitle(R.string.file_types)
        if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) {
            UiFileCategory.entries.forEach { category ->
                FileTypeTile(
                    category,
                    configuration.categories[category] == true,
                    scopeEnabled,
                    onCategoryEnabledChange,
                )
            }
        } else {
            UiFileCategory.entries.chunked(2).forEach { categories ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { category ->
                        FileTypeTile(
                            category,
                            configuration.categories[category] == true,
                            scopeEnabled,
                            onCategoryEnabledChange,
                            Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviousScanCard(
    configuration: HomeConfiguration,
    onReviewDuplicates: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onReviewDuplicates)) {
        Row(
            Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(painterResource(R.drawable.ic_history), null, Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.previous_scan), style = MaterialTheme.typography.bodyLarge)
                Text(
                    pluralStringResource(
                        R.plurals.groups_found,
                        configuration.previousGroups,
                        configuration.previousGroups,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        R.string.available_space,
                        DisplayFormatters.size(LocalResources.current, configuration.previousAvailableBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(24.dp))
        }
    }
}
