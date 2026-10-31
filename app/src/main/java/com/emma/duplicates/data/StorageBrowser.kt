package com.emma.duplicates.data

import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.StorageVolumeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

data class StorageBrowserEntry(
    val displayName: String,
    val canonicalPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val isReadable: Boolean,
)

data class StorageBrowserLocation(
    val volumeId: String,
    val volumeLabel: String,
    val currentPath: String,
    val parentPath: String?,
    val entries: List<StorageBrowserEntry>,
)

class StorageBrowser(
    private val volumeSource: StorageVolumeSource,
    private val applicationDirectories: Set<String>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun mountedVolumes(): List<AvailableStorageVolume> = volumeSource.mountedVolumes()

    suspend fun open(
        volumeId: String,
        requestedPath: String? = null,
    ): StorageBrowserLocation? =
        withContext(ioDispatcher) {
            val volume = mountedVolumes().firstOrNull { it.id == volumeId } ?: return@withContext null
            val root = volume.directory.safeCanonicalFile() ?: return@withContext null
            val requested = requestedPath?.let(::File)?.safeCanonicalFile() ?: root
            if (!requested.toPath().startsWith(root.toPath()) || !requested.isDirectory || !requested.canRead()) {
                return@withContext null
            }
            val appPaths =
                applicationDirectories.mapNotNull { path ->
                    runCatching { Paths.get(path).toAbsolutePath().normalize() }.getOrNull()
                }
            val children =
                try {
                    requested.listFiles()
                } catch (_: SecurityException) {
                    null
                }
            val entries =
                children
                    ?.asSequence()
                    ?.mapNotNull { child -> child.toBrowserEntry(root.toPath(), appPaths) }
                    ?.sortedWith(
                        compareByDescending<StorageBrowserEntry> { it.isDirectory }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                            .thenBy { it.displayName },
                    )?.toList()
                    .orEmpty()
            StorageBrowserLocation(
                volumeId = volume.id,
                volumeLabel = volume.label,
                currentPath = requested.path,
                parentPath =
                    requested.parentFile
                        ?.safeCanonicalFile()
                        ?.takeIf { it.toPath().startsWith(root.toPath()) }
                        ?.path,
                entries = entries,
            )
        }

    private fun File.toBrowserEntry(
        root: Path,
        applicationPaths: List<Path>,
    ): StorageBrowserEntry? {
        val path = toPath()
        val attributes =
            path
                .safeAttributes()
                ?.takeIf { !it.isSymbolicLink && (it.isDirectory || it.isRegularFile) }
        val canonical =
            attributes?.let {
                safeCanonicalFile()?.takeIf { file ->
                    file.isBrowsable(root, applicationPaths)
                }
            }
        return if (attributes != null && canonical != null) {
            StorageBrowserEntry(
                displayName = canonical.name.ifBlank { canonical.path },
                canonicalPath = canonical.path,
                isDirectory = attributes.isDirectory,
                sizeBytes = if (attributes.isRegularFile) attributes.size() else 0L,
                isReadable = true,
            )
        } else {
            null
        }
    }

    private fun Path.safeAttributes(): BasicFileAttributes? =
        try {
            Files.readAttributes(this, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        }

    private fun File.isBrowsable(
        root: Path,
        applicationPaths: List<Path>,
    ): Boolean {
        val path = toPath()
        val outsideAllowedPaths = !path.startsWith(root) || applicationPaths.any(path::startsWith)
        val restricted = isDirectory && isRestrictedDirectory()
        return !outsideAllowedPaths && !restricted && canRead()
    }

    private fun File.safeCanonicalFile(): File? =
        try {
            canonicalFile
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        }

    private fun File.isRestrictedDirectory(): Boolean {
        if (name.lowercase() in RECYCLE_DIRECTORIES) return true
        return parentFile?.name?.equals("Android", ignoreCase = true) == true &&
            name.lowercase() in RESTRICTED_ANDROID_DIRECTORIES
    }

    private companion object {
        val RESTRICTED_ANDROID_DIRECTORIES = setOf("data", "obb")
        val RECYCLE_DIRECTORIES = setOf(".trash", ".trashes", ".trash-1000", "\$recycle.bin")
    }
}
