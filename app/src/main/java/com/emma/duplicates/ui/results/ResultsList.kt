package com.emma.duplicates.ui.results

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.ui.components.ConfirmationDialog
import com.emma.duplicates.ui.components.DetailHeader
import com.emma.duplicates.ui.components.LARGE_FONT_SCALE
import com.emma.duplicates.ui.components.rememberVideoThumbnailImageLoader

@Composable
internal fun ResultsList(
    state: ResultsUiState,
    onSearchQueryChange: (String) -> Unit,
    onFilterSelect: (ResultsFilter) -> Unit,
    onSortSelect: (ResultsSort) -> Unit,
    onSortVisibilityChange: (Boolean) -> Unit,
    onPreviewFile: (String, String) -> Unit,
    onMemberSelectionChange: (String, Boolean) -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    key(state.sessionId) {
        val listState = rememberLazyListState()
        val imageLoader = rememberVideoThumbnailImageLoader()
        LazyColumn(
            state = listState,
            modifier = modifier.testTag("results-list"),
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                ),
        ) {
            if (!state.hasCompletedScan) {
                item { ResultsEmpty(R.string.no_saved_scan_results, onScanAgain, !state.busy) }
            } else {
                item { ResultsSummary(state) }
                if (state.searchVisible) {
                    item {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            label = { Text(stringResource(R.string.search_duplicates)) },
                            singleLine = true,
                        )
                    }
                }
                item { FilterRow(state.selectedFilter, onFilterSelect) }
                item { ResultsSortMenu(state, onSortSelect, onSortVisibilityChange) }
                if (state.groups.isEmpty()) {
                    item {
                        ResultsEmpty(
                            when {
                                state.duplicateGroupCount > 0 -> R.string.no_results_for_filter
                                state.scannedFileCount == 0 -> R.string.no_matching_files
                                else -> R.string.no_duplicates_found
                            },
                            onScanAgain,
                            !state.busy && state.duplicateGroupCount == 0,
                        )
                    }
                }
                duplicateGroups(state, imageLoader, onPreviewFile, onMemberSelectionChange)
            }
        }
    }
}

@Composable
private fun ResultsSummary(state: ResultsUiState) {
    val formatted = DisplayFormatters.size(LocalResources.current, state.availableBytes)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).testTag("results-summary"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.available_to_free), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            buildAnnotatedString {
                withStyle(
                    SpanStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold),
                ) { append(formatted.substringBefore(' ')) }
                withStyle(SpanStyle(fontSize = 20.sp)) {
                    append(" ")
                    append(formatted.substringAfter(' '))
                }
            },
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.semantics { contentDescription = formatted },
        )
        if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) {
            SummaryMetric(state.duplicateGroupCount, R.string.duplicate_groups)
            SummaryMetric(state.duplicateFileCount, R.string.files_found)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SummaryMetric(state.duplicateGroupCount, R.string.duplicate_groups, Modifier.weight(1f))
                SummaryMetric(state.duplicateFileCount, R.string.files_found, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    value: Int,
    label: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(value.toString(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilterRow(
    selected: ResultsFilter,
    onSelect: (ResultsFilter) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ResultsFilter.entries.forEach { filter ->
            FilterChip(
                selected == filter,
                { onSelect(filter) },
                label = { Text(stringResource(filter.labelRes())) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(28.dp),
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            )
        }
    }
}

@Composable
private fun ResultsEmpty(
    message: Int,
    onScan: () -> Unit,
    showScan: Boolean,
) {
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(message))
        if (showScan) PrimaryActionButton(onScan, Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_files)) }
    }
}

@Composable
private fun ResultsSortMenu(
    state: ResultsUiState,
    onSortSelect: (ResultsSort) -> Unit,
    onSortVisibilityChange: (Boolean) -> Unit,
) {
    Box {
        TextButton(onClick = { onSortVisibilityChange(true) }) {
            Text(
                stringResource(R.string.sort_by, stringResource(state.selectedSort.labelRes())),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(state.sortVisible, { onSortVisibilityChange(false) }) {
            ResultsSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sort.labelRes())) },
                    onClick = { onSortSelect(sort) },
                )
            }
        }
    }
}

private fun LazyListScope.duplicateGroups(
    state: ResultsUiState,
    imageLoader: ImageLoader,
    onPreviewFile: (String, String) -> Unit,
    onMemberSelectionChange: (String, Boolean) -> Unit,
) {
    state.groups.forEach { group ->
        item(
            key = "header-${group.id}",
            contentType = "group-header",
        ) { DuplicateGroupHeader(group) }
        itemsIndexed(group.members, key = {
            _,
            member,
            ->
            "member-${member.memberId}"
        }, contentType = {
            _,
            _,
            ->
            "member"
        }) { index, member ->
            DuplicateMemberRow(
                group.id,
                member,
                index == group.members.lastIndex,
                !state.busy && !state.deleteConfirmationVisible,
                imageLoader,
                onPreviewFile,
                onMemberSelectionChange,
            )
        }
        item(key = "gap-${group.id}", contentType = "gap") { Spacer(Modifier.height(16.dp)) }
    }
}
