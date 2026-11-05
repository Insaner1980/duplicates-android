package com.emma.duplicates.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
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
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.ui.model.UiFileCategory

@Composable
fun DetailHeader(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleLarge,
    stackTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            }
            if (stackTitle) {
                Spacer(Modifier.weight(1f))
            } else {
                Text(stringResource(titleRes), Modifier.weight(1f).semantics { heading() }, style = titleStyle)
            }
            actions()
        }
        if (stackTitle) {
            Text(stringResource(titleRes), Modifier.fillMaxWidth().semantics { heading() }, style = titleStyle)
        }
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
        tint = MaterialTheme.colorScheme.onSurface,
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
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
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
        text = { Text(message, modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) },
        confirmButton = {
            if (destructive) {
                PrimaryActionButton(onClick = onConfirm) { Text(confirmLabel) }
            } else {
                TextButton(onClick = onConfirm) {
                    Text(confirmLabel, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurface)
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
fun HomeHeader(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_app_stack),
            null,
            Modifier.size(32.dp),
            tint =
                if (prominent) {
                    com.emma.duplicates.core.designsystem
                        .actionTextColor()
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        Text(
            text = stringResource(R.string.app_name),
            style =
                if (prominent) {
                    com.emma.duplicates.core.designsystem.DashboardTitle
                } else {
                    MaterialTheme.typography.titleLarge
                },
            modifier =
                Modifier
                    .weight(1f)
                    .semantics { heading() },
        )
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.settings),
            )
        }
    }
}
