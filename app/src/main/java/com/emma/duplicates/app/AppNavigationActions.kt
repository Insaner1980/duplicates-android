package com.emma.duplicates.app

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.ui.tools.RootTab

internal fun NavHostController.openTool(tool: CleanupTool) {
    if (tool == CleanupTool.DUPLICATES) {
        navigate(DuplicateSetupRoute) { launchSingleTop = true }
    } else {
        navigate(ToolRoute(tool.name)) { launchSingleTop = true }
    }
}

internal fun NavHostController.selectRootTab(tab: RootTab) {
    val route: Any =
        when (tab) {
            RootTab.HOME -> HomeRoute
            RootTab.RECOVERY -> RecoveryRoute
        }
    navigate(route) {
        popUpTo<HomeRoute> { saveState = true }
        launchSingleTop = true
        restoreState = tab == RootTab.RECOVERY
    }
}

internal fun NavHostController.returnHome() {
    if (!popBackStack<HomeRoute>(inclusive = false)) {
        navigate(HomeRoute) {
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

internal fun NavHostController.navigateToResults(filter: String? = null) {
    val replacesScanning = currentDestination?.hasRoute<ScanningRoute>() == true
    navigate(ResultsRoute(filter)) {
        if (replacesScanning) popUpTo<ScanningRoute> { inclusive = true }
        launchSingleTop = true
    }
}

internal fun NavController.openScanning(request: ScanNavigationRequest = ScanNavigationRequest()) {
    navigate(ScanningRoute(request.baselineSessionId, request.awaitNewSession)) { launchSingleTop = true }
}
