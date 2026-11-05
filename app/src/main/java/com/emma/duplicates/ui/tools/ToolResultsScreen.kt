package com.emma.duplicates.ui.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.emma.duplicates.R
import com.emma.duplicates.core.database.ScanFailureReasons
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.designsystem.CleanupRowTitle
import com.emma.duplicates.core.designsystem.CleanupTitle
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.designsystem.cleanupCheckboxColors
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.ui.components.ConfirmationDialog
import com.emma.duplicates.ui.components.DetailHeader
import com.emma.duplicates.ui.components.MediaThumbnail
import com.emma.duplicates.ui.components.rememberVideoThumbnailImageLoader
import com.emma.duplicates.ui.model.UiFileCategory
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ToolResultsScreen(
    state: ToolScreenState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onGrantAccess: () -> Unit,
    onControls: (ToolControls) -> Unit,
    onPreview: (String) -> Unit,
    onRequestCleanup: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onDismissOutcome: () -> Unit,
    onSelectVisible: (Set<String>, Boolean) -> Unit,
    onKeep: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val emptyMessage =
        if (state.tool == CleanupTool.EMPTY_FOLDERS) R.string.tool_no_folders else R.string.tool_no_results
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        val footerInList = maxHeight < 360.dp
        val columns = if (maxWidth < 360.dp || LocalDensity.current.fontScale >= LARGE_FONT_SCALE) 1 else 2
        val stackTitle = maxWidth < 360.dp && LocalDensity.current.fontScale >= LARGE_FONT_SCALE
        val visible = state.visibleItems
        val listState = rememberLazyListState()
        LaunchedEffect(state.controls.group) { listState.scrollToItem(0) }
        Column(Modifier.fillMaxSize()) {
            ToolHeader(state, onBack, onRefresh, stackTitle)
            LazyColumn(
                Modifier.weight(1f).testTag("tool-results"),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (state.showRunStatus) {
                    item { Column(Modifier.padding(bottom = 12.dp)) { ToolRunStatus(state, onStop, onGrantAccess) } }
                }
                if (state.hasAccess) {
                    item { ToolFilters(state, onControls) }
                    if (visible.isNotEmpty()) {
                        item { ToolSelectionControls(state, onSelectVisible) }
                    }
                    if (visible.isEmpty() && !state.running) {
                        item {
                            Text(stringResource(emptyMessage))
                        }
                    }
                    toolItems(state, columns, onPreview, onSelectVisible, onKeep)
                }
                if (footerInList) {
                    item {
                        if (state.tool == CleanupTool.SIMILAR_PHOTOS) SimilarGroupFilter(state, onControls)
                        ToolFooter(state, onRequestCleanup)
                    }
                }
            }
            if (!footerInList) {
                if (state.tool == CleanupTool.SIMILAR_PHOTOS) SimilarGroupFilter(state, onControls)
                ToolFooter(state, onRequestCleanup)
            }
        }
    }
    ToolDialogs(state, onConfirm, onDismissConfirmation, onDismissOutcome)
}

