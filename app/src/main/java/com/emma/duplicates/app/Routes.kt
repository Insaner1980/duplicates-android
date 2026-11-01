package com.emma.duplicates.app

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data class ResultsRoute(
    val filter: String? = null,
)

@Serializable
data object ExclusionsRoute

@Serializable
data class ScanningRoute(
    val baselineSessionId: String? = null,
    val awaitNewSession: Boolean = false,
)

data class ScanNavigationRequest(
    val baselineSessionId: String? = null,
    val awaitNewSession: Boolean = false,
)

@Serializable
data class PreviewRoute(
    val groupId: String,
    val fileId: String,
)

@Serializable
data object SettingsRoute

@Serializable
data object ScanLocationsRoute

@Serializable
data object TypesToScanRoute

@Serializable
data class ExclusionBrowserRoute(
    val kind: String,
)
