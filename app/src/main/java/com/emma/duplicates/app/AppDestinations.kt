package com.emma.duplicates.app

import android.content.Context
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
import androidx.navigation.NavBackStackEntry
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
import kotlinx.coroutines.flow.Flow

@Composable
internal fun HomeDestination(
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel,
    navController: NavHostController,
    onStartScan: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    scanStartPending: Boolean,
) {
    val state by homeViewModel.state.collectAsStateWithLifecycle()
    val configuration by homeViewModel.configuration.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onOpenSettings = { navController.navigate(SettingsRoute) },
        onGrantAccess = onOpenStorageSettings,
        onStartScan = onStartScan,
        onViewScan = { navController.openScanning() },
        onReviewDuplicates = {
            navController.navigateToResults()
        },
        configuration = configuration,
        scanStartPending = scanStartPending,
        onOpenScanLocations = { navController.navigate(ScanLocationsRoute) },
        onCategoryEnabledChange = settingsViewModel::setCategoryEnabled,
    )
}

@Composable
internal fun ResultsDestination(
    container: AppContainer,
    navController: NavHostController,
    onStartScan: () -> Unit,
    deletionAuthorizer: MediaDeletionAuthorizer,
    entry: NavBackStackEntry,
    resultsViewModel: ResultsViewModel =
        viewModel(
            viewModelStoreOwner = entry,
            factory =
                ViewModelFactory {
                    ResultsViewModel(
                        container.scanStore,
                        container.preferencesRepository,
                        container.deletionCoordinator(deletionAuthorizer),
                        entry.savedStateHandle,
                    )
                },
        ),
) {
    val route = entry.toRoute<ResultsRoute>()
    val state by resultsViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (!entry.savedStateHandle.contains("filter")) {
            resultsViewModel.setFilter(
                ResultsFilter.entries.firstOrNull { it.name == route.filter } ?: ResultsFilter.ALL,
            )
        }
    }

    fun leaveResults() {
        if (state.busy) return
        if (state.searchVisible) resultsViewModel.setSearchVisible(false) else navController.returnHome()
    }
    BackHandler(enabled = state.busy || state.searchVisible, onBack = ::leaveResults)
    ResultsScreen(
        state = state,
        onSearchVisibilityChange = resultsViewModel::setSearchVisible,
        onSearchQueryChange = resultsViewModel::setSearchQuery,
        onFilterSelect = resultsViewModel::setFilter,
        onSortSelect = resultsViewModel::setSort,
        onOverflowVisibilityChange = resultsViewModel::setOverflowVisible,
        onSortVisibilityChange = resultsViewModel::setSortVisible,
        onPreviewFile = { groupId, fileId ->
            navController.navigate(PreviewRoute(groupId, fileId))
        },
        onScanAgain = onStartScan,
        onRequestClear = resultsViewModel::requestClear,
        onDismissClear = resultsViewModel::dismissClear,
        onConfirmClear = resultsViewModel::confirmClear,
        onBack = ::leaveResults,
        onMemberSelectionChange = resultsViewModel::changeSelection,
        onRequestDelete = resultsViewModel::requestDelete,
        onDismissDelete = resultsViewModel::dismissDelete,
        onConfirmDelete = resultsViewModel::confirmDelete,
        onDismissDeletionResult = resultsViewModel::dismissDeletionResult,
        onShowAll = resultsViewModel::showAll,
    )
}

@Composable
internal fun ExclusionsDestination(
    container: AppContainer,
    navController: NavHostController,
    entry: NavBackStackEntry,
    exclusionsViewModel: ExclusionsViewModel =
        viewModel(
            viewModelStoreOwner = entry,
            factory = ViewModelFactory { ExclusionsViewModel(container.exclusionRepository) },
        ),
) {
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
        onBack = { navController.popBackStack() },
    )
}

