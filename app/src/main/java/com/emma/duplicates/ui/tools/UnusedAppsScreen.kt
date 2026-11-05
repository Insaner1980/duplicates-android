package com.emma.duplicates.ui.tools

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.CleanupMetadata
import com.emma.duplicates.core.designsystem.CleanupRowTitle
import com.emma.duplicates.core.designsystem.CleanupTitle
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
    val windowSize = LocalWindowInfo.current.containerSize
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
        DetailHeader(
            R.string.unused_apps,
            onBack,
            Modifier.padding(horizontal = 16.dp).heightIn(min = 56.dp),
            titleStyle = CleanupTitle,
            stackTitle =
                with(LocalDensity.current) {
                    windowSize.width.toDp() < 360.dp && fontScale >= LARGE_FONT_SCALE
                },
        ) {
            IconButton(onRefresh, enabled = state.state != UsageAccessState.LOADING) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Column(Modifier.padding(bottom = 12.dp)) {
                    ToolDropdown(
                        pluralStringResource(R.plurals.tool_unused_days, days, days),
                        UNUSED_DAYS.map { pluralStringResource(R.plurals.tool_unused_days, it, it) },
                    ) { onDays(UNUSED_DAYS[it]) }
                    when (state.state) {
                        UsageAccessState.LOADING -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }

                        UsageAccessState.NOT_GRANTED, UsageAccessState.REVOKED -> {
                            val message =
                                if (state.state == UsageAccessState.REVOKED) {
                                    R.string.tool_usage_revoked
                                } else {
                                    R.string.tool_usage_access
                                }
                            Text(stringResource(message))
                            PrimaryActionButton(onGrant) { Text(stringResource(R.string.tool_grant_access)) }
                        }

                        UsageAccessState.UNAVAILABLE -> {
                            Text(stringResource(R.string.tool_usage_unavailable))
                        }

                        UsageAccessState.EMPTY -> {
                            Text(stringResource(R.string.tool_no_apps))
                        }

                        UsageAccessState.RESULTS -> {
                            // Results are rendered by the app rows below.
                        }
                    }
                }
            }
            itemsIndexed(state.apps, key = { _, app -> app.packageName }) { index, app ->
                UnusedAppCard(app, onDetails, index == 0, index == state.apps.lastIndex)
            }
        }
    }
}

@Composable
private fun UnusedAppCard(
    app: com.emma.duplicates.data.UnusedApp,
    onDetails: (String) -> Unit,
    first: Boolean,
    last: Boolean,
) {
    val detailsLabel = stringResource(R.string.tool_app_details_for, app.label)
    Surface(
        Modifier.fillMaxWidth().clickable { onDetails(app.packageName) }.semantics {
            contentDescription =
                detailsLabel
        },
        shape = groupedRowShape(first, last),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                Modifier.heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InstalledAppIcon(app.packageName)
                Column(Modifier.weight(1f)) {
                    Text(
                        app.label,
                        Modifier.testTag("file-name-" + app.packageName),
                        style = CleanupRowTitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            app.sizeBytes?.let { DisplayFormatters.size(LocalResources.current, it) }
                                ?: stringResource(R.string.cleanup_unknown),
                            style = CleanupMetadata,
                        )
                    }
                    Text(
                        stringResource(R.string.tool_last_used, DisplayFormatters.date(app.lastUsed)),
                        style = CleanupMetadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(24.dp))
            }
            if (!last) HorizontalDivider(Modifier.padding(start = 76.dp, end = 16.dp))
        }
    }
}

@Composable
private fun InstalledAppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, packageName) {
        value = loadInstalledAppIcon(context, packageName)
    }
    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.size(48.dp)) }
        ?: Icon(painterResource(R.drawable.ic_apps), null, Modifier.size(48.dp))
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
