package com.emma.duplicates.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.data.storageUsage
import com.emma.duplicates.ui.components.DetailHeader
import com.emma.duplicates.ui.components.ScreenColumn
import java.io.File

@Composable
fun StorageUsageScreen(
    state: ToolScreenState,
    onBack: () -> Unit,
    onRefresh: (String?) -> Unit,
    onStop: () -> Unit,
    onGrantAccess: () -> Unit,
    onFolder: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = state.results?.session
    val volume =
        session
            ?.scope
            ?.let(ToolScope::decode)
            ?.volumes
            ?.singleOrNull()
    val root = volume?.path
    val folder = state.controls.folder ?: root
    val resources = LocalResources.current

    fun size(value: Long?) =
        value?.let { DisplayFormatters.size(resources, it) } ?: resources.getString(R.string.tool_unknown)
    ScreenColumn(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DetailHeader(R.string.storage_usage, onBack) {
            IconButton({ onRefresh(volume?.id) }, enabled = !state.running && !state.busy) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
            }
        }
        val labels = state.volumes.map { it.label }
        if (labels.isNotEmpty()) {
            ToolDropdown(
                state.volumes
                    .firstOrNull {
                        it.id == volume?.id
                    }?.label ?: volume?.path ?: labels.first(),
                labels,
            ) { onRefresh(state.volumes[it].id) }
        }
        ToolRunStatus(state, onStop, onGrantAccess)
        if (session != null && folder != null) {
            val used =
                session.totalBytes?.let { total ->
                    session.freeBytes?.takeIf { it <= total }?.let { total - it }
                }
            Text(stringResource(R.string.tool_used, size(used)), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.tool_total, size(session.totalBytes)))
            val usage = storageUsage(state.results.items, folder, session.totalBytes, session.freeBytes)
            StorageCategories(usage, session)
            StorageFolders(usage.folders, folder, root, onFolder)
        }
    }
}

@Composable
private fun StorageCategories(
    usage: com.emma.duplicates.data.StorageUsage,
    session: com.emma.duplicates.core.database.ToolScanSession,
) {
    val resources = LocalResources.current

    fun size(value: Long?) =
        value?.let { DisplayFormatters.size(resources, it) } ?: resources.getString(R.string.tool_unknown)
    val categories =
        listOf(
            Triple(R.string.videos, R.drawable.ic_category_videos, usage.categories["VIDEOS"]),
            Triple(R.string.photos, R.drawable.ic_category_photos, usage.categories["PHOTOS"]),
            Triple(R.string.audio, R.drawable.ic_category_audio, usage.categories["AUDIO"]),
            Triple(R.string.tool_other_files, R.drawable.ic_category_documents, usage.categories["DOCUMENTS"]),
            Triple(
                R.string.tool_other_used,
                R.drawable.ic_apps,
                usage.otherUsedBytes.takeIf {
                    session.complete &&
                        session.failureReason == null
                },
            ),
        )
    categories.forEach { (label, icon, bytes) ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(painterResource(icon), null, Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(label),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(size(bytes))
                }
                if (bytes !=
                    null
                ) {
                    LinearProgressIndicator(progress = {
                        (
                            bytes.toDouble() /
                                (session.totalBytes ?: 1L).coerceAtLeast(1)
                        ).toFloat().coerceIn(0f, 1f)
                    }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                }
            }
        }
    }
}

@Composable
private fun StorageFolders(
    folders: List<com.emma.duplicates.data.FolderUsage>,
    folder: String,
    root: String?,
    onFolder: (String?) -> Unit,
) {
    val resources = LocalResources.current

    fun size(value: Long?) =
        value?.let { DisplayFormatters.size(resources, it) } ?: resources.getString(R.string.tool_unknown)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(
                R.string.tool_largest_folders,
            ),
            style = MaterialTheme.typography.titleLarge,
            modifier =
                Modifier.semantics {
                    heading()
                },
        )
        if (folder != root) {
            Text(folder, style = MaterialTheme.typography.bodyMedium)
            TextButton({
                onFolder(File(folder).parent?.takeIf { it != root })
            }) { Text(stringResource(R.string.tool_parent_folder)) }
        }
        folders.forEach { child ->
            DuplicatesCard(Modifier.fillMaxWidth().clickable { onFolder(child.path) }) {
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(26.dp))
                    Text(child.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(size(child.bytes))
                    Text("›")
                }
            }
        }
    }
}