@Composable
internal fun ScanningDestination(
    container: AppContainer,
    navController: NavHostController,
    entry: NavBackStackEntry,
    context: Context = LocalContext.current,
    scanViewModel: ScanViewModel =
        viewModel(
            viewModelStoreOwner = entry,
            factory =
                ViewModelFactory {
                    ScanViewModel(
                        context.applicationContext,
                        container.scanStore,
                        container.scanScheduler,
                        container.storageVolumeSource,
                        savedStateHandle = entry.savedStateHandle,
                        workActive = container.scanScheduler.workActive,
                    )
                },
        ),
) {
    val state by scanViewModel.state.collectAsStateWithLifecycle()

    fun returnHome() {
        navController.returnHome()
    }
    val lifecycleState by entry.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(state.completionReady, state.sessionId, lifecycleState) {
        if (state.completionReady && lifecycleState == Lifecycle.State.RESUMED) navController.navigateToResults()
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

@Composable
internal fun PreviewDestination(
    container: AppContainer,
    navController: NavHostController,
    entry: NavBackStackEntry,
    previewViewModel: PreviewViewModel =
        viewModel(
            viewModelStoreOwner = entry,
            factory =
                ViewModelFactory {
                    val route = entry.toRoute<PreviewRoute>()
                    PreviewViewModel(
                        groupId = route.groupId,
                        fileId = route.fileId,
                        scanStore = container.scanStore,
                        previewLauncher = container.filePreviewLauncher,
                    )
                },
        ),
) {
    val state by previewViewModel.state.collectAsStateWithLifecycle()
    when (val previewState = state) {
        PreviewScreenState.Loading -> {
            LoadingScreen()
        }

        is PreviewScreenState.Photo -> {
            PhotoPreviewScreen(
                state = previewState.state,
                onBack = { navController.popBackStack() },
            )
        }

        is PreviewScreenState.Generic -> {
            GenericPreviewScreen(
                state = previewState.state,
                onBack = { navController.popBackStack() },
                onOpenExternally = previewViewModel::openExternally,
            )
        }
    }
}

@Composable
internal fun SettingsDestination(
    settingsViewModel: SettingsViewModel,
    navController: NavHostController,
    onOpenStorageSettings: () -> Unit,
) {
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
        onThemeModeChange = settingsViewModel::setThemeMode,
    )
}

@Composable
internal fun ScanLocationsDestination(
    container: AppContainer,
    navController: NavHostController,
    entry: NavBackStackEntry,
    selectionViewModel: ScanLocationsViewModel =
        viewModel(
            viewModelStoreOwner = entry,
            factory =
                ViewModelFactory {
                    ScanLocationsViewModel(
                        container.storageBrowser,
                        container.preferencesRepository,
                    )
                },
        ),
) {
    val state by selectionViewModel.state.collectAsStateWithLifecycle()
    val completed by selectionViewModel.completed.collectAsStateWithLifecycle()
    LaunchedEffect(completed) { if (completed) navController.popBackStack() }
    val back = {
        if (!state.saving && !selectionViewModel.navigateBack()) navController.popBackStack()
        Unit
    }
    BackHandler(onBack = back)
    com.emma.duplicates.ui.settings.ScanSelectionScreen(
        state = state,
        onBack = back,
        onModeChange = selectionViewModel::setSelectedItemsOnly,
        onOpenVolume = selectionViewModel::openVolume,
        onVolumeSelectionChange = selectionViewModel::setVolumeSelected,
        onOpenDirectory = selectionViewModel::openDirectory,
        onShowStorage = selectionViewModel::showStorage,
        onPathSelectionChange = selectionViewModel::setPathSelected,
        onDone = selectionViewModel::save,
    )
}

@Composable
internal fun TypesToScanDestination(
    settingsViewModel: SettingsViewModel,
    navController: NavHostController,
) {
    val context = LocalContext.current
    val state by settingsViewModel.typesToScanState.collectAsStateWithLifecycle()
    TypesToScanScreen(
        state = state,
        onBack = { navController.popBackStack() },
        onCategoryEnabledChange = settingsViewModel::setCategoryEnabled,
        onSelectionBlock = {
            Toast.makeText(context, R.string.select_at_least_one_type, Toast.LENGTH_SHORT).show()
        },
    )
}

@Composable
internal fun ExclusionBrowserDestination(
    container: AppContainer,
    navController: NavHostController,
    entry: NavBackStackEntry,
    browserViewModel: StorageBrowserViewModel =
        viewModel(
            viewModelStoreOwner = entry,
            factory =
                ViewModelFactory {
                    val route = entry.toRoute<ExclusionBrowserRoute>()
                    val kind =
                        ExclusionKind.entries.firstOrNull { it.name == route.kind } ?: ExclusionKind.FOLDER
                    StorageBrowserViewModel(
                        storageBrowser = container.storageBrowser,
                        exclusionRepository = container.exclusionRepository,
                        kind = kind,
                    )
                },
        ),
) {
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
