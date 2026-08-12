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
data object ScanningRoute

@Serializable
data class ReviewRoute(
    val groupId: String,
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
