package com.emma.duplicates.ui.exclusions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.ui.components.DetailHeader

data class BrowserEntryUiState(
    val canonicalPath: String,
    val displayName: String,
    val isDirectory: Boolean,
    val accessible: Boolean,
)

data class BrowserBreadcrumbUiState(
    val canonicalPath: String,
    val label: String,
)

enum class BrowserMessage {
    ALREADY_EXCLUDED,
    PARENT_ALREADY_EXCLUDED,
    INACCESSIBLE,
}

data class ExclusionBrowserUiState(
    val kind: ExclusionKind,
    val currentPath: String,
    val entries: List<BrowserEntryUiState>,
    val breadcrumbs: List<BrowserBreadcrumbUiState> = emptyList(),
    val canSelectCurrentFolder: Boolean = true,
    val message: BrowserMessage? = null,
)

@Composable
fun ExclusionBrowserScreen(
    state: ExclusionBrowserUiState,
    onBack: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    onSelectCurrentFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 24.dp
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DetailHeader(
                titleRes =
                    if (state.kind == ExclusionKind.FOLDER) {
                        R.string.choose_folder
                    } else {
                        R.string.choose_file
                    },
                onBack = onBack,
            )
            Text(
                text = stringResource(R.string.current_location),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (state.breadcrumbs.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.breadcrumbs.forEach { breadcrumb ->
                        Text(
                            text = breadcrumb.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable { onOpenDirectory(breadcrumb.canonicalPath) }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                    }
                }
            } else {
                Text(
                    text = state.currentPath,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.message?.let {
                Text(
                    text = stringResource(it.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.empty_folder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.entries, key = { it.canonicalPath }) { entry ->
                        BrowserEntryRow(
                            state = entry,
                            onClick = {
                                if (entry.isDirectory) {
                                    onOpenDirectory(entry.canonicalPath)
                                } else {
                                    onSelectFile(entry.canonicalPath)
                                }
                            },
                        )
                    }
                }
            }
            if (state.kind == ExclusionKind.FOLDER) {
                PrimaryActionButton(
                    onClick = onSelectCurrentFolder,
                    enabled = state.canSelectCurrentFolder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.select_this_folder))
                }
            }
        }
    }
}

@Composable
private fun BrowserEntryRow(
    state: BrowserEntryUiState,
    onClick: () -> Unit,
) {
    DuplicatesCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(enabled = state.accessible, onClick = onClick)
                .semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    if (state.isDirectory) Icons.Outlined.Home else Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        if (state.accessible) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                if (!state.accessible) {
                    Text(
                        text = stringResource(R.string.folder_not_accessible),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun BrowserMessage.messageRes(): Int =
    when (this) {
        BrowserMessage.ALREADY_EXCLUDED -> R.string.already_excluded
        BrowserMessage.PARENT_ALREADY_EXCLUDED -> R.string.parent_already_excluded
        BrowserMessage.INACCESSIBLE -> R.string.folder_not_accessible
    }
