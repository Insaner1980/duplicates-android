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
internal fun ToolFileRow(
    item: ToolItemEntity,
    tool: CleanupTool,
    folder: String,
    busy: Boolean,
    onSelect: (Boolean) -> Unit,
    onPreview: () -> Unit,
) {
    val videoLoader = rememberVideoThumbnailImageLoader()
    val selection = stringResource(R.string.tool_select_item, item.name)
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                item.selected,
                onSelect,
                enabled = !busy,
                modifier =
                    Modifier.size(48.dp).semantics {
                        contentDescription =
                            selection
                    },
            )
            Row(
                Modifier.weight(1f).clickable(onClick = onPreview).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (item.directory) {
                    Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(28.dp))
                } else {
                    MediaThumbnail(
                        UiFileCategory.valueOf(item.category),
                        File(item.canonicalPath),
                        item.contentUri,
                        null,
                        videoLoader,
                        false,
                        size = 56.dp,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    if (!item.directory) Text(DisplayFormatters.size(LocalResources.current, item.sizeBytes))
                    Text(
                        if (tool ==
                            CleanupTool.OLD_DOWNLOADS
                        ) {
                            itemDate(item)
                        } else {
                            folder
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ToolImageCard(
    item: ToolItemEntity,
    busy: Boolean,
    screenshot: Boolean,
    onSelect: (Boolean) -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selection = stringResource(R.string.tool_select_item, item.name)
    val preview = stringResource(R.string.tool_open_item, item.name)
    val keeper = !screenshot && item.keeper
    Surface(
        modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (keeper) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(4.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(if (screenshot) SCREENSHOT_ASPECT_RATIO else PHOTO_ASPECT_RATIO),
            ) {
                Box(
                    Modifier.fillMaxSize().clickable(onClick = onPreview).semantics { contentDescription = preview },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_category_photos), null, Modifier.size(40.dp))
                    AsyncImage(File(item.canonicalPath), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (keeper) {
                        Surface(Modifier.align(Alignment.TopStart).padding(6.dp), shape = MaterialTheme.shapes.medium) {
                            Text(
                                stringResource(R.string.tool_keep),
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                if (screenshot) {
                    Surface(Modifier.align(Alignment.TopEnd), shape = MaterialTheme.shapes.small) {
                        ToolImageSelection(item.selected, busy, selection, onSelect)
                    }
                }
            }
            if (!screenshot) ToolPhotoDetails(item, busy, selection, onSelect)
        }
    }
}

@Composable
private fun ToolPhotoDetails(
    item: ToolItemEntity,
    busy: Boolean,
    selection: String,
    onSelect: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            DisplayFormatters.size(LocalResources.current, item.sizeBytes),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (item.keeper) {
            Icon(Icons.Outlined.Lock, stringResource(R.string.tool_keep), Modifier.padding(12.dp).size(24.dp))
        } else {
            ToolImageSelection(item.selected, busy, selection, onSelect)
        }
    }
}

@Composable
private fun ToolImageSelection(
    selected: Boolean,
    busy: Boolean,
    selection: String,
    onSelect: (Boolean) -> Unit,
) {
    Checkbox(
        selected,
        onSelect,
        enabled = !busy,
        modifier = Modifier.size(48.dp).semantics { contentDescription = selection },
    )
}

@Composable
private fun itemDate(item: ToolItemEntity): String =
    if (item.modifiedAt <= 0 ||
        item.modifiedAt > System.currentTimeMillis()
    ) {
        stringResource(R.string.tool_unknown_date)
    } else {
        DisplayFormatters.dateTime(LocalResources.current, item.modifiedAt)
    }

internal fun monthLabel(
    item: ToolItemEntity,
    state: ToolScreenState,
): String {
    if (item.modifiedAt <= 0 || item.modifiedAt > (state.results?.session?.startedAt ?: 0)) return ""
    val zone =
        state.results
            ?.session
            ?.scope
            ?.let(ToolScope::decode)
            ?.zoneId ?: ZoneId.systemDefault().id
    return DateTimeFormatter
        .ofPattern(
            "MMMM yyyy",
        ).format(Instant.ofEpochMilli(item.modifiedAt).atZone(ZoneId.of(zone)))
}

private const val PHOTO_ASPECT_RATIO = 1.15f
private const val SCREENSHOT_ASPECT_RATIO = 1.35f

internal fun relativeFolder(
    item: ToolItemEntity,
    state: ToolScreenState,
): String {
    val root =
        state.results
            ?.session
            ?.scope
            ?.let(ToolScope::decode)
            ?.volumes
            ?.firstOrNull { it.id == item.volume }
            ?.path
            .orEmpty()
    return item.canonicalPath
        .removePrefix(root)
        .trimStart('/')
        .substringBeforeLast('/', "")
}
