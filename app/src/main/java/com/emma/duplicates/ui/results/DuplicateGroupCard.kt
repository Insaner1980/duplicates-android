package com.emma.duplicates.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.emma.duplicates.R
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.ui.components.LARGE_FONT_SCALE
import com.emma.duplicates.ui.components.MediaThumbnail
import com.emma.duplicates.ui.model.UiFileCategory

private const val MAX_VISIBLE_PATH_SEGMENTS = 3

@Composable
internal fun DuplicateGroupHeader(group: DuplicateGroupCardUiState) {
    val title =
        pluralStringResource(
            when (group.category) {
                UiFileCategory.PHOTOS -> R.plurals.identical_photos
                UiFileCategory.VIDEOS -> R.plurals.identical_videos
                UiFileCategory.AUDIO -> R.plurals.identical_audio
                UiFileCategory.DOCUMENTS -> R.plurals.identical_group_files
            },
            group.copyCount,
            group.copyCount,
        )
    val extra =
        stringResource(R.string.extra_space, DisplayFormatters.size(LocalResources.current, group.reclaimableBytes))
    val modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp)
    if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DuplicateMemberRow(
    groupId: String,
    member: ResultMemberUiState,
    last: Boolean,
    enabled: Boolean,
    videoImageLoader: ImageLoader,
    onPreviewFile: (String, String) -> Unit,
    onMemberSelectionChange: (String, Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(
                if (last) RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp) else RoundedCornerShape(0.dp),
            ).background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberPreview(groupId, member, enabled, videoImageLoader, onPreviewFile, Modifier.weight(1f))
            MemberSelection(member, enabled, onMemberSelectionChange)
        }
        if (member.selected) {
            TextButton(
                onClick = { onMemberSelectionChange(member.memberId, false) },
                enabled = enabled,
                modifier = Modifier.testTag("keep-${member.memberId}"),
            ) {
                Text(stringResource(R.string.keep_this_copy), color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            Text(
                stringResource(R.string.keep),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
            )
        }
        if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun MemberMetadata(member: ResultMemberUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            DisplayFormatters.size(LocalResources.current, member.sizeBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            member.location
                .split(" / ")
                .let { segments ->
                    if (segments.size > MAX_VISIBLE_PATH_SEGMENTS) {
                        "…/" + segments.takeLast(MAX_VISIBLE_PATH_SEGMENTS).joinToString("/")
                    } else {
                        segments.joinToString("/")
                    }
                }.ifEmpty {
                    stringResource(R.string.internal_storage)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MemberPreview(
    groupId: String,
    member: ResultMemberUiState,
    enabled: Boolean,
    videoImageLoader: ImageLoader,
    onPreviewFile: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewLabel = stringResource(R.string.preview_file_description, member.filename, member.fullPath)
    val largeFont = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    Column(
        modifier
            .clickable(enabled = enabled) { onPreviewFile(groupId, member.fileId) }
            .semantics(mergeDescendants = true) { contentDescription = previewLabel }
            .testTag("preview-${member.fileId}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaThumbnail(
                member.category,
                member.thumbnailModel,
                member.audioContentUri,
                null,
                videoImageLoader,
                audioArtworkVisible = true,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                size = 56.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    member.filename,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!largeFont) MemberMetadata(member)
            }
        }
        if (largeFont) MemberMetadata(member)
    }
}

@Composable
private fun MemberSelection(
    member: ResultMemberUiState,
    enabled: Boolean,
    onMemberSelectionChange: (String, Boolean) -> Unit,
) {
    val selectedLabel = stringResource(if (member.selected) R.string.selected_for_deletion else R.string.keep)
    Box(
        Modifier.heightIn(min = 48.dp).semantics {
            stateDescription = selectedLabel
        },
        contentAlignment = Alignment.Center,
    ) {
        Checkbox(
            checked = member.selected,
            enabled = enabled && member.canSelect,
            onCheckedChange = { onMemberSelectionChange(member.memberId, it) },
            modifier =
                Modifier
                    .size(48.dp)
                    .testTag("selection-${member.memberId}")
                    .semantics {
                        contentDescription = member.filename
                        stateDescription = selectedLabel
                    },
        )
    }
}
