package com.emma.duplicates.ui.exclusions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.Primary

enum class ExclusionKind {
    FOLDER,
    FILE,
}

data class ExclusionItemUiState(
    val id: String,
    val kind: ExclusionKind,
    val displayName: String,
    val canonicalPath: String,
)

data class ExclusionsUiState(
    val folders: List<ExclusionItemUiState>,
    val files: List<ExclusionItemUiState>,
    val addSheetVisible: Boolean = false,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ExclusionsScreen(
    state: ExclusionsUiState,
    onRequestAdd: () -> Unit,
    onDismissAddSheet: () -> Unit,
    onChooseFolder: () -> Unit,
    onChooseFile: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding(),
    ) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 24.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = 24.dp,
                    bottom = 32.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.exclusions),
                        style = MaterialTheme.typography.displayLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconButton(
                        onClick = onRequestAdd,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.add_exclusion),
                        )
                    }
                }
            }
            item {
                DuplicatesCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.scans_skip_excluded_items),
                            style = MaterialTheme.typography.titleLarge,
                            color = Primary,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = stringResource(R.string.exclusions_explanation),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SectionHeader(
                    title = stringResource(R.string.excluded_folders),
                    count = state.folders.size,
                )
            }
            if (state.folders.isEmpty()) {
                item { EmptyExclusionText(R.string.no_excluded_folders) }
            } else {
                items(state.folders, key = { it.id }) { item -> ExclusionRow(item, onRemove) }
            }
            item {
                SectionHeader(
                    title = stringResource(R.string.excluded_files),
                    count = state.files.size,
                )
            }
            if (state.files.isEmpty()) {
                item { EmptyExclusionText(R.string.no_excluded_files) }
            } else {
                items(state.files, key = { it.id }) { item -> ExclusionRow(item, onRemove) }
            }
        }
    }

    if (state.addSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismissAddSheet,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.add_exclusion),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
                )
                AddChoiceRow(R.string.choose_folder, onChooseFolder)
                AddChoiceRow(R.string.choose_file, onChooseFile)
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
) {
    Text(
        text = stringResource(R.string.section_count, title, count),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun EmptyExclusionText(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExclusionRow(
    item: ExclusionItemUiState,
    onRemove: (String) -> Unit,
) {
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter =
                    painterResource(
                        if (item.kind == ExclusionKind.FOLDER) {
                            R.drawable.ic_folder
                        } else {
                            R.drawable.ic_category_documents
                        },
                    ),
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(32.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = item.canonicalPath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { onRemove(item.id) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription =
                        stringResource(R.string.remove_item_description, item.displayName),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddChoiceRow(
    textRes: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = Primary,
        )
        Text(stringResource(textRes), style = MaterialTheme.typography.titleMedium)
    }
}
