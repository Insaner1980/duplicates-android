package com.emma.duplicates.ui.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.emma.duplicates.R
import com.emma.duplicates.core.database.ScanFailureReasons
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.PrimaryActionButton
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
    onScope: () -> Unit,
    onControls: (ToolControls) -> Unit,
    onSelect: (String, Boolean) -> Unit,
    onPreview: (String) -> Unit,
    onRequestCleanup: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onDismissOutcome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        val footerInList = maxHeight < 360.dp
        val columns = if (maxWidth < 360.dp || LocalDensity.current.fontScale >= LARGE_FONT_SCALE) 1 else 2
        val visible = state.visibleItems
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).testTag("tool-results"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    DetailHeader(state.tool.titleResource(), onBack) {
                        IconButton(onClick = onRefresh, enabled = !state.busy && !state.running && state.hasAccess) {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
                        }
                    }
                }
                item { ToolRunStatus(state, onStop, onGrantAccess) }
                if (state.hasAccess) {
                    item { ToolFilters(state, onControls, onScope) }
                    if (visible.isEmpty() && !state.running) item { Text(stringResource(R.string.tool_no_results)) }
                    toolItems(state, columns, onSelect, onPreview)
                }
                if (footerInList) item { ToolFooter(state, onRequestCleanup) }
            }
            if (!footerInList) ToolFooter(state, onRequestCleanup)
        }
    }
    ToolDialogs(state, onConfirm, onDismissConfirmation, onDismissOutcome)
}

private fun androidx.compose.foundation.lazy.LazyListScope.toolItems(
    state: ToolScreenState,
    columns: Int,
    onSelect: (String, Boolean) -> Unit,
    onPreview: (String) -> Unit,
) {
    val visible = state.visibleItems
    if (state.tool == CleanupTool.SIMILAR_PHOTOS || state.tool == CleanupTool.SCREENSHOTS) {
        toolImageItems(state, columns, onSelect, onPreview)
    } else {
        items(visible, key = { it.id }) { item ->
            ToolFileRow(
                item,
                state.tool,
                relativeFolder(item, state),
                state.busy || state.running,
                { onSelect(item.id, it) },
                { onPreview(item.id) },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.toolImageItems(
    state: ToolScreenState,
    columns: Int,
    onSelect: (String, Boolean) -> Unit,
    onPreview: (String) -> Unit,
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
                Text(
                    month.ifEmpty { stringResource(R.string.tool_unknown_date) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        items(images.chunked(columns), key = { row -> row.first().id }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    ToolImageCard(
                        item,
                        state.busy || state.running,
                        state.tool == CleanupTool.SCREENSHOTS,
                        { onSelect(item.id, it) },
                        { onPreview(item.id) },
                        Modifier.weight(1f),
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
            state.results?.session?.skippedCount?.takeIf { it > 0 }?.let {
                Text(
                    stringResource(R.string.tool_skipped, it),
                )
            }
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.tool_prepare))
        }
        state.notice?.let { Text(stringResource(it)) }
    }
}

@Composable
private fun ToolFooter(
    state: ToolScreenState,
    onCleanup: () -> Unit,
) {
    if (!state.hasAccess) return
    Surface(color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val inline =
                maxWidth >= 328.dp && LocalDensity.current.fontScale < LARGE_FONT_SCALE &&
                    state.tool !in setOf(CleanupTool.LARGE_FILES, CleanupTool.SIMILAR_PHOTOS)
            if (inline) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SelectionSummary(state, Modifier.weight(1f))
                    CleanupButton(state, onCleanup)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionSummary(state)
                    CleanupButton(state, onCleanup, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SelectionSummary(
    state: ToolScreenState,
    modifier: Modifier = Modifier,
) {
    val summary =
        if (state.tool == CleanupTool.LARGE_FILES) {
            stringResource(
                R.string.tool_selected_bytes,
                DisplayFormatters.size(
                    LocalResources.current,
                    state.selected.sumOf { it.sizeBytes },
                ),
            )
        } else {
            pluralStringResource(R.plurals.tool_selected_count, state.selected.size, state.selected.size)
        }
    Column(modifier) {
        Text(summary, style = MaterialTheme.typography.titleMedium)
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
    ) {
        Text(
            stringResource(
                if (state.tool ==
                    CleanupTool.EMPTY_FOLDERS
                ) {
                    R.string.tool_delete_folders
                } else {
                    R.string.tool_move
                },
            ),
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
            stringResource(R.string.delete_confirmation_title),
            androidx.compose.ui.res.pluralStringResource(
                R.plurals.tool_confirm_move,
                batch.files.size,
                batch.files.size,
                DisplayFormatters.size(resources, batch.selectedBytes),
                scopeLabel(state),
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
                scopeLabel(state),
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
                    R.string.deletion_outcome,
                    result.completed,
                    DisplayFormatters.size(resources, 0),
                    result.failed,
                )
            }
        androidx.compose.material3.AlertDialog(onDismissRequest = onDismissOutcome, text = {
            Text(message)
        }, confirmButton = { TextButton(onDismissOutcome) { Text(stringResource(android.R.string.ok)) } })
    }
}

@Composable
internal fun scopeLabel(state: ToolScreenState): String {
    val scope =
        state.results
            ?.session
            ?.scope
            ?.let(ToolScope::decode) ?: return stringResource(R.string.selected_storage)
    return if (scope.includedPaths.isNotEmpty()) {
        scope.includedPaths.joinToString("\n")
    } else {
        scope.volumes.joinToString("\n") { volume ->
            state.volumes.firstOrNull { it.id == volume.id }?.label
                ?: volume.path
        }
    }
}

internal const val LARGE_FONT_SCALE = 1.5f
