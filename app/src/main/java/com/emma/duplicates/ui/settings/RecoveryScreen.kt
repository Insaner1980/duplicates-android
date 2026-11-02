package com.emma.duplicates.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.database.RecoveryFile
import com.emma.duplicates.ui.components.DetailHeader
import java.text.DateFormat
import java.util.Date

@Composable
fun RecoveryScreen(
    files: List<RecoveryFile>,
    busy: Boolean,
    result: String?,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { DetailHeader(R.string.recovery_files, onBack) }
        item { Text(stringResource(R.string.recovery_explanation)) }
        if (files.isEmpty()) item { Text(stringResource(R.string.recovery_empty)) }
        items(files, key = { it.id }) { file ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(file.originalPath, style = MaterialTheme.typography.bodyLarge)
                Text(
                    file.expiresAt?.let {
                        stringResource(R.string.recovery_expiry, DateFormat.getDateInstance().format(Date(it)))
                    } ?: stringResource(R.string.recovery_pending),
                )
                Button(onClick = { onRestore(file.id) }, enabled = !busy) {
                    Text(stringResource(R.string.restore_copy))
                }
            }
        }
    }
    if (result != null) {
        AlertDialog(
            onDismissRequest = onDismissResult,
            title = { Text(stringResource(R.string.restore_copy)) },
            text = {
                Text(
                    if (result.isEmpty()) {
                        stringResource(R.string.recovery_failed)
                    } else {
                        stringResource(R.string.recovery_restored, result)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissResult) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}
