package com.emma.duplicates.ui.results

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.OnPrimaryContainer
import com.emma.duplicates.core.designsystem.Primary
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.designsystem.PrimaryContainer
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.ui.components.ConfirmationDialog
import com.emma.duplicates.ui.components.Metric
import com.emma.duplicates.ui.components.rememberVideoThumbnailImageLoader
import com.emma.duplicates.ui.components.screenListPadding
import com.emma.duplicates.ui.model.UiFileCategory
import java.text.NumberFormat
import java.util.Locale

enum class ResultsFilter {
    ALL,
    PHOTOS,
    VIDEOS,
    AUDIO,
    DOCUMENTS,
}

enum class ResultsSort {
    LARGEST_RECLAIMABLE,
    MOST_COPIES,
    FILE_NAME,
    NEWEST,
    OLDEST,
}

data class DuplicateGroupCardUiState(
    val id: String,
    val title: String,
    val category: UiFileCategory,
    val copyCount: Int,
    val reclaimableBytes: Long,
    val paths: List<String>,
    val thumbnailModel: Any?,
    val audioContentUri: String? = null,
    val previewFileId: String? = null,
)

data class ResultsUiState(
    val hasCompletedScan: Boolean,
    val duplicateFileCount: Int,
    val duplicateGroupCount: Int,
    val reclaimableBytes: Long,
    val selectedFilter: ResultsFilter,
    val selectedSort: ResultsSort,
    val searchVisible: Boolean,
    val searchQuery: String,
    val clearConfirmationVisible: Boolean,
    val groups: List<DuplicateGroupCardUiState>,
    val overflowVisible: Boolean = false,
    val sortVisible: Boolean = false,
)

@Composable
fun ResultsScreen(
    state: ResultsUiState,
    onSearchVisibilityChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFilterSelect: (ResultsFilter) -> Unit,
    onSortSelect: (ResultsSort) -> Unit,
    onOverflowVisibilityChange: (Boolean) -> Unit,
    onSortVisibilityChange: (Boolean) -> Unit,
    onPreviewFile: (String, String) -> Unit,
    onReviewGroup: (String) -> Unit,
    onScanAgain: () -> Unit,
    onRequestClear: () -> Unit,
    onDismissClear: () -> Unit,
    onConfirmClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding(),
    ) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 24.dp
        val listState = rememberLazyListState()
        val visibleGroupIds =
            remember(listState) {
                derivedStateOf {
                    listState.layoutInfo.visibleItemsInfo.mapNotNullTo(mutableSetOf()) { it.key as? String }
                }
            }
        val videoImageLoader = rememberVideoThumbnailImageLoader()
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("results-list"),
            state = listState,
            contentPadding = screenListPadding(horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                ResultsHeader(
                    state = state,
                    onSearchVisibilityChange = onSearchVisibilityChange,
                    onOverflowVisibilityChange = onOverflowVisibilityChange,
                    onSortVisibilityChange = onSortVisibilityChange,
                    onSortSelect = onSortSelect,
                    onRequestClear = onRequestClear,
                )
            }
            if (!state.hasCompletedScan) {
                item { NoSavedResults(onScanAgain) }
            } else {
                item { ResultsSummary(state) }
                if (state.searchVisible) {
                    item {
                        ResultsSearchField(state.searchQuery, onSearchQueryChange)
                    }
                }
                item { FilterRow(state.selectedFilter, onFilterSelect) }
                duplicateGroups(state.groups, visibleGroupIds, videoImageLoader, onPreviewFile, onReviewGroup)
            }
        }
    }

    ClearResultsConfirmation(state.clearConfirmationVisible, onDismissClear, onConfirmClear)
}