private fun androidx.compose.foundation.lazy.LazyListScope.toolItems(
    state: ToolScreenState,
    columns: Int,
    onPreview: (String) -> Unit,
    onSelectVisible: (Set<String>, Boolean) -> Unit,
    onKeep: (String) -> Unit,
) {
    val visible = state.visibleItems
    if (state.tool == CleanupTool.SIMILAR_PHOTOS || state.tool == CleanupTool.SCREENSHOTS) {
        toolImageItems(state, columns, onPreview, onSelectVisible, onKeep)
    } else {
        itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
            ToolFileRow(
                item,
                state.tool,
                relativeFolder(item, state),
                state.busy || state.running,
                { onSelectVisible(setOf(item.id), it) },
                { onPreview(item.id) },
                first = index == 0,
                last = index == visible.lastIndex,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.toolImageItems(
    state: ToolScreenState,
    columns: Int,
    onPreview: (String) -> Unit,
    onSelectVisible: (Set<String>, Boolean) -> Unit,
    onKeep: (String) -> Unit,
) {
    val visible = state.visibleItems
    val groups =
        if (state.tool ==
            CleanupTool.SCREENSHOTS
        ) {
            visible.groupBy { monthLabel(it, state) }
        } else {
            mapOf("" to visible)
        }
    groups.forEach { (month, images) ->
        if (state.tool == CleanupTool.SCREENSHOTS) {
            item(key = "month:$month") {
                ScreenshotMonthHeader(month, images, state.busy || state.running, onSelectVisible)
            }
        }
        items(images.chunked(columns), key = { row -> row.first().id }) { row ->
            Row(
                Modifier.height(IntrinsicSize.Min).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { item ->
                    ToolImageCard(
                        item,
                        state.busy || state.running,
                        state.tool == CleanupTool.SCREENSHOTS,
                        { onSelectVisible(setOf(item.id), it) },
                        { onPreview(item.id) },
                        { onKeep(item.id) },
                        Modifier.weight(1f),
                        dateLabel =
                            month.takeIf { it.isNotEmpty() }?.let {
                                DisplayFormatters.shortDate(
                                    item.modifiedAt,
                                    scanZone(state),
                                )
                            },
                    )
                }
                if (row.size < columns) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun ToolRunStatus(
    state: ToolScreenState,
    onStop: () -> Unit,
    onGrantAccess: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.hasAccess) {
            Text(stringResource(R.string.tool_access_required))
            PrimaryActionButton(onGrantAccess) { Text(stringResource(R.string.tool_grant_access)) }
        } else if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        if (state.latest?.phase ==
                            ScanPhases.COMPARING_CANDIDATES
                        ) {
                            R.string.tool_comparing
                        } else {
                            R.string.tool_scanning
                        },
                    ),
                    Modifier.weight(1f),
                )
                TextButton(onStop) { Text(stringResource(R.string.cancel)) }
            }
        } else {
            when (state.latest?.status) {
                ScanStatuses.CANCELED -> {
                    Text(stringResource(R.string.scan_canceled))
                }

                ScanStatuses.FAILED -> {
                    Text(
                        stringResource(
                            when (state.latest.failureReason) {
                                ScanFailureReasons.PERMISSION_REVOKED -> R.string.tool_access_required
                                ScanFailureReasons.STORAGE_VOLUME_UNAVAILABLE -> R.string.tool_scope_unavailable
                                else -> R.string.tool_failed
                            },
                        ),
                    )
                }
            }
            if (state.results?.session?.complete == false) Text(stringResource(R.string.tool_partial))
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.notice?.let { Text(stringResource(it)) }
    }
}

@Composable
private fun ToolFooter(
    state: ToolScreenState,
    onCleanup: () -> Unit,
) {
    if (!state.hasAccess || state.selected.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().testTag("tool-footer").padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HorizontalDivider()
            SelectionSummary(state)
            CleanupButton(state, onCleanup, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SelectionSummary(
    state: ToolScreenState,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                pluralStringResource(R.plurals.tool_selected_count, state.selected.size, state.selected.size),
                style = CleanupRowTitle,
            )
            if (state.tool != CleanupTool.EMPTY_FOLDERS) {
                Text(
                    DisplayFormatters.size(LocalResources.current, state.selected.sumOf { it.sizeBytes }),
                    style = CleanupRowTitle,
                )
            }
        }
        if (state.hiddenCount > 0) {
            Text(
                pluralStringResource(R.plurals.tool_hidden_selected, state.hiddenCount, state.hiddenCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CleanupButton(
    state: ToolScreenState,
    onCleanup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryActionButton(
        onCleanup,
        modifier.testTag("tool-cleanup"),
        state.selected.isNotEmpty() && !state.busy && !state.running,
        compact = true,
    ) {
        Text(
            if (state.tool == CleanupTool.EMPTY_FOLDERS) {
                pluralStringResource(R.plurals.tool_delete_folder_count, state.selected.size, state.selected.size)
            } else {
                stringResource(R.string.tool_move)
            },
        )
    }
}

@Composable
private fun ToolDialogs(
    state: ToolScreenState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onDismissOutcome: () -> Unit,
) {
    val resources = LocalResources.current
    state.fileConfirmation?.let { batch ->
        ConfirmationDialog(
            stringResource(R.string.tool_move_title),
            androidx.compose.ui.res.pluralStringResource(
                R.plurals.tool_confirm_move,
                batch.files.size,
                batch.files.size,
                DisplayFormatters.size(resources, batch.selectedBytes),
            ) +
                if (batch.hiddenCount >
                    0
                ) {
                    "\n" + pluralStringResource(R.plurals.tool_hidden_selected, batch.hiddenCount, batch.hiddenCount)
                } else {
                    ""
                },
            stringResource(R.string.tool_move),
            onDismiss,
            onConfirm,
            true,
        )
    }
    state.folderConfirmation?.let { batch ->
        ConfirmationDialog(
            stringResource(R.string.tool_delete_folders_title),
            androidx.compose.ui.res.pluralStringResource(
                R.plurals.tool_delete_folders_confirmation,
                batch.selected.size,
                batch.selected.size,
            ),
            stringResource(R.string.tool_delete_folders),
            onDismiss,
            onConfirm,
            true,
        )
    }
    state.outcome?.let { result ->
        val message =
            if (result.directories) {
                stringResource(R.string.tool_folder_outcome, result.completed, result.failed)
            } else {
                stringResource(
                    R.string.tool_move_outcome,
                    result.completed,
                    result.failed,
                )
            }
        androidx.compose.material3.AlertDialog(onDismissRequest = onDismissOutcome, text = {
            Text(message)
        }, confirmButton = { TextButton(onDismissOutcome) { Text(stringResource(android.R.string.ok)) } })
    }
}

internal const val LARGE_FONT_SCALE = 1.5f

@Composable
private fun ToolHeader(
    state: ToolScreenState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    stackTitle: Boolean,
) {
    DetailHeader(
        state.tool.titleResource(),
        onBack,
        Modifier.padding(horizontal = 16.dp).heightIn(min = 56.dp),
        titleStyle = CleanupTitle,
        stackTitle = stackTitle,
    ) {
        IconButton(onClick = onRefresh, enabled = !state.busy && !state.running && state.hasAccess) {
            Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
        }
    }
}

private val ToolScreenState.showRunStatus: Boolean
    get() {
        val activeOrUnavailable = !hasAccess || running || busy
        val interrupted = latest?.status in listOf(ScanStatuses.FAILED, ScanStatuses.CANCELED)
        val incomplete = notice != null || results?.session?.complete == false
        return activeOrUnavailable || interrupted || incomplete
    }

@Composable
private fun ScreenshotMonthHeader(
    month: String,
    images: List<ToolItemEntity>,
    busy: Boolean,
    onSelectVisible: (Set<String>, Boolean) -> Unit,
) {
    val label = month.ifEmpty { stringResource(R.string.tool_unknown_date) }
    val eligible = images.filter { !it.keeper }
    val selected = eligible.count { it.selected }
    val selectionState =
        when {
            selected == 0 -> ToggleableState.Off
            selected == eligible.size -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
    val description = stringResource(R.string.tool_select_item, label)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f).semantics { heading() }, style = CleanupRowTitle)
            TriStateCheckbox(
                selectionState,
                {
                    onSelectVisible(
                        eligible.mapTo(HashSet()) { it.id },
                        selectionState != ToggleableState.On,
                    )
                },
                Modifier.size(48.dp).semantics { contentDescription = description },
                enabled = !busy && eligible.isNotEmpty(),
                colors = cleanupCheckboxColors(),
            )
        }
    }
}
