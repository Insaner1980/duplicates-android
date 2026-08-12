package com.emma.duplicates.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.FreeStorage
import com.emma.duplicates.core.designsystem.OtherUsedStorage
import com.emma.duplicates.core.designsystem.Primary
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.core.format.StorageLabels
import com.emma.duplicates.core.format.StorageSegments
import com.emma.duplicates.ui.components.CategoryIcon
import com.emma.duplicates.ui.components.labelRes
import com.emma.duplicates.ui.model.UiFileCategory
import com.emma.duplicates.ui.permission.PermissionScreen
import java.text.NumberFormat
import java.util.Locale

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

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenSettings: () -> Unit,
    onGrantAccess: () -> Unit,
    onStartScan: () -> Unit,
    onViewScan: () -> Unit,
    onReviewDuplicates: () -> Unit,
    onCategorySelected: (UiFileCategory) -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        HomeUiState.PermissionRequired ->
            PermissionScreen(
                onGrantAccess = onGrantAccess,
                onOpenSettings = onOpenSettings,
                modifier = modifier,
            )

        is HomeUiState.Ready ->
            ReadyHomeScreen(
                locationSummary = state.locationSummary,
                typeSummary = state.typeSummary,
                scanEnabled = state.scanEnabled,
                onStartScan = onStartScan,
                onOpenSettings = onOpenSettings,
                modifier = modifier,
            )

        is HomeUiState.ActiveScan ->
            HomeScrollableContent(modifier) {
                HomeHeader(onOpenSettings)
                Spacer(Modifier.height(32.dp))
                ActiveScanCard(state, onViewScan)
            }

        is HomeUiState.Completed ->
            HomeScrollableContent(modifier) {
                HomeHeader(onOpenSettings)
                Spacer(Modifier.height(32.dp))
                CompletedSummaryCard(state.summary)
                Spacer(Modifier.height(20.dp))
                PrimaryActionButton(
                    onClick = onReviewDuplicates,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.review_duplicates))
                }
                Spacer(Modifier.height(32.dp))
                Text(
                    text = stringResource(R.string.duplicates_by_type),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(16.dp))
                CategoryGrid(state.summary.categories, onCategorySelected)
                Spacer(Modifier.height(32.dp))
                LastScanCard(
                    timestamp = state.summary.lastScanEpochMillis,
                    duplicateCount = state.summary.duplicateFileCount,
                    reclaimableBytes = state.summary.reclaimableBytes,
                    skippedFileCount = state.summary.skippedFileCount,
                    onScanAgain = onScanAgain,
                )
            }

        is HomeUiState.NoDuplicates ->
            EmptyScanHome(
                title = stringResource(R.string.no_duplicates_found),
                explanation = stringResource(R.string.no_duplicates_explanation),
                timestamp = state.lastScanEpochMillis,
                skippedFileCount = state.skippedFileCount,
                onOpenSettings = onOpenSettings,
                onScanAgain = onScanAgain,
                modifier = modifier,
            )

        is HomeUiState.NoMatchingFiles ->
            EmptyScanHome(
                title = stringResource(R.string.no_matching_files),
                explanation = stringResource(R.string.no_matching_files_explanation),
                timestamp = state.lastScanEpochMillis,
                skippedFileCount = state.skippedFileCount,
                onOpenSettings = onOpenSettings,
                onScanAgain = onScanAgain,
                modifier = modifier,
            )
    }
}

@Composable
private fun EmptyScanHome(
    title: String,
    explanation: String,
    timestamp: Long,
    skippedFileCount: Int,
    onOpenSettings: () -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier,
) {
    HomeScrollableContent(modifier) {
        HomeHeader(onOpenSettings)
        Spacer(Modifier.height(32.dp))
        DuplicatesCard(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = DisplayFormatters.dateTime(LocalResources.current, timestamp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (skippedFileCount > 0) {
                    Text(
                        text = stringResource(R.string.scan_skipped_files),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrimaryActionButton(
                    onClick = onScanAgain,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.scan_again))
                }
            }
        }
    }
}

@Composable
private fun HomeScrollableContent(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
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
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding, vertical = 24.dp)
                    .padding(bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayLarge,
            modifier =
                Modifier
                    .weight(1f)
                    .semantics { heading() },
        )
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.settings),
            )
        }
    }
}

