package com.emma.duplicates.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.Primary
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.ui.components.ConfirmationDialog
import com.emma.duplicates.ui.components.CategoryIcon
import com.emma.duplicates.ui.components.DetailHeader
import com.emma.duplicates.ui.components.MediaThumbnail
import com.emma.duplicates.ui.components.rememberVideoThumbnailImageLoader
import com.emma.duplicates.ui.model.UiFileCategory

data class ReviewMemberUiState(
    val id: String,
    val filename: String,
    val sizeBytes: Long,
    val dateEpochMillis: Long,
    val path: String,
    val thumbnailModel: Any?,
    val selectedForDeletion: Boolean,
    val recommendedKeep: Boolean,
    val canSelectForDeletion: Boolean,
    val audioContentUri: String? = null,
)

data class DeletionResultUiState(
    val deletedCount: Int,
    val failedCount: Int,
    val reclaimedBytes: Long = 0,
    val changedFilesKept: Boolean = false,
    val authorizationCanceled: Boolean = false,
)

data class ReviewUiState(
    val groupId: String,
    val title: String,
    val category: UiFileCategory,
    val identicalFileCount: Int,
    val reclaimableBytes: Long,
    val selectedCount: Int,
    val selectedBytes: Long,
    val members: List<ReviewMemberUiState>,
    val deleteConfirmationVisible: Boolean = false,
    val deletionResult: DeletionResultUiState? = null,
)

@Composable
fun ReviewDuplicatesScreen(
    state: ReviewUiState,
    onBack: () -> Unit,
    onPreviewMember: (String) -> Unit,
    onMemberSelectionChange: (String, Boolean) -> Unit,
    onSelectionBlocked: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDeletionResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding(),
    ) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 24.dp
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val listState = rememberLazyListState()
            val visibleMemberIds by
                remember(listState) {
                    derivedStateOf {
                        listState.layoutInfo.visibleItemsInfo.mapNotNullTo(mutableSetOf()) { it.key as? String }
                    }
                }
            val videoImageLoader = rememberVideoThumbnailImageLoader()
            DetailHeader(titleRes = R.string.review_duplicates, onBack = onBack)
            ReviewSummaryCard(state)
            LazyColumn(
                modifier = Modifier.weight(1f).testTag("review-list"),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.members, key = { it.id }) { member ->
                    ReviewMemberCard(
                        state = member,
                        category = state.category,
                        videoImageLoader = videoImageLoader,
                        audioArtworkVisible = member.id in visibleMemberIds,
                        onPreview = { onPreviewMember(member.id) },
                        onToggle = {
                            if (member.canSelectForDeletion) {
                                onMemberSelectionChange(member.id, !member.selectedForDeletion)
                            } else {
                                onSelectionBlocked()
                            }
                        },
                    )
                }
            }
            PrimaryActionButton(
                onClick = onRequestDelete,
                enabled = state.selectedCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.selectedCount > 0) {
                        stringResource(
                            R.string.delete_selected_duplicates,
                            state.selectedCount,
                            DisplayFormatters.size(LocalResources.current, state.selectedBytes),
                        )
                    } else {
                        stringResource(R.string.nothing_selected)
                    },
                )
            }
        }
    }

    if (state.deleteConfirmationVisible) {
        ConfirmationDialog(
            title = stringResource(R.string.delete_confirmation_title),
            message =
                pluralStringResource(
                    R.plurals.delete_confirmation_message,
                    state.selectedCount,
                    state.selectedCount,
                    DisplayFormatters.size(LocalResources.current, state.selectedBytes),
                ),
            confirmLabel = stringResource(R.string.delete_selected),
            onDismiss = onDismissDelete,
            onConfirm = onConfirmDelete,
            destructive = true,
        )
    }
    state.deletionResult?.let { result ->
        DeletionResultDialog(result, onDismissDeletionResult)
    }
}

@Composable
private fun ReviewMemberCard(
    state: ReviewMemberUiState,
    category: UiFileCategory,
    videoImageLoader: ImageLoader,
    audioArtworkVisible: Boolean,
    onPreview: () -> Unit,
    onToggle: () -> Unit,
) {
    val selectionDescription =
        stringResource(
            if (state.selectedForDeletion) R.string.delete_description else R.string.kept_description,
            state.filename,
        )
    val previewDescription = stringResource(R.string.preview_item_description, state.filename)
    DuplicatesCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 112.dp)
                .testTag("review-member-${state.id}")
                .clickable(onClick = onToggle)
                .semantics { contentDescription = selectionDescription },
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(72.dp)
                        .clickable(onClick = onPreview)
                        .semantics { contentDescription = previewDescription },
                contentAlignment = Alignment.Center,
            ) {
                MediaThumbnail(
                    category = category,
                    thumbnailModel = state.thumbnailModel,
                    audioContentUri = state.audioContentUri,
                    contentDescription = null,
                    videoImageLoader = videoImageLoader,
                    audioArtworkVisible = audioArtworkVisible,
                    fallbackIconSize = 48.dp,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(state.filename, style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        stringResource(
                            R.string.file_size_label,
                            DisplayFormatters.size(LocalResources.current, state.sizeBytes),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = state.path,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = DisplayFormatters.dateTime(LocalResources.current, state.dateEpochMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                        stringResource(
                            if (state.selectedForDeletion) {
                                R.string.selected_for_deletion
                            } else {
                                R.string.kept
                            },
                        ),
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (state.selectedForDeletion) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Primary
                        },
                )
                if (state.recommendedKeep) {
                    Text(
                        text = stringResource(R.string.recommended),
                        style = MaterialTheme.typography.labelLarge,
                        color = Primary,
                    )
                }
            }
            Icon(
                imageVector =
                    if (state.selectedForDeletion) Icons.Outlined.Close else Icons.Outlined.Check,
                contentDescription = null,
                tint =
                    if (state.selectedForDeletion) MaterialTheme.colorScheme.error else Primary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun ReviewSummaryCard(state: ReviewUiState) {
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryIcon(state.category, Modifier.size(48.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text =
                        stringResource(
                            R.string.identical_files_summary,
                            state.identicalFileCount,
                            DisplayFormatters.size(LocalResources.current, state.reclaimableBytes),
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.keep_at_least_one_copy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeletionResultDialog(
    result: DeletionResultUiState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deletion_complete)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.deletion_result,
                        result.deletedCount,
                        result.failedCount,
                    ),
                )
                Text(
                    stringResource(
                        R.string.deletion_space_freed,
                        DisplayFormatters.size(LocalResources.current, result.reclaimedBytes),
                    ),
                )
                if (result.changedFilesKept) Text(stringResource(R.string.files_changed_before_delete))
                if (result.authorizationCanceled) {
                    Text(stringResource(R.string.deletion_authorization_canceled))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        shape = MaterialTheme.shapes.extraLarge,
    )
}
