package com.emma.duplicates.data

import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.StorageVolumeSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
) {
    fun mountedVolumes(): List<AvailableStorageVolume> = volumeSource.mountedVolumes()

    suspend fun open(
        volumeId: String,
        requestedPath: String? = null,
    ): StorageBrowserLocation? =
        withContext(Dispatchers.IO) {
            val volume = mountedVolumes().firstOrNull { it.id == volumeId } ?: return@withContext null
            val root = volume.directory.safeCanonicalFile() ?: return@withContext null
            val requested = requestedPath?.let(::File)?.safeCanonicalFile() ?: root
            if (!requested.toPath().startsWith(root.toPath()) || !requested.isDirectory || !requested.canRead()) {
                return@withContext null
            }
            val appPaths = applicationDirectories.mapNotNull { path ->
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
                    )
                    ?.toList()
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
            try {
                Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: SecurityException) {
                return null
            } catch (_: IOException) {
                return null
            }
        if (attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile)) return null
        val canonical = safeCanonicalFile() ?: return null
        val canonicalPath = canonical.toPath()
        if (!canonicalPath.startsWith(root) || applicationPaths.any(canonicalPath::startsWith)) return null
        if (canonical.isDirectory && canonical.isRestrictedDirectory()) return null
        if (!canonical.canRead()) return null
        return StorageBrowserEntry(
            displayName = canonical.name.ifBlank { canonical.path },
            canonicalPath = canonical.path,
            isDirectory = attributes.isDirectory,
            sizeBytes = if (attributes.isRegularFile) attributes.size() else 0L,
            isReadable = true,
        )
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
