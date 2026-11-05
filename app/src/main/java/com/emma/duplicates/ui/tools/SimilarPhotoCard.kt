package com.emma.duplicates.ui.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.emma.duplicates.R
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.designsystem.CleanupMetadata
import com.emma.duplicates.core.designsystem.cleanupCheckboxColors
import com.emma.duplicates.core.format.DisplayFormatters
import java.io.File

@Composable
internal fun SimilarPhotoCard(
    item: ToolItemEntity,
    busy: Boolean,
    onSelect: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = stringResource(R.string.tool_open_item, item.name)
    val status =
        stringResource(
            if (item.selected) R.string.tool_selected_for_recovery else R.string.keep,
        )
    Surface(
        modifier.fillMaxHeight(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border =
            BorderStroke(
                if (item.selected) 2.dp else 1.dp,
                if (item.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onPreview).semantics {
                    contentDescription = preview
                    stateDescription = status
                },
            ) {
                AsyncImage(File(item.canonicalPath), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        DisplayFormatters.size(LocalResources.current, item.sizeBytes),
                        Modifier.weight(1f),
                        style = CleanupMetadata,
                    )
                    if (!item.keeper) SimilarPhotoOptions(item, busy, onKeep)
                }
                SimilarPhotoSelection(item, status, busy, onSelect)
            }
        }
    }
}

@Composable
private fun SimilarPhotoSelection(
    item: ToolItemEntity,
    status: String,
    busy: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val selection = stringResource(R.string.tool_select_item, item.name)
    Column {
        if (item.keeper) {
            Row(
                Modifier.heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(20.dp))
                Text(status, style = CleanupMetadata)
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        item.selected,
                        enabled = !busy,
                        role = Role.Checkbox,
                        onValueChange = onSelect,
                    ).heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = selection
                        stateDescription = status
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(item.selected, null, colors = cleanupCheckboxColors())
                Text(status, style = CleanupMetadata)
            }
        }
    }
}

@Composable
private fun SimilarPhotoOptions(
    item: ToolItemEntity,
    busy: Boolean,
    onKeep: () -> Unit,
) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val keep = stringResource(R.string.tool_keep_photo_description, item.name)
    Box {
        IconButton({ expanded = true }, enabled = !busy) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.tool_photo_options, item.name))
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tool_keep_photo)) },
                onClick = {
                    expanded = false
                    onKeep()
                },
                modifier = Modifier.semantics { contentDescription = keep },
                enabled = !busy,
            )
        }
    }
}
