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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.ui.components.ConfirmationDialog
import com.emma.duplicates.ui.components.DetailHeader
import com.emma.duplicates.ui.components.LARGE_FONT_SCALE
import com.emma.duplicates.ui.components.rememberVideoThumbnailImageLoader

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
    onScanAgain: () -> Unit,
    onRequestClear: () -> Unit,
    onDismissClear: () -> Unit,
    onConfirmClear: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onMemberSelectionChange: (String, Boolean) -> Unit = { _, _ -> },
    onRequestDelete: () -> Unit = {},
    onDismissDelete: () -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onDismissDeletionResult: () -> Unit = {},
    onShowAll: () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        val footerHeight = maxHeight * MAX_FOOTER_HEIGHT_FRACTION
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            ResultsHeader(state, onBack, onSearchVisibilityChange, onOverflowVisibilityChange, onRequestClear)
            ResultsList(
                state,
                onSearchQueryChange,
                onFilterSelect,
                onSortSelect,
                onSortVisibilityChange,
                onPreviewFile,
                onMemberSelectionChange,
                onScanAgain,
                Modifier.weight(1f),
            )
            if (state.hasCompletedScan) {
                SelectionFooter(state, onRequestDelete, onShowAll, Modifier.heightIn(max = footerHeight))
            }
        }
    }
    ResultsDialogs(state, onDismissClear, onConfirmClear, onDismissDelete, onConfirmDelete, onDismissDeletionResult)
}

@Composable
private fun SelectionFooter(
    state: ResultsUiState,
    onDelete: () -> Unit,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("selection-footer")) {
        HorizontalDivider()
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.files_selected, state.selectedCount, state.selectedCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                DisplayFormatters.size(LocalResources.current, state.selectedBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.hiddenSelectedCount > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.selected_outside_view,
                        state.hiddenSelectedCount,
                        state.hiddenSelectedCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = onShowAll,
                ) { Text(stringResource(R.string.show_all), color = MaterialTheme.colorScheme.onSurface) }
            }
            PrimaryActionButton(
                onClick = onDelete,
                enabled = state.selectedCount > 0 && !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("delete-selected"),
                destructive = true,
            ) {
                Icon(Icons.Outlined.Delete, null)
                Text(
                    stringResource(
                        if (state.busy) R.string.deletion_in_progress else R.string.delete_selected,
                    ),
                    Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ResultsDialogs(
    state: ResultsUiState,
    onDismissClear: () -> Unit,
    onConfirmClear: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissResult: () -> Unit,
) {
    if (state.clearConfirmationVisible) {
        ConfirmationDialog(
            stringResource(R.string.clear_scan_results_title),
            stringResource(R.string.clear_scan_results_message),
            stringResource(R.string.clear_results),
            onDismissClear,
            onConfirmClear,
            true,
        )
    }
    if (state.deleteConfirmationVisible) {
        val message =
            pluralStringResource(
                R.plurals.delete_confirmation_message,
                state.confirmationCount,
                state.confirmationCount,
                DisplayFormatters.size(LocalResources.current, state.confirmationBytes),
            )
        val hidden =
            if (state.confirmationHiddenCount >
                0
            ) {
                "\n\n" +
                    pluralStringResource(
                        R.plurals.selected_outside_view,
                        state.confirmationHiddenCount,
                        state.confirmationHiddenCount,
                    )
            } else {
                ""
            }
        ConfirmationDialog(
            stringResource(R.string.delete_confirmation_title),
            message + hidden,
            stringResource(R.string.delete_permanently),
            onDismissDelete,
            onConfirmDelete,
            true,
        )
    }
    if (state.deletionResult != null || state.messageRes != null) {
        AlertDialog(onDismissRequest = onDismissResult, title = { Text(stringResource(R.string.app_name)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.messageRes?.let { Text(stringResource(it)) }
                state.deletionResult?.let { result ->
                    Text(
                        stringResource(
                            R.string.deletion_outcome,
                            result.deletedFileCount,
                            DisplayFormatters.size(LocalResources.current, result.reclaimedBytes),
                            result.failedFileCount,
                        ),
                    )
                }
            }
        }, confirmButton = { TextButton(onClick = onDismissResult) { Text(stringResource(android.R.string.ok)) } })
    }
}

private const val MAX_FOOTER_HEIGHT_FRACTION = 0.45f

@Composable
private fun ResultsHeader(
    state: ResultsUiState,
    onBack: () -> Unit,
    onSearchVisibilityChange: (Boolean) -> Unit,
    onOverflowVisibilityChange: (Boolean) -> Unit,
    onRequestClear: () -> Unit,
) {
    DetailHeader(R.string.app_name, onBack, Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        IconButton(onClick = { onSearchVisibilityChange(!state.searchVisible) }, enabled = !state.busy) {
            Icon(Icons.Outlined.Search, stringResource(R.string.search_duplicates))
        }
        Box {
            IconButton(onClick = { onOverflowVisibilityChange(true) }, enabled = !state.busy) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_options))
            }
            DropdownMenu(state.overflowVisible, { onOverflowVisibilityChange(false) }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clear_scan_results)) },
                    onClick = onRequestClear,
                    enabled = !state.busy,
                )
            }
        }
    }
}
