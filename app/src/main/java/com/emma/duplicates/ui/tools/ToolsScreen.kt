package com.emma.duplicates.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.CleanupMetadata
import com.emma.duplicates.core.designsystem.CleanupRowTitle
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.ui.components.HomeHeader
import com.emma.duplicates.ui.components.ScreenColumn
import com.emma.duplicates.ui.home.HomeConfiguration
import com.emma.duplicates.ui.home.HomeUiState

@Composable
fun DashboardScreen(
    state: HomeUiState,
    configuration: HomeConfiguration,
    onOpenSettings: () -> Unit,
    onGrantAccess: () -> Unit,
    onOpenTool: (CleanupTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HomeHeader(onOpenSettings, prominent = true)
        DuplicatesCard(Modifier.fillMaxWidth().clickable { onOpenTool(CleanupTool.STORAGE_USAGE) }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_storage),
                        null,
                        Modifier.padding(end = 12.dp).size(24.dp),
                    )
                    Text(
                        stringResource(R.string.storage),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(24.dp))
                }
                if (state == HomeUiState.PermissionRequired) {
                    PrimaryActionButton(onGrantAccess, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.grant_storage_access))
                    }
                } else {
                    DashboardStorage(configuration)
                }
            }
        }
        ToolList(onOpenTool)
    }
}

@Composable
private fun DashboardStorage(configuration: HomeConfiguration) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val total = configuration.totalBytes
        if (total == null || total <= 0) {
            Text(stringResource(R.string.cleanup_unknown))
        } else {
            val free = configuration.freeBytes.coerceIn(0L, total)
            val used = total - free
            Text(
                stringResource(
                    R.string.tool_used,
                    DisplayFormatters.size(LocalResources.current, used),
                ),
                style = CleanupRowTitle,
            )
            Text(
                stringResource(R.string.storage_free, DisplayFormatters.size(LocalResources.current, free)),
                style = CleanupMetadata,
            )
            androidx.compose.material3.LinearProgressIndicator(
                progress = { (used.toDouble() / total).toFloat() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 6.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun ToolList(onOpenTool: (CleanupTool) -> Unit) {
    DuplicatesCard(Modifier.fillMaxWidth()) {
        val tools = CleanupTool.entries.filter { it != CleanupTool.STORAGE_USAGE }
        tools.forEachIndexed { index, tool ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("tool-${tool.name}")
                    .clickable { onOpenTool(tool) }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(painterResource(tool.iconResource()), null, Modifier.size(24.dp))
                Text(
                    stringResource(tool.titleResource()),
                    Modifier.weight(1f),
                    style = CleanupRowTitle,
                )
                Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(24.dp))
            }
            if (index < tools.lastIndex) HorizontalDivider(Modifier.padding(start = 52.dp, end = 16.dp))
        }
    }
}

fun CleanupTool.titleResource(): Int =
    when (this) {
        CleanupTool.DUPLICATES -> R.string.app_name
        CleanupTool.EMPTY_FOLDERS -> R.string.empty_folders
        CleanupTool.LARGE_FILES -> R.string.large_files
        CleanupTool.SIMILAR_PHOTOS -> R.string.similar_photos
        CleanupTool.SCREENSHOTS -> R.string.screenshots
        CleanupTool.OLD_DOWNLOADS -> R.string.old_downloads
        CleanupTool.UNUSED_APPS -> R.string.unused_apps
        CleanupTool.STORAGE_USAGE -> R.string.storage_usage
    }

private fun CleanupTool.iconResource(): Int =
    when (this) {
        CleanupTool.DUPLICATES -> R.drawable.ic_app_stack
        CleanupTool.EMPTY_FOLDERS -> R.drawable.ic_folder
        CleanupTool.LARGE_FILES -> R.drawable.ic_category_documents
        CleanupTool.SIMILAR_PHOTOS -> R.drawable.ic_category_photos
        CleanupTool.SCREENSHOTS -> R.drawable.ic_screenshot
        CleanupTool.OLD_DOWNLOADS -> R.drawable.ic_download
        CleanupTool.UNUSED_APPS -> R.drawable.ic_apps
        CleanupTool.STORAGE_USAGE -> R.drawable.ic_storage
    }
