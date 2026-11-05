package com.emma.duplicates.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.emma.duplicates.R
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.domain.deletion.MediaDeletionAuthorizer
import com.emma.duplicates.ui.preview.GenericPreviewScreen
import com.emma.duplicates.ui.preview.PhotoPreviewScreen
import com.emma.duplicates.ui.tools.StorageUsageScreen
import com.emma.duplicates.ui.tools.ToolResultsScreen
import com.emma.duplicates.ui.tools.UnusedAppsScreen

@Composable
internal fun ToolDestination(
    container: AppContainer,
    nav: NavHostController,
    entry: NavBackStackEntry,
    authorizer: MediaDeletionAuthorizer,
    onGrantAccess: () -> Unit,
) {
    val route = entry.toRoute<ToolRoute>()
    val tool = CleanupTool.valueOf(route.tool)
    if (tool == CleanupTool.UNUSED_APPS) {
        UnusedAppsDestination(container, nav, entry)
        return
    }
    FileToolDestination(container, nav, entry, authorizer, onGrantAccess, tool)
}

@Composable
private fun FileToolDestination(
    container: AppContainer,
    nav: NavHostController,
    entry: NavBackStackEntry,
    authorizer: MediaDeletionAuthorizer,
    onGrantAccess: () -> Unit,
    tool: CleanupTool,
    model: ToolViewModel =
        viewModel(
            viewModelStoreOwner = entry,
            key = entry.toRoute<ToolRoute>().let { "tool:${it.tool}:${it.runId}" },
            factory =
                ViewModelFactory {
                    ToolViewModel(tool, entry.toRoute<ToolRoute>().runId, container, authorizer, entry.savedStateHandle)
                },
        ),
) {
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(model) {
        model.onResume()
        onPauseOrDispose {}
    }
    if (tool == CleanupTool.STORAGE_USAGE) {
        StorageUsageScreen(state, {
            nav.popBackStack()
        }, model::refresh, model::stop, onGrantAccess, { model.controls(state.controls.copy(folder = it)) })
    } else {
        var folderDetail by rememberSaveable { mutableStateOf<String?>(null) }
        ToolResultsScreen(
            state,
            { nav.popBackStack() },
            { model.refresh() },
            model::stop,
            onGrantAccess,
            model::controls,
            { id ->
                val item = state.results?.items?.firstOrNull { it.id == id }
                if (item?.directory == true) {
                    folderDetail = id
                } else {
                    item?.let { nav.navigate(ToolPreviewRoute(it.sessionId, it.id)) }
                }
            },
            model::requestCleanup,
            model::confirmCleanup,
            model::dismissConfirmation,
            model::dismissOutcome,
            model::selectVisible,
            model::keep,
        )
        state.results?.items?.firstOrNull { it.id == folderDetail }?.let { item ->
            AlertDialog(onDismissRequest = {
                folderDetail = null
            }, title = { Text(item.name) }, text = { Text(item.canonicalPath) }, confirmButton = {
                TextButton({ folderDetail = null }) { Text(stringResource(android.R.string.ok)) }
            })
        }
    }
}

@Composable
private fun UnusedAppsDestination(
    container: AppContainer,
    nav: NavHostController,
    entry: NavBackStackEntry,
    model: UnusedAppsViewModel =
        viewModel(
            viewModelStoreOwner = entry,
            factory =
                ViewModelFactory {
                    UnusedAppsViewModel(container.unusedAppsRepository, entry.savedStateHandle)
                },
        ),
) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val days by model.days.collectAsStateWithLifecycle()
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose {}
    }

    fun openSettings(packageName: String?) {
        try {
            if (packageName != null) {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            context.startActivity(
                Intent(
                    if (packageName ==
                        null
                    ) {
                        Settings.ACTION_USAGE_ACCESS_SETTINGS
                    } else {
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    },
                    "package:${packageName ?: context.packageName}".toUri(),
                ),
            )
        } catch (
            _: ActivityNotFoundException,
        ) {
            Toast.makeText(context, R.string.tool_app_unavailable, Toast.LENGTH_SHORT).show()
        } catch (_: PackageManager.NameNotFoundException) {
            model.refresh()
        } catch (
            _: SecurityException,
        ) {
            Toast.makeText(context, R.string.tool_app_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
    UnusedAppsScreen(state, days, {
        nav.popBackStack()
    }, model::days, model::refresh, { openSettings(null) }, ::openSettings)
}

@Composable
internal fun ToolPreviewDestination(
    container: AppContainer,
    nav: NavHostController,
    entry: NavBackStackEntry,
    model: ToolPreviewViewModel =
        viewModel(
            viewModelStoreOwner = entry,
            factory =
                ViewModelFactory {
                    ToolPreviewViewModel(
                        entry.toRoute<ToolPreviewRoute>().sessionId,
                        entry.toRoute<ToolPreviewRoute>().itemId,
                        container.toolResultStore,
                        container.filePreviewLauncher,
                    )
                },
        ),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val canKeep by model.canKeep.collectAsStateWithLifecycle()
    when (val current = state) {
        is PreviewScreenState.Photo -> {
            PhotoPreviewScreen(
                current.state,
                { nav.popBackStack() },
                onKeep =
                    if (canKeep) {
                        { model.keep() }
                    } else {
                        null
                    },
            )
        }

        is PreviewScreenState.Generic -> {
            GenericPreviewScreen(current.state, { nav.popBackStack() }, model::open)
        }

        PreviewScreenState.Loading -> {
            androidx.compose.material3.CircularProgressIndicator()
        }
    }
}
