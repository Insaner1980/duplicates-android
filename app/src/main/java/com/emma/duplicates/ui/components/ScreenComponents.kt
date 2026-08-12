package com.emma.duplicates.ui.components

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.Primary
import com.emma.duplicates.ui.model.UiFileCategory

@Composable
fun DetailHeader(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineMedium,
            modifier =
                Modifier
                    .weight(1f)
                    .semantics { heading() },
        )
        actions()
    }
}

@Composable
fun CategoryIcon(
    category: UiFileCategory,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(category.iconRes),
        contentDescription = null,
        tint = Primary,
        modifier = modifier.size(32.dp),
    )
}

@StringRes
fun UiFileCategory.labelRes(): Int =
    when (this) {
        UiFileCategory.PHOTOS -> R.string.photos
        UiFileCategory.VIDEOS -> R.string.videos
        UiFileCategory.AUDIO -> R.string.audio
        UiFileCategory.DOCUMENTS -> R.string.documents
    }

@get:DrawableRes
val UiFileCategory.iconRes: Int
    get() =
        when (this) {
            UiFileCategory.PHOTOS -> R.drawable.ic_category_photos
            UiFileCategory.VIDEOS -> R.drawable.ic_category_videos
            UiFileCategory.AUDIO -> R.drawable.ic_category_audio
            UiFileCategory.DOCUMENTS -> R.drawable.ic_category_documents
        }

@Composable
fun Metric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = if (emphasized) Primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else Primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
    )
}
