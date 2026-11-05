package com.emma.duplicates.ui.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.emma.duplicates.R
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.designsystem.CleanupMetadata
import com.emma.duplicates.core.designsystem.CleanupRowTitle
import com.emma.duplicates.core.designsystem.actionTextColor
import com.emma.duplicates.core.designsystem.cleanupCheckboxColors
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.ui.components.MediaThumbnail
import com.emma.duplicates.ui.components.rememberVideoThumbnailImageLoader
import com.emma.duplicates.ui.model.UiFileCategory
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ToolFileRow(
    item: ToolItemEntity,
    tool: CleanupTool,
    folder: String,
    busy: Boolean,
    onSelect: (Boolean) -> Unit,
    onPreview: () -> Unit,
    first: Boolean = true,
    last: Boolean = true,
) {
    val videoLoader = rememberVideoThumbnailImageLoader()
    val selection = stringResource(R.string.tool_select_item, item.name)
    val preview = stringResource(R.string.tool_open_item, item.name)
    Surface(
        Modifier.fillMaxWidth(),
        shape = groupedRowShape(first, last),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                Modifier.heightIn(min = 72.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    item.selected,
                    onSelect,
                    enabled = !busy,
                    colors = cleanupCheckboxColors(),
                    modifier = Modifier.size(48.dp).semantics { contentDescription = selection },
                )
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onPreview)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = preview },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!item.directory) {
                        MediaThumbnail(
                            UiFileCategory.valueOf(item.category),
                            File(item.canonicalPath),
                            item.contentUri,
                            null,
                            videoLoader,
                            false,
                            Modifier.clip(RoundedCornerShape(8.dp)),
                            size = 48.dp,
                            fileName = item.name,
                        )
                    }
                    ToolFileMetadata(item, tool, folder, Modifier.weight(1f))
                }
            }
            if (!last) HorizontalDivider(Modifier.padding(start = if (item.directory) 64.dp else 124.dp, end = 8.dp))
        }
    }
}

internal fun groupedRowShape(
    first: Boolean,
    last: Boolean,
) = RoundedCornerShape(
    topStart = if (first) 16.dp else 0.dp,
    topEnd = if (first) 16.dp else 0.dp,
    bottomStart = if (last) 16.dp else 0.dp,
    bottomEnd = if (last) 16.dp else 0.dp,
)

@Composable
internal fun ToolImageCard(
    item: ToolItemEntity,
    busy: Boolean,
    screenshot: Boolean,
    onSelect: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
    dateLabel: String? = null,
) {
    if (!screenshot) {
        SimilarPhotoCard(item, busy, onSelect, onPreview, onKeep, modifier)
        return
    }
    val selection = stringResource(R.string.tool_select_item, item.name)
    val sizeLabel = DisplayFormatters.size(LocalResources.current, item.sizeBytes)
    Column(modifier) {
        ScreenshotPreview(item, onPreview)
        FlowRow(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                dateLabel ?: stringResource(R.string.cleanup_unknown),
                Modifier.align(Alignment.CenterVertically),
                style = CleanupMetadata,
            )
            Text(sizeLabel, Modifier.align(Alignment.CenterVertically), style = CleanupMetadata)
            Checkbox(
                item.selected,
                onSelect,
                enabled = !busy,
                colors = cleanupCheckboxColors(),
                modifier = Modifier.size(48.dp).semantics { contentDescription = selection },
            )
        }
    }
}

@Composable
private fun ScreenshotPreview(
    item: ToolItemEntity,
    onPreview: () -> Unit,
) {
    val preview = stringResource(R.string.tool_open_item, item.name)
    var failed by remember(item.canonicalPath, item.modifiedAt) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().height(208.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            Modifier.fillMaxSize().clickable(onClick = onPreview).semantics { contentDescription = preview },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_category_photos), null, Modifier.size(40.dp))
                if (failed) {
                    Text(
                        stringResource(R.string.tool_thumbnail_unavailable),
                        style = CleanupMetadata,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            AsyncImage(
                File(item.canonicalPath),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                onSuccess = { failed = false },
            )
        }
    }
}

@Composable
private fun itemDate(item: ToolItemEntity): String =
    if (item.modifiedAt <= 0 || item.modifiedAt > System.currentTimeMillis()) {
        stringResource(R.string.cleanup_unknown)
    } else {
        DisplayFormatters.date(item.modifiedAt)
    }

internal fun monthLabel(
    item: ToolItemEntity,
    state: ToolScreenState,
): String {
    if (item.modifiedAt <= 0 || item.modifiedAt > (state.results?.session?.startedAt ?: 0)) return ""
    return DateTimeFormatter
        .ofPattern("MMMM yyyy", Locale.ENGLISH)
        .format(Instant.ofEpochMilli(item.modifiedAt).atZone(scanZone(state)))
}

internal fun scanZone(state: ToolScreenState): ZoneId =
    ZoneId.of(
        state.results
            ?.session
            ?.scope
            ?.let(ToolScope::decode)
            ?.zoneId ?: ZoneId.systemDefault().id,
    )

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
    val relative =
        item.canonicalPath
            .removePrefix(root)
            .trimStart('/')
    return if (item.directory) relative else relative.substringBeforeLast('/', "")
}

@Composable
private fun ToolFileMetadata(
    item: ToolItemEntity,
    tool: CleanupTool,
    folder: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            item.name,
            Modifier.testTag("file-name-${item.id}").semantics {
                contentDescription = "${item.name} ${item.canonicalPath}"
            },
            style = CleanupRowTitle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!item.directory) {
                Text(
                    DisplayFormatters.size(LocalResources.current, item.sizeBytes),
                    style = CleanupMetadata,
                    fontWeight = if (tool == CleanupTool.LARGE_FILES) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Text(
                if (tool == CleanupTool.OLD_DOWNLOADS) itemDate(item) else folder,
                Modifier.testTag("file-path-${item.id}"),
                style = CleanupMetadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (item.directory || tool == CleanupTool.OLD_DOWNLOADS) Int.MAX_VALUE else 1,
                overflow =
                    if (item.directory || tool == CleanupTool.OLD_DOWNLOADS) {
                        TextOverflow.Clip
                    } else {
                        TextOverflow.StartEllipsis
                    },
            )
        }
    }
}
