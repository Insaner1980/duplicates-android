package com.emma.duplicates.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.emma.duplicates.R
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.designsystem.CleanupMetadata
import com.emma.duplicates.core.designsystem.CleanupRowTitle
import java.io.File

@Composable
internal fun SimilarGroupsDialog(
    state: ToolScreenState,
    onGroup: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = similarPhotoGroups(state.results)
    val index = state.controls.group.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_browse_groups)) },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("tool-group-list"),
                state = rememberLazyListState(initialFirstVisibleItemIndex = index),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(groups, key = { _, group -> requireNotNull(group.first().groupId) }) { groupIndex, group ->
                    SimilarGroupRow(group, groupIndex, groupIndex == index, { onGroup(groupIndex) })
                }
            }
        },
        confirmButton = {
            TextButton(onDismiss) { Text(stringResource(R.string.tool_close_groups)) }
        },
    )
}

@Composable
private fun SimilarGroupRow(
    group: List<ToolItemEntity>,
    index: Int,
    current: Boolean,
    onClick: () -> Unit,
) {
    val preview = group.firstOrNull { it.keeper } ?: group.first()
    val selectedCount = group.count { it.selected && !it.keeper }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .semantics { selected = current }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(File(preview.canonicalPath), null, Modifier.size(48.dp), contentScale = ContentScale.Fit)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.tool_group_number, index + 1), style = CleanupRowTitle)
            Text(
                pluralStringResource(R.plurals.tool_photo_count, group.size, group.size),
                style = CleanupMetadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedCount > 0) {
                Text(
                    pluralStringResource(R.plurals.tool_selected_count, selectedCount, selectedCount),
                    style = CleanupMetadata,
                )
            }
        }
    }
}
