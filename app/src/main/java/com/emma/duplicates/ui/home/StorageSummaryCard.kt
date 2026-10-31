package com.emma.duplicates.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.FreeStorage
import com.emma.duplicates.core.designsystem.OtherUsedStorage
import com.emma.duplicates.core.designsystem.Primary
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.core.format.StorageLabels
import com.emma.duplicates.core.format.StorageSegments
import java.text.NumberFormat
import java.util.Locale

private const val RING_START_DEGREES = -90f
private const val FULL_CIRCLE_DEGREES = 360f

@Composable
internal fun CompletedSummaryCard(summary: CompletedHomeUiState) {
    val storage =
        StorageSegments.calculate(
            totalBytes = summary.totalStorageBytes,
            freeBytes = summary.freeStorageBytes,
            reclaimableBytes = summary.reclaimableBytes,
        )
    val storageLabels = DisplayFormatters.storageLabels(LocalResources.current, storage)
    val chartDescription =
        stringResource(
            R.string.storage_chart_description,
            storageLabels.total,
            storageLabels.reclaimable,
            storageLabels.free,
        )
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(R.string.space_you_can_reclaim),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = DisplayFormatters.size(LocalResources.current, summary.reclaimableBytes),
                style = MaterialTheme.typography.displayLarge,
                color = Primary,
            )
            Text(
                text = stringResource(R.string.duplicate_files_value, englishInteger(summary.duplicateFileCount)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.duplicate_groups_value, englishInteger(summary.duplicateGroupCount)),
                style = MaterialTheme.typography.bodyLarge,
            )
            StorageRing(storage, storageLabels.total, chartDescription)
            StorageLegend(storageLabels)
        }
    }
}

@Composable
private fun StorageRing(
    storage: StorageSegments,
    totalLabel: String,
    spokenDescription: String,
) {
    val total = storage.totalBytes.coerceAtLeast(1L).toFloat()
    val stroke = 18.dp
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clearAndSetSemantics { contentDescription = spokenDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(190.dp)) {
            val strokeWidth = stroke.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            var start = RING_START_DEGREES
            listOf(
                OtherUsedStorage to storage.otherUsedBytes,
                Primary to storage.reclaimableBytes,
                FreeStorage to storage.freeBytes,
            ).forEach { (color, bytes) ->
                val sweep = bytes.toFloat() / total * FULL_CIRCLE_DEGREES
                if (sweep > 0f) {
                    drawArc(
                        color = color,
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(strokeWidth, cap = StrokeCap.Butt),
                    )
                }
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = totalLabel,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.total_storage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StorageLegend(labels: StorageLabels) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LegendRow(stringResource(R.string.other_used_storage), labels.otherUsed)
        LegendRow(stringResource(R.string.reclaimable_storage), labels.reclaimable)
        LegendRow(stringResource(R.string.free_storage), labels.free)
    }
}

@Composable
private fun LegendRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp),
            maxLines = 1,
        )
    }
}

private fun englishInteger(value: Int): String = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(value)
