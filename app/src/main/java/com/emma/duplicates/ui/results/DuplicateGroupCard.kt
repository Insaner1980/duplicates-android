package com.emma.duplicates.ui.results

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.ui.components.MediaThumbnail
import com.emma.duplicates.ui.components.labelRes
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun DuplicateGroupCard(
    group: DuplicateGroupCardUiState,
    onPreviewFile: (String, String) -> Unit,
    onReviewGroup: (String) -> Unit,
    videoImageLoader: ImageLoader,
    audioArtworkVisible: Boolean,
) {
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaThumbnail(
                    category = group.category,
                    thumbnailModel = group.thumbnailModel,
                    audioContentUri = group.audioContentUri,
                    contentDescription = stringResource(R.string.thumbnail_description, group.title),
                    videoImageLoader = videoImageLoader,
                    audioArtworkVisible = audioArtworkVisible,
                    modifier =
                        group.previewFileId?.let { fileId ->
                            Modifier.clickable { onPreviewFile(group.id, fileId) }
                        } ?: Modifier,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text =
                            stringResource(
                                R.string.group_metadata,
                                stringResource(group.category.labelRes()),
                                englishInteger(group.copyCount),
                                DisplayFormatters.size(LocalResources.current, group.reclaimableBytes),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            GroupPaths(group.paths)
            PrimaryActionButton(
                onClick = { onReviewGroup(group.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.review))
            }
        }
    }
}

@Composable
private fun GroupPaths(paths: List<String>) {
    paths.take(2).forEach { path ->
        Text(
            text = path,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (paths.size > 2) {
        Text(
            text =
                pluralStringResource(
                    R.plurals.location_count_more,
                    paths.size - 2,
                    paths.size - 2,
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun englishInteger(value: Int): String = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(value)
