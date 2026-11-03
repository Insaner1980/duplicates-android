package com.emma.duplicates.ui.tools

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.data.UnusedAppsResult
import com.emma.duplicates.data.UsageAccessState
import com.emma.duplicates.ui.components.DetailHeader
import com.emma.duplicates.ui.components.ScreenColumn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun UnusedAppsScreen(
    state: UnusedAppsResult,
    days: Int,
    onBack: () -> Unit,
    onDays: (Int) -> Unit,
    onRefresh: () -> Unit,
    onGrant: () -> Unit,
    onDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DetailHeader(R.string.unused_apps, onBack) {
            IconButton(onRefresh, enabled = state.state != UsageAccessState.LOADING) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
            }
        }
        val choices = UNUSED_DAYS
        ToolDropdown(
            pluralStringResource(R.plurals.tool_unused_days, days, days),
            choices.map {
                pluralStringResource(R.plurals.tool_unused_days, it, it)
            },
        ) { onDays(choices[it]) }
        when (state.state) {
            UsageAccessState.LOADING -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            UsageAccessState.NOT_GRANTED, UsageAccessState.REVOKED -> {
                Text(
                    stringResource(
                        if (state.state ==
                            UsageAccessState.REVOKED
                        ) {
                            R.string.tool_usage_revoked
                        } else {
                            R.string.tool_usage_access
                        },
                    ),
                )
                PrimaryActionButton(onGrant) { Text(stringResource(R.string.tool_grant_access)) }
            }

            UsageAccessState.UNAVAILABLE -> {
                Text(stringResource(R.string.tool_usage_unavailable))
            }

            UsageAccessState.EMPTY -> {
                Text(stringResource(R.string.tool_no_apps))
            }

            UsageAccessState.RESULTS -> {
                Unit
            }
        }
        state.apps.forEach { app ->
            UnusedAppCard(app, onDetails)
        }
    }
}

@Composable
private fun UnusedAppCard(
    app: com.emma.duplicates.data.UnusedApp,
    onDetails: (String) -> Unit,
) {
    val detailsLabel = stringResource(R.string.tool_app_details_for, app.label)
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InstalledAppIcon(app.packageName)
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    app.sizeBytes?.let { DisplayFormatters.size(LocalResources.current, it) }
                        ?: stringResource(R.string.tool_unknown),
                )
                Text(
                    stringResource(
                        R.string.tool_last_used,
                        DisplayFormatters.dateTime(LocalResources.current, app.lastUsed),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    { onDetails(app.packageName) },
                    Modifier.semantics { contentDescription = detailsLabel },
                ) {
                    Text(stringResource(R.string.tool_app_details))
                }
            }
        }
    }
}

@Composable
private fun InstalledAppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, packageName) {
        value = loadInstalledAppIcon(context, packageName)
    }
    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.size(52.dp)) }
        ?: Icon(painterResource(R.drawable.ic_apps), null, Modifier.size(52.dp))
}

private suspend fun loadInstalledAppIcon(
    context: android.content.Context,
    packageName: String,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): android.graphics.Bitmap? =
    withContext(ioDispatcher) {
        try {
            context.packageManager.getApplicationIcon(packageName).toBitmap(APP_ICON_PIXELS, APP_ICON_PIXELS)
        } catch (
            _: android.content.pm.PackageManager.NameNotFoundException,
        ) {
            null
        }
    }

private val UNUSED_DAYS = listOf(UNUSED_MONTH, UNUSED_QUARTER, UNUSED_HALF_YEAR)
private const val APP_ICON_PIXELS = 64

private const val UNUSED_MONTH = 30
private const val UNUSED_QUARTER = 90
private const val UNUSED_HALF_YEAR = 180
