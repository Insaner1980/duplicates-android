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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val homeViewModel: HomeViewModel =
        viewModel(
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
    val settingsViewModel: SettingsViewModel =
        viewModel(
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

    LaunchedEffect(navController, openScanningInitially) {
        if (openScanningInitially) navController.openScanning()
    }
    LaunchedEffect(navController, openScanningRequests) {
        openScanningRequests.collect { navController.openScanning() }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val rootDestination = backStackEntry?.destination?.rootDestination()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            rootDestination?.let { selected ->
                RootNavigationBar(
                    selectedDestination = selected,
                    onDestinationSelected = { navController.navigateToRoot(it) },
                )
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable<HomeRoute> {
                val state by homeViewModel.state.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                    onGrantAccess = onOpenStorageSettings,
                    onStartScan = onStartScan,
                    onViewScan = { navController.openScanning() },
                    onReviewDuplicates = {
                        navController.navigateToResults()
                    },
                    onCategorySelected = { category ->
                        navController.navigateToResults(category.name)
                    },
                    onScanAgain = onStartScan,
                )
            }

            composable<ResultsRoute> { entry ->
                val route = entry.toRoute<ResultsRoute>()
                val resultsViewModel: ResultsViewModel =
                    viewModel(
                        viewModelStoreOwner = entry,
                        factory = ViewModelFactory { ResultsViewModel(container.scanStore) },
                    )
                val state by resultsViewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(route.filter) {
                    resultsViewModel.setFilter(
                        ResultsFilter.entries.firstOrNull { it.name == route.filter } ?: ResultsFilter.ALL,
                    )
                }
                ResultsScreen(
                    state = state,
                    onSearchVisibilityChange = resultsViewModel::setSearchVisible,
                    onSearchQueryChange = resultsViewModel::setSearchQuery,
                    onFilterSelected = resultsViewModel::setFilter,
                    onSortSelected = resultsViewModel::setSort,
                    onOverflowVisibilityChange = resultsViewModel::setOverflowVisible,
                    onSortVisibilityChange = resultsViewModel::setSortVisible,
                    onPreviewFile = { groupId, fileId ->
                        navController.navigate(PreviewRoute(groupId, fileId))
                    },
                    onReviewGroup = { navController.navigate(ReviewRoute(it)) },
                    onScanAgain = onStartScan,
                    onRequestClear = resultsViewModel::requestClear,
                    onDismissClear = resultsViewModel::dismissClear,
                    onConfirmClear = resultsViewModel::confirmClear,
                )
            }

            composable<ExclusionsRoute> { entry ->
                val exclusionsViewModel: ExclusionsViewModel =
                    viewModel(
                        viewModelStoreOwner = entry,
                        factory = ViewModelFactory { ExclusionsViewModel(container.exclusionRepository) },
                    )
                val state by exclusionsViewModel.state.collectAsStateWithLifecycle()
                ExclusionsScreen(
                    state = state,
                    onRequestAdd = exclusionsViewModel::showAddSheet,
                    onDismissAddSheet = exclusionsViewModel::dismissAddSheet,
                    onChooseFolder = {
                        exclusionsViewModel.dismissAddSheet()
                        navController.navigate(ExclusionBrowserRoute(ExclusionKind.FOLDER.name))
                    },
                    onChooseFile = {
                        exclusionsViewModel.dismissAddSheet()
                        navController.navigate(ExclusionBrowserRoute(ExclusionKind.FILE.name))
                    },
                    onRemove = exclusionsViewModel::remove,
                )
            }

            composable<ScanningRoute> { entry ->
                val scanViewModel: ScanViewModel =
                    viewModel(
                        viewModelStoreOwner = entry,
                        factory =
                            ViewModelFactory {
                                ScanViewModel(
                                    context.applicationContext,
                                    container.scanStore,
                                    container.scanScheduler,
                                    container.storageVolumeSource,
                                )
                            },
                    )
                val state by scanViewModel.state.collectAsStateWithLifecycle()
                fun returnHome() {
                    navController.navigateToRoot(RootDestination.HOME)
                }
                BackHandler(onBack = ::returnHome)
                ScanningScreen(
                    state = state,
                    onBack = ::returnHome,
                    onRequestStop = scanViewModel::requestStop,
                    onDismissStop = scanViewModel::dismissStop,
                    onConfirmStop = scanViewModel::confirmStop,
                )
            }

            composable<ReviewRoute> { entry ->
                val route = entry.toRoute<ReviewRoute>()
                val reviewViewModel: ReviewViewModel =
                    viewModel(
                        viewModelStoreOwner = entry,
                        factory =
                            ViewModelFactory {
                                ReviewViewModel(
                                    groupId = route.groupId,
                                    scanStore = container.scanStore,
                                    preferencesRepository = container.preferencesRepository,
                                    deletionCoordinator = container.deletionCoordinator(deletionAuthorizer),
                                )
                            },
                    )
                val state by reviewViewModel.state.collectAsStateWithLifecycle()
                state?.let { reviewState ->
                    ReviewDuplicatesScreen(
                        state = reviewState,
                        onBack = { navController.popBackStack() },
                        onPreviewMember = { memberId ->
                            navController.navigate(PreviewRoute(route.groupId, memberId))
                        },
                        onMemberSelectionChange = reviewViewModel::changeSelection,
                        onSelectionBlocked = {
                            Toast.makeText(context, R.string.keep_at_least_one_copy, Toast.LENGTH_SHORT).show()
                        },
                        onRequestDelete = reviewViewModel::requestDelete,
                        onDismissDelete = reviewViewModel::dismissDelete,
                        onConfirmDelete = reviewViewModel::confirmDelete,
                        onDismissDeletionResult = {
                            reviewViewModel.dismissDeletionResult()
                            if (!reviewViewModel.groupStillExists()) navController.popBackStack()
                        },
                    )
                } ?: LoadingScreen()
            }

            composable<PreviewRoute> { entry ->
                val route = entry.toRoute<PreviewRoute>()
                val previewViewModel: PreviewViewModel =
                    viewModel(
                        viewModelStoreOwner = entry,
                        factory =
                            ViewModelFactory {
                                PreviewViewModel(
                                    groupId = route.groupId,
                                    fileId = route.fileId,
                                    scanStore = container.scanStore,
                                    previewLauncher = container.filePreviewLauncher,
                                )
                            },
                    )
                val state by previewViewModel.state.collectAsStateWithLifecycle()
                when (val previewState = state) {
                    PreviewScreenState.Loading -> LoadingScreen()
                    is PreviewScreenState.Photo ->
                        PhotoPreviewScreen(
                            state = previewState.state,
                            onBack = { navController.popBackStack() },
                        )
                    is PreviewScreenState.Generic ->
                        GenericPreviewScreen(
                            state = previewState.state,
                            onBack = { navController.popBackStack() },
                            onOpenExternally = previewViewModel::openExternally,
                        )
                }
            }

            composable<SettingsRoute> {
                val state by settingsViewModel.settingsState.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onOpenScanLocations = { navController.navigate(ScanLocationsRoute) },
                    onScanHiddenFoldersChange = settingsViewModel::setScanHiddenFolders,
                    onIgnoreSystemFoldersChange = settingsViewModel::setIgnoreSystemFolders,
                    onAutoSelectChange = settingsViewModel::setAutoSelectDuplicateCopies,
                    onConfirmBeforeDeleteChange = settingsViewModel::setConfirmBeforeDelete,
                    onOpenExclusions = { navController.navigate(ExclusionsRoute) },
                    onOpenFileTypes = { navController.navigate(TypesToScanRoute) },
                    onOpenStoragePermission = onOpenStorageSettings,
                )
            }

            composable<ScanLocationsRoute> {
                val state by settingsViewModel.scanLocationsState.collectAsStateWithLifecycle()
                ScanLocationsScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onLocationSelectionChange = settingsViewModel::setLocationSelected,
                    onSelectionBlocked = {
                        Toast.makeText(context, R.string.select_at_least_one_location, Toast.LENGTH_SHORT).show()
                    },
                )
            }

            composable<TypesToScanRoute> {
                val state by settingsViewModel.typesToScanState.collectAsStateWithLifecycle()
                TypesToScanScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onCategoryEnabledChange = settingsViewModel::setCategoryEnabled,
                    onSelectionBlocked = {
                        Toast.makeText(context, R.string.select_at_least_one_type, Toast.LENGTH_SHORT).show()
                    },
                )
            }

            composable<ExclusionBrowserRoute> { entry ->
                val route = entry.toRoute<ExclusionBrowserRoute>()
                val kind =
                    ExclusionKind.entries.firstOrNull { it.name == route.kind } ?: ExclusionKind.FOLDER
                val browserViewModel: StorageBrowserViewModel =
                    viewModel(
                        viewModelStoreOwner = entry,
                        factory =
                            ViewModelFactory {
                                StorageBrowserViewModel(
                                    storageBrowser = container.storageBrowser,
                                    exclusionRepository = container.exclusionRepository,
                                    kind = kind,
                                )
                            },
                    )
                val state by browserViewModel.state.collectAsStateWithLifecycle()
                val selectionCompleted by
                    browserViewModel.selectionCompleted.collectAsStateWithLifecycle()
                fun navigateBack() {
                    if (!browserViewModel.navigateBack()) navController.popBackStack()
                }
                BackHandler(onBack = ::navigateBack)
                LaunchedEffect(selectionCompleted) {
                    if (selectionCompleted) {
                        browserViewModel.consumeSelectionCompleted()
                        navController.popBackStack()
                    }
                }
                ExclusionBrowserScreen(
                    state = state,
                    onBack = ::navigateBack,
                    onOpenDirectory = browserViewModel::openDirectory,
                    onSelectFile = browserViewModel::selectFile,
                    onSelectCurrentFolder = browserViewModel::selectCurrentFolder,
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
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

private fun NavHostController.navigateToRoot(destination: RootDestination) {
    when (destination) {
        RootDestination.HOME ->
            navigate(HomeRoute) {
                popUpTo<HomeRoute> { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        RootDestination.RESULTS -> navigateToResults()
        RootDestination.EXCLUSIONS ->
            navigate(ExclusionsRoute) {
                popUpTo<HomeRoute> { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
    }
}

private fun NavHostController.navigateToResults(filter: String? = null) {
    navigate(ResultsRoute(filter)) {
        popUpTo<HomeRoute> { saveState = true }
        launchSingleTop = true
        restoreState = filter == null
    }
}

private fun NavController.openScanning() {
    navigate(ScanningRoute) { launchSingleTop = true }
}
