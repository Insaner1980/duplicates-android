package com.emma.duplicates.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.format.DisplayFormatters

@Composable
fun StorageSummaryCard(
    totalBytes: Long?,
    freeBytes: Long,
    multipleVolumes: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (multipleVolumes) R.string.selected_storage else R.string.device_storage),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (totalBytes == null || totalBytes <= 0) {
                Text(stringResource(R.string.storage_unavailable), style = MaterialTheme.typography.bodySmall)
            } else {
                val used = totalBytes - freeBytes.coerceIn(0, totalBytes)
                Text(
                    stringResource(
                        R.string.storage_used_of_total,
                        DisplayFormatters.size(LocalResources.current, used),
                        DisplayFormatters.size(LocalResources.current, totalBytes),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { (used.toDouble() / totalBytes).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
            }
        }
    }
}
