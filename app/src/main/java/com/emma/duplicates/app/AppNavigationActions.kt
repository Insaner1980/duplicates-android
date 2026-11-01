package com.emma.duplicates.app

import androidx.navigation.NavController
import androidx.navigation.NavHostController

internal fun NavHostController.returnHome() {
    if (!popBackStack<HomeRoute>(inclusive = false)) {
        navigate(HomeRoute) {
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

internal fun NavHostController.navigateToResults(filter: String? = null) {
    navigate(ResultsRoute(filter)) {
        popUpTo<HomeRoute>()
        launchSingleTop = true
    }
}

internal fun NavController.openScanning(request: ScanNavigationRequest = ScanNavigationRequest()) {
    navigate(ScanningRoute(request.baselineSessionId, request.awaitNewSession)) { launchSingleTop = true }
}