@Composable
private fun ResultsHeader(
    state: ResultsUiState,
    onSearchVisibilityChange: (Boolean) -> Unit,
    onOverflowVisibilityChange: (Boolean) -> Unit,
    onSortVisibilityChange: (Boolean) -> Unit,
    onSortSelect: (ResultsSort) -> Unit,
    onRequestClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.results),
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconButton(
            onClick = { onSearchVisibilityChange(!state.searchVisible) },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription =
                    stringResource(
                        if (state.searchVisible) R.string.hide_search else R.string.show_search,
                    ),
            )
        }
        ResultsOverflowMenu(state, onOverflowVisibilityChange, onSortVisibilityChange, onSortSelect, onRequestClear)
    }
}

@Composable
private fun ResultsSummary(state: ResultsUiState) {
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.scan_results),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Metric(englishInteger(state.duplicateFileCount), stringResource(R.string.duplicate_files))
            Metric(englishInteger(state.duplicateGroupCount), stringResource(R.string.duplicate_groups))
            Metric(
                DisplayFormatters.size(LocalResources.current, state.reclaimableBytes),
                stringResource(R.string.reclaimable),
                emphasized = true,
            )
        }
    }
}

@Composable
private fun FilterRow(
    selected: ResultsFilter,
    onSelect: (ResultsFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ResultsFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(filter.labelRes())) },
                leadingIcon =
                    if (filter == selected) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                            )
                        }
                    } else {
                        null
                    },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryContainer,
                        selectedLabelColor = OnPrimaryContainer,
                        selectedLeadingIconColor = Primary,
                    ),
                modifier = Modifier.height(48.dp),
            )
        }
    }
}

@Composable
private fun NoSavedResults(onScanAgain: () -> Unit) {
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.no_saved_scan_results),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.no_saved_scan_results_message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryActionButton(
                onClick = onScanAgain,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.start_scan))
            }
        }
    }
}

private fun englishInteger(value: Int): String = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(value)

@Composable
private fun ResultsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.search_duplicates)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
            )
        },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.duplicateGroups(
    groups: List<DuplicateGroupCardUiState>,
    visibleGroupIds: State<Set<String>>,
    videoImageLoader: ImageLoader,
    onPreviewFile: (String, String) -> Unit,
    onReviewGroup: (String) -> Unit,
) {
    if (groups.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.no_results_for_filter),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items(groups, key = { it.id }) { group ->
            DuplicateGroupCard(
                group = group,
                onPreviewFile = onPreviewFile,
                onReviewGroup = onReviewGroup,
                videoImageLoader = videoImageLoader,
                audioArtworkVisible = group.id in visibleGroupIds.value,
            )
        }
    }
}

@Composable
private fun ResultsOverflowMenu(
    state: ResultsUiState,
    onOverflowVisibilityChange: (Boolean) -> Unit,
    onSortVisibilityChange: (Boolean) -> Unit,
    onSortSelect: (ResultsSort) -> Unit,
    onRequestClear: () -> Unit,
) {
    Box {
        IconButton(
            onClick = { onOverflowVisibilityChange(true) },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.more_options),
            )
        }
        DropdownMenu(
            expanded = state.overflowVisible,
            onDismissRequest = { onOverflowVisibilityChange(false) },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort)) },
                onClick = {
                    onOverflowVisibilityChange(false)
                    onSortVisibilityChange(true)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.clear_scan_results)) },
                onClick = {
                    onOverflowVisibilityChange(false)
                    onRequestClear()
                },
            )
        }
        DropdownMenu(
            expanded = state.sortVisible,
            onDismissRequest = { onSortVisibilityChange(false) },
        ) {
            ResultsSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sort.labelRes())) },
                    onClick = {
                        onSortVisibilityChange(false)
                        onSortSelect(sort)
                    },
                )
            }
        }
    }
}

@Composable
private fun ClearResultsConfirmation(
    visible: Boolean,
    onDismissClear: () -> Unit,
    onConfirmClear: () -> Unit,
) {
    if (visible) {
        ConfirmationDialog(
            title = stringResource(R.string.clear_scan_results_title),
            message = stringResource(R.string.clear_scan_results_message),
            confirmLabel = stringResource(R.string.clear_results),
            onDismiss = onDismissClear,
            onConfirm = onConfirmClear,
            destructive = true,
        )
    }
}
