package com.emma.duplicates.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZoneId

enum class CleanupTool {
    DUPLICATES,
    EMPTY_FOLDERS,
    LARGE_FILES,
    SIMILAR_PHOTOS,
    SCREENSHOTS,
    OLD_DOWNLOADS,
    UNUSED_APPS,
    STORAGE_USAGE,
}

@Serializable
data class ToolVolume(
    val id: String,
    val path: String,
)

@Serializable
data class ToolExclusion(
    val path: String,
    val directory: Boolean,
)

@Serializable
data class ToolScope(
    val volumes: List<ToolVolume>,
    val includedPaths: List<String> = emptyList(),
    val exclusions: List<ToolExclusion> = emptyList(),
    val applicationDirectories: List<String> = emptyList(),
    val scanHiddenFolders: Boolean = false,
    val ignoreSystemFolders: Boolean = true,
    val zoneId: String = ZoneId.systemDefault().id,
    val minimumFileBytes: Long = 0,
    val maximumPhotoDistance: Int? = null,
    val maximumAspectDifference: Double? = null,
    val photoFingerprintVersion: Int? = null,
) {
    fun encode(): String = format.encodeToString(this)

    companion object {
        private val format = Json { encodeDefaults = true }

        fun decode(value: String): ToolScope = format.decodeFromString<ToolScope>(value)
    }
}
