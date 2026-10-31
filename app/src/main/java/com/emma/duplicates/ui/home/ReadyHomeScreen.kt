package com.emma.duplicates.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.ui.components.HomeIntroContent

@Composable
fun ReadyHomeScreen(
    locationSummary: String,
    typeSummary: String,
    scanEnabled: Boolean,
    onStartScan: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HomeIntroContent(onOpenSettings = onOpenSettings, modifier = modifier) {
        Text(
            text = stringResource(R.string.ready_to_scan),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        SummaryField(
            label = stringResource(R.string.scan_locations),
            value = locationSummary,
        )
        SummaryField(
            label = stringResource(R.string.types_to_scan),
            value = typeSummary,
        )
        Text(
            text = stringResource(R.string.scanning_does_not_delete),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryActionButton(
            onClick = onStartScan,
            modifier = Modifier.fillMaxWidth(),
            enabled = scanEnabled,
        ) {
            Text(text = stringResource(R.string.start_scan))
        }
    }
}

@Composable
private fun SummaryField(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
