package com.emma.duplicates.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.ui.components.CategoryIcon
import com.emma.duplicates.ui.components.HomeHeader
import com.emma.duplicates.ui.components.ScreenColumn
import com.emma.duplicates.ui.components.labelRes
import com.emma.duplicates.ui.model.UiFileCategory
import com.emma.duplicates.ui.permission.PermissionScreen
import java.text.NumberFormat
import java.util.Locale

private const val LARGE_FONT_SCALE = 1.5f

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
    onCategorySelect: (UiFileCategory) -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        HomeUiState.PermissionRequired -> {
            PermissionScreen(
                onGrantAccess = onGrantAccess,
                onOpenSettings = onOpenSettings,
                modifier = modifier,
            )
        }

        is HomeUiState.Ready -> {
            ReadyHomeScreen(
                locationSummary = state.locationSummary,
                typeSummary = state.typeSummary,
                scanEnabled = state.scanEnabled,
                onStartScan = onStartScan,
                onOpenSettings = onOpenSettings,
                modifier = modifier,
            )
        }

        is HomeUiState.ActiveScan -> {
            ActiveScanHome(state, onOpenSettings, onViewScan, modifier)
        }

        is HomeUiState.Completed -> {
            CompletedHomeScreen(
                state.summary,
                onOpenSettings,
                onReviewDuplicates,
                onCategorySelect,
                onScanAgain,
                modifier,
            )
        }

        is HomeUiState.NoDuplicates -> {
            EmptyScanHome(
                title = stringResource(R.string.no_duplicates_found),
                explanation = stringResource(R.string.no_duplicates_explanation),
                timestamp = state.lastScanEpochMillis,
                skippedFileCount = state.skippedFileCount,
                onOpenSettings = onOpenSettings,
                onScanAgain = onScanAgain,
                modifier = modifier,
            )
        }

        is HomeUiState.NoMatchingFiles -> {
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
}

@Composable
private fun EmptyScanHome(
    title: String,
    explanation: String,
    timestamp: Long,
    skippedFileCount: Int,
    onOpenSettings: () -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
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
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ScreenColumn(
        modifier = modifier,
        fillHeight = false,
        bottomPadding = 24.dp,
        content = content,
    )
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
private fun CategoryGrid(
    categories: List<HomeCategorySummaryUiState>,
    onCategorySelect: (UiFileCategory) -> Unit,
) {
    BoxWithConstraints {
        val stack = maxWidth < 400.dp || LocalConfiguration.current.fontScale >= LARGE_FONT_SCALE
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
                                    .clickable { onCategorySelect(category.category) }
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
                                    text =
                                        stringResource(
                                            R.string.duplicates_value,
                                            englishInteger(category.duplicateCount),
                                        ),
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

@Composable
private fun CompletedHomeScreen(
    summary: CompletedHomeUiState,
    onOpenSettings: () -> Unit,
    onReviewDuplicates: () -> Unit,
    onCategorySelect: (UiFileCategory) -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HomeScrollableContent(modifier) {
        HomeHeader(onOpenSettings)
        Spacer(Modifier.height(32.dp))
        CompletedSummaryCard(summary)
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
        CategoryGrid(summary.categories, onCategorySelect)
        Spacer(Modifier.height(32.dp))
        LastScanCard(
            timestamp = summary.lastScanEpochMillis,
            duplicateCount = summary.duplicateFileCount,
            reclaimableBytes = summary.reclaimableBytes,
            skippedFileCount = summary.skippedFileCount,
            onScanAgain = onScanAgain,
        )
    }
}

@Composable
private fun ActiveScanHome(
    state: HomeUiState.ActiveScan,
    onOpenSettings: () -> Unit,
    onViewScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HomeScrollableContent(modifier) {
        HomeHeader(onOpenSettings)
        Spacer(Modifier.height(32.dp))
        ActiveScanCard(state, onViewScan)
    }
}
