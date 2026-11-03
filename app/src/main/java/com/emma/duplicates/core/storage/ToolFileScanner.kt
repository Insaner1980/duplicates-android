package com.emma.duplicates.core.storage

import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ExclusionType
import com.emma.duplicates.core.model.FileCategory
import com.emma.duplicates.core.model.FileMetadata
import com.emma.duplicates.core.model.ScanExclusion
import com.emma.duplicates.core.model.StorageVolumeRoot
import com.emma.duplicates.core.model.ToolScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId

data class ToolDiscoveryResult(
    val items: List<ToolItemEntity>,
    val complete: Boolean = true,
    val errorCount: Int = 0,
    val skippedCount: Int = 0,
)

class ToolFileScanner(
    private val discovery: FileDiscovery,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun scan(
        sessionId: String,
        tool: CleanupTool,
        scope: ToolScope,
        onProgress: suspend (FileDiscoveryProgress) -> Unit = {},
    ): ToolDiscoveryResult =
        withContext(ioDispatcher) {
            val result =
                discovery.discover(
                    scope.volumes.map { StorageVolumeRoot(File(it.path), it.id) },
                    scope.discoveryOptions(),
                    onProgress,
                )
            val items =
                result.files
                    .filter { file ->
                        scope.allows(file.canonicalPath, file.storageVolume) &&
                            when (tool) {
                                CleanupTool.LARGE_FILES -> {
                                    file.sizeBytes > LARGE_FILE_MINIMUM
                                }

                                CleanupTool.SCREENSHOTS -> {
                                    file.category == FileCategory.PHOTOS &&
                                        scope.isScreenshot(file)
                                }

                                CleanupTool.OLD_DOWNLOADS -> {
                                    scope.relativeParts(file).firstOrNull()?.equals("Download", true) ==
                                        true
                                }

                                CleanupTool.SIMILAR_PHOTOS -> {
                                    file.category == FileCategory.PHOTOS
                                }

                                CleanupTool.STORAGE_USAGE -> {
                                    true
                                }

                                else -> {
                                    false
                                }
                            }
                    }.map { it.toToolItem(sessionId) }
            ToolDiscoveryResult(items, result.errorCount == 0, result.errorCount, result.skippedFileCount)
        }
}

internal fun ToolScope.discoveryOptions() =
    FileDiscoveryOptions(
        scanHiddenFolders = scanHiddenFolders,
        ignoreSystemFolders = ignoreSystemFolders,
        exclusions =
            exclusions
                .map {
                    ScanExclusion(it.path, if (it.directory) ExclusionType.FOLDER else ExclusionType.FILE)
                }.toSet(),
        applicationDirectories = applicationDirectories.toSet(),
        includedPaths = includedPaths.toSet(),
        includeZeroByteFiles = true,
    )

internal fun ToolScope.allows(
    path: String,
    volumeId: String,
): Boolean =
    try {
        val file = File(path)
        val normalized = file.toPath().toAbsolutePath().normalize()
        val root = volumes.firstOrNull { it.id == volumeId }?.let { Paths.get(it.path) }
        root != null && normalized.startsWith(root) && file.canonicalFile.toPath() == normalized &&
            !Files.isSymbolicLink(normalized) &&
            (includedPaths.isEmpty() || includedPaths.any { normalized.startsWith(Paths.get(it)) }) &&
            !FileEntryPolicy(discoveryOptions()).excludesWithin(file, root)
    } catch (_: java.io.IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun ToolScope.relativeParts(file: FileMetadata): List<String> {
    val root = volumes.first { it.id == file.storageVolume }
    return Paths.get(root.path).relativize(Paths.get(file.canonicalPath)).map { it.toString() }
}

private fun ToolScope.isScreenshot(file: FileMetadata): Boolean {
    val actualDirectories = relativeParts(file).dropLast(1)
    val validatedRelativePath =
        file.relativePath?.let { relative ->
            val parts = relative.trim('/').split('/').filter(String::isNotEmpty)
            parts.takeIf { it == actualDirectories && it.none { part -> part == "." || part == ".." } }
        }
    val directories = (validatedRelativePath ?: actualDirectories).map(String::lowercase)
    return directories.firstOrNull() in SCREENSHOT_FOLDERS ||
        (directories.firstOrNull() in setOf("pictures", "dcim") && directories.getOrNull(1) in SCREENSHOT_FOLDERS)
}

internal fun FileMetadata.toToolItem(sessionId: String) =
    ToolItemEntity(
        sessionId = sessionId,
        id = id,
        volume = storageVolume,
        canonicalPath = canonicalPath,
        name = displayName,
        sizeBytes = sizeBytes,
        modifiedAt = lastModifiedMillis,
        category = category.name,
        mimeType = mimeType,
        contentUri = contentUri,
    )

fun isOlderThanMonths(
    modifiedAt: Long,
    months: Int,
    now: Long,
    zone: ZoneId,
): Boolean =
    modifiedAt > 0 && modifiedAt <= now &&
        modifiedAt <
        Instant
            .ofEpochMilli(now)
            .atZone(zone)
            .minusMonths(months.toLong())
            .toInstant()
            .toEpochMilli()

private val SCREENSHOT_FOLDERS = setOf("screenshots", "screen captures", "screencapture", "screencaptures")
const val LARGE_FILE_MINIMUM = 100_000_000L