@Composable
private fun ActiveScanCard(
    state: HomeUiState.ActiveScan,
    onViewScan: () -> Unit,
) {
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.scan_in_progress),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(state.phase, style = MaterialTheme.typography.bodyLarge)
            if (state.progress != null) {
                val safeProgress = state.progress.coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { safeProgress },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics {
                                progressBarRangeInfo =
                                    androidx.compose.ui.semantics.ProgressBarRangeInfo(
                                        safeProgress,
                                        0f..1f,
                                    )
                            },
                )
                state.progressLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text =
                    stringResource(
                        R.string.files_indexed_value,
                        englishInteger(state.filesIndexed),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onViewScan,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(stringResource(R.string.view_scan))
            }
        }
    }
}

@Composable
private fun CompletedSummaryCard(summary: CompletedHomeUiState) {
    val storage =
        StorageSegments.calculate(
            totalBytes = summary.totalStorageBytes,
            freeBytes = summary.freeStorageBytes,
            reclaimableBytes = summary.reclaimableBytes,
        )
    val storageLabels = DisplayFormatters.storageLabels(LocalResources.current, storage)
    val chartDescription =
        stringResource(
            R.string.storage_chart_description,
            storageLabels.total,
            storageLabels.reclaimable,
            storageLabels.free,
        )
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(R.string.space_you_can_reclaim),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = DisplayFormatters.size(LocalResources.current, summary.reclaimableBytes),
                style = MaterialTheme.typography.displayLarge,
                color = Primary,
            )
            Text(
                text = stringResource(R.string.duplicate_files_value, englishInteger(summary.duplicateFileCount)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.duplicate_groups_value, englishInteger(summary.duplicateGroupCount)),
                style = MaterialTheme.typography.bodyLarge,
            )
            StorageRing(storage, storageLabels.total, chartDescription)
            StorageLegend(storageLabels)
        }
    }
}

@Composable
private fun StorageRing(
    storage: StorageSegments,
    totalLabel: String,
    spokenDescription: String,
) {
    val total = storage.totalBytes.coerceAtLeast(1L).toFloat()
    val stroke = 18.dp
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clearAndSetSemantics { contentDescription = spokenDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(190.dp)) {
            val strokeWidth = stroke.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            var start = -90f
            listOf(
                OtherUsedStorage to storage.otherUsedBytes,
                Primary to storage.reclaimableBytes,
                FreeStorage to storage.freeBytes,
            ).forEach { (color, bytes) ->
                val sweep = bytes.toFloat() / total * 360f
                if (sweep > 0f) {
                    drawArc(
                        color = color,
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(strokeWidth, cap = StrokeCap.Butt),
                    )
                }
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = totalLabel,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.total_storage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StorageLegend(labels: StorageLabels) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LegendRow(stringResource(R.string.other_used_storage), labels.otherUsed)
        LegendRow(stringResource(R.string.reclaimable_storage), labels.reclaimable)
        LegendRow(stringResource(R.string.free_storage), labels.free)
    }
}

@Composable
private fun LegendRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun CategoryGrid(
    categories: List<HomeCategorySummaryUiState>,
    onCategorySelected: (UiFileCategory) -> Unit,
) {
    BoxWithConstraints {
        val stack = maxWidth < 400.dp || LocalConfiguration.current.fontScale >= 1.5f
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            categories.chunked(if (stack) 1 else 2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    rowItems.forEach { category ->
                        DuplicatesCard(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .clickable { onCategorySelected(category.category) }
                                    .semantics(mergeDescendants = true) {},
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CategoryIcon(category.category)
                                Text(
                                    text = stringResource(category.category.labelRes()),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.duplicates_value, englishInteger(category.duplicateCount)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text =
                                        DisplayFormatters.size(
                                            LocalResources.current,
                                            category.reclaimableBytes,
                                        ),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                    if (!stack && rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LastScanCard(
    timestamp: Long,
    duplicateCount: Int,
    reclaimableBytes: Long,
    skippedFileCount: Int,
    onScanAgain: () -> Unit,
) {
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.last_scan),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                DisplayFormatters.dateTime(LocalResources.current, timestamp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text =
                    stringResource(
                        R.string.last_scan_summary,
                        englishInteger(duplicateCount),
                        DisplayFormatters.size(LocalResources.current, reclaimableBytes),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (skippedFileCount > 0) {
                Text(
                    text = stringResource(R.string.scan_skipped_files),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onScanAgain,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(stringResource(R.string.scan_again))
            }
        }
    }
}

private fun englishInteger(value: Int): String = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(value)
