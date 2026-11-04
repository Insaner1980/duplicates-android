package com.emma.duplicates.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.emma.duplicates.R
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.domain.deletion.MediaDeletionAuthorizer
import com.emma.duplicates.ui.exclusions.ExclusionBrowserScreen
import com.emma.duplicates.ui.exclusions.ExclusionKind
import com.emma.duplicates.ui.exclusions.ExclusionsScreen
import com.emma.duplicates.ui.home.HomeScreen
import com.emma.duplicates.ui.preview.GenericPreviewScreen
import com.emma.duplicates.ui.preview.PhotoPreviewScreen
import com.emma.duplicates.ui.results.ResultsFilter
import com.emma.duplicates.ui.results.ResultsScreen
import com.emma.duplicates.ui.scan.ScanningScreen
import com.emma.duplicates.ui.settings.SettingsScreen
import com.emma.duplicates.ui.settings.TypesToScanScreen
import com.emma.duplicates.ui.tools.DashboardScreen
import com.emma.duplicates.ui.tools.RootNavigationBar
import com.emma.duplicates.ui.tools.RootTab
import kotlinx.coroutines.flow.Flow

@Composable
fun AppNavigation(
    container: AppContainer,
    deletionAuthorizer: MediaDeletionAuthorizer,
    onStartScan: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    openScanningInitially: Boolean,
    openScanningRequests: Flow<ScanNavigationRequest>,
    scanStartPending: Boolean = false,
    openToolInitially: ToolRoute? = null,
    openToolRequests: Flow<ToolRoute> = kotlinx.coroutines.flow.emptyFlow(),
) {
    val navController = rememberNavController()

    LaunchedEffect(navController, openScanningInitially) {
        if (openScanningInitially) navController.openScanning()
    }
    LaunchedEffect(navController, openScanningRequests) {
        openScanningRequests.collect { navController.openScanning(it) }
    }
    LaunchedEffect(navController, openToolInitially) {
        openToolInitially?.let { navController.navigate(it) { launchSingleTop = true } }
    }
    LaunchedEffect(navController, openToolRequests) {
        openToolRequests.collect { navController.navigate(it) { launchSingleTop = true } }
    }

    AppNavHost(
        container,
        deletionAuthorizer,
        navController,
        onStartScan,
        onOpenStorageSettings,
        scanStartPending,
    )
}

@Composable
private fun AppNavHost(
    container: AppContainer,
    deletionAuthorizer: MediaDeletionAuthorizer,
    navController: NavHostController,
    onStartScan: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    scanStartPending: Boolean,
) {
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val tab =
        when {
            destination?.hasRoute<RecoveryRoute>() == true -> RootTab.RECOVERY
            else -> RootTab.HOME
        }
    val showTabs =
        destination == null || destination.hasRoute<HomeRoute>() || destination.hasRoute<ToolsRoute>() ||
            destination.hasRoute<RecoveryRoute>() || destination.hasRoute<DuplicateSetupRoute>() ||
            destination.hasRoute<ResultsRoute>() || destination.hasRoute<ScanningRoute>() ||
            destination.hasRoute<ToolRoute>()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showTabs) {
                RootNavigationBar(
                    tab,
                    navController::selectRootTab,
                    emphasizeSelection =
                        destination?.hasRoute<HomeRoute>() == true ||
                            destination?.hasRoute<RecoveryRoute>() == true,
                )
            }
        },
    ) { contentPadding ->
        AppNavigationGraph(
            container,
            deletionAuthorizer,
            navController,
            onStartScan,
            onOpenStorageSettings,
            scanStartPending,
            Modifier.padding(contentPadding).consumeWindowInsets(contentPadding),
        )
    }
}

@Composable
private fun AppNavigationGraph(
    container: AppContainer,
    deletionAuthorizer: MediaDeletionAuthorizer,
    navController: NavHostController,
    onStartScan: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    scanStartPending: Boolean,
    modifier: Modifier = Modifier,
) {
    val homeViewModel = rememberHomeViewModel(container)
    val settingsViewModel = rememberSettingsViewModel(container)
    ObservePlatformState(homeViewModel, settingsViewModel)

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
    ) {
        composable<HomeRoute> {
            val state by homeViewModel.state.collectAsStateWithLifecycle()
            val configuration by homeViewModel.configuration.collectAsStateWithLifecycle()
            DashboardScreen(
                state,
                configuration,
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onGrantAccess = onOpenStorageSettings,
                onOpenTool = navController::openTool,
            )
        }

        toolDestinations(container, navController, deletionAuthorizer, onOpenStorageSettings)

        composable<DuplicateSetupRoute> {
            HomeDestination(
                homeViewModel,
                settingsViewModel,
                navController,
                onStartScan,
                onOpenStorageSettings,
                scanStartPending,
            )
        }

        composable<ResultsRoute> { entry ->
            ResultsDestination(container, navController, onStartScan, deletionAuthorizer, entry)
        }

        composable<ExclusionsRoute> { entry ->
            ExclusionsDestination(container, navController, entry)
        }

        composable<ScanningRoute> { entry ->
            ScanningDestination(container, navController, entry)
        }

        composable<PreviewRoute> { entry ->
            PreviewDestination(container, navController, entry)
        }

        composable<SettingsRoute> {
            SettingsDestination(settingsViewModel, navController, onOpenStorageSettings)
        }

        composable<ScanLocationsRoute> { entry ->
            ScanLocationsDestination(container, navController, entry)
        }

        composable<TypesToScanRoute> {
            TypesToScanDestination(settingsViewModel, navController)
        }

        composable<ExclusionBrowserRoute> { entry ->
            ExclusionBrowserDestination(container, navController, entry)
        }
    }
}

@Composable
private fun rememberHomeViewModel(container: AppContainer): HomeViewModel {
    val context = LocalContext.current
    return viewModel(
        factory =
            ViewModelFactory {
                HomeViewModel(
                    context = context.applicationContext,
                    storageAccessManager = container.storageAccessManager,
                    volumeSource = container.storageVolumeSource,
                    preferencesRepository = container.preferencesRepository,
                    scanStore = container.scanStore,
                )
            },
    )
}

@Composable
private fun rememberSettingsViewModel(container: AppContainer): SettingsViewModel {
    val context = LocalContext.current
    return viewModel(
        factory =
            ViewModelFactory {
                SettingsViewModel(
                    context = context.applicationContext,
                    storageAccessManager = container.storageAccessManager,
                    storageVolumeSource = container.storageVolumeSource,
                    preferencesRepository = container.preferencesRepository,
                )
            },
    )
}

@Composable
private fun ObservePlatformState(
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, homeViewModel, settingsViewModel) {
        fun refreshPlatformState() {
            homeViewModel.refreshPlatformState()
            settingsViewModel.refreshPlatformState()
        }
        refreshPlatformState()
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshPlatformState()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

internal fun androidx.navigation.NavGraphBuilder.toolDestinations(
    container: AppContainer,
    navController: NavHostController,
    deletionAuthorizer: MediaDeletionAuthorizer,
    onOpenStorageSettings: () -> Unit,
) {
    composable<RecoveryRoute> {
        RecoveryDestination(container)
    }
    composable<ToolsRoute> {
        LaunchedEffect(navController) { navController.returnHome() }
    }

    composable<ToolRoute> { entry ->
        ToolDestination(container, navController, entry, deletionAuthorizer, onOpenStorageSettings)
    }

    composable<ToolPreviewRoute> { entry ->
        ToolPreviewDestination(container, navController, entry)
    }
}
