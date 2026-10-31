package com.emma.duplicates.app

import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.emma.duplicates.ui.components.RootDestination

internal fun NavHostController.navigateToRoot(destination: RootDestination) {
    when (destination) {
        RootDestination.HOME -> {
            navigate(HomeRoute) {
                popUpTo<HomeRoute> { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        RootDestination.RESULTS -> {
            navigateToResults()
        }

        RootDestination.EXCLUSIONS -> {
            navigate(ExclusionsRoute) {
                popUpTo<HomeRoute> { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
}

internal fun NavHostController.navigateToResults(filter: String? = null) {
    navigate(ResultsRoute(filter)) {
        popUpTo<HomeRoute> { saveState = true }
        launchSingleTop = true
        restoreState = filter == null
    }
}

internal fun NavController.openScanning() {
    navigate(ScanningRoute) { launchSingleTop = true }
}
