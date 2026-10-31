package com.emma.duplicates.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.emma.duplicates.R
import com.emma.duplicates.domain.deletion.MediaDeletionAuthorizer
import com.emma.duplicates.ui.components.RootDestination
import com.emma.duplicates.ui.components.RootNavigationBar
import com.emma.duplicates.ui.exclusions.ExclusionBrowserScreen
import com.emma.duplicates.ui.exclusions.ExclusionKind
import com.emma.duplicates.ui.exclusions.ExclusionsScreen
import com.emma.duplicates.ui.home.HomeScreen
import com.emma.duplicates.ui.preview.GenericPreviewScreen
import com.emma.duplicates.ui.preview.PhotoPreviewScreen
import com.emma.duplicates.ui.results.ResultsFilter
import com.emma.duplicates.ui.results.ResultsScreen
import com.emma.duplicates.ui.review.ReviewDuplicatesScreen
import com.emma.duplicates.ui.scan.ScanningScreen
import com.emma.duplicates.ui.settings.ScanLocationsScreen
import com.emma.duplicates.ui.settings.SettingsScreen
import com.emma.duplicates.ui.settings.TypesToScanScreen
import kotlinx.coroutines.flow.Flow

@Composable
fun AppNavigation(
    container: AppContainer,
    deletionAuthorizer: MediaDeletionAuthorizer,
    onStartScan: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    openScanningInitially: Boolean,
    openScanningRequests: Flow<Unit>,
) {
    val navController = rememberNavController()

    LaunchedEffect(navController, openScanningInitially) {
        if (openScanningInitially) navController.openScanning()
    }
    LaunchedEffect(navController, openScanningRequests) {
        openScanningRequests.collect { navController.openScanning() }
    }

    AppNavHost(
        container,
        deletionAuthorizer,
        navController,
        onStartScan,
        onOpenStorageSettings,
    )
}

@Composable
private fun AppNavHost(
    container: AppContainer,
    deletionAuthorizer: MediaDeletionAuthorizer,
    navController: NavHostController,
    onStartScan: () -> Unit,
    onOpenStorageSettings: () -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val rootDestination = backStackEntry?.destination?.rootDestination()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            rootDestination?.let { selected ->
                RootNavigationBar(
                    selectedDestination = selected,
                    onDestinationSelect = { navController.navigateToRoot(it) },
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
            Modifier.padding(contentPadding),
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
            HomeDestination(homeViewModel, navController, onStartScan, onOpenStorageSettings)
        }

        composable<ResultsRoute> { entry ->
            ResultsDestination(container, navController, onStartScan, entry)
        }

        composable<ExclusionsRoute> { entry ->
            ExclusionsDestination(container, navController, entry)
        }

        composable<ScanningRoute> { entry ->
            ScanningDestination(container, navController, entry)
        }

        composable<ReviewRoute> { entry ->
            ReviewDestination(container, navController, deletionAuthorizer, entry)
        }

        composable<PreviewRoute> { entry ->
            PreviewDestination(container, navController, entry)
        }

        composable<SettingsRoute> {
            SettingsDestination(settingsViewModel, navController, onOpenStorageSettings)
        }

        composable<ScanLocationsRoute> {
            ScanLocationsDestination(settingsViewModel, navController)
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

private fun NavDestination.rootDestination(): RootDestination? =
    when {
        route.matchesRoute(HomeRoute::class.qualifiedName) -> RootDestination.HOME
        route.matchesRoute(ResultsRoute::class.qualifiedName) -> RootDestination.RESULTS
        route.matchesRoute(ExclusionsRoute::class.qualifiedName) -> RootDestination.EXCLUSIONS
        else -> null
    }

private fun String?.matchesRoute(qualifiedName: String?): Boolean =
    qualifiedName != null && (this == qualifiedName || this?.startsWith("$qualifiedName?") == true)
