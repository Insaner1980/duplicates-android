package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.ExclusionType
import com.emma.duplicates.core.model.FileCategory
import com.emma.duplicates.core.model.FileMetadata
import com.emma.duplicates.core.model.ScanExclusion
import com.emma.duplicates.core.model.StorageVolumeRoot
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PlatformFileMetadata(
    val mimeType: String? = null,
    val contentUri: String? = null,
    val isFavorite: Boolean? = null,
    val isTrashed: Boolean? = null,
)

fun interface FileMetadataReader {
    suspend fun read(file: File): PlatformFileMetadata
}

data class FileDiscoveryOptions(
    val scanHiddenFolders: Boolean = false,
    val ignoreSystemFolders: Boolean = true,
    val enabledCategories: Set<FileCategory> = FileCategory.entries.toSet(),
    val exclusions: Set<ScanExclusion> = emptySet(),
    val applicationDirectories: Set<String> = emptySet(),
)

data class FileDiscoveryProgress(
    val visitedEntryCount: Int,
    val currentPath: String,
    val discoveredFileCount: Int = 0,
    val scannedByteCount: Long = 0,
)

data class FileDiscoveryResult(
    val files: List<FileMetadata>,
    val skippedFileCount: Int,
    val errorCount: Int,
)

class FileDiscovery(
    private val categoryResolver: FileCategoryResolver = FileCategoryResolver(),
    private val metadataReader: FileMetadataReader = DefaultFileMetadataReader,
) {
    suspend fun discover(
        roots: List<StorageVolumeRoot>,
        options: FileDiscoveryOptions = FileDiscoveryOptions(),
        onProgress: suspend (FileDiscoveryProgress) -> Unit = {},
    ): FileDiscoveryResult = withContext(Dispatchers.IO) {
        val files = mutableListOf<FileMetadata>()
        val visitedDirectories = mutableSetOf<String>()
        val queue = ArrayDeque<PendingEntry>()
        val exclusions = options.exclusions.map(::normalizedExclusion)
        val applicationDirectories = options.applicationDirectories.map(::normalizedPath)
        var skippedFileCount = 0
        var errorCount = 0
        var visitedEntryCount = 0
        var scannedByteCount = 0L

        roots.sortedWith(compareBy(StorageVolumeRoot::id, { it.directory.absolutePath }))
            .forEach { queue.addLast(PendingEntry(it.directory, it)) }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val pending = queue.removeFirst()
            visitedEntryCount += 1
            try {
                val path = pending.file.toPath()
                val attributes = try {
                    Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (_: NoSuchFileException) {
                    skippedFileCount += 1
                    errorCount += 1
                    continue
                } catch (_: FileNotFoundException) {
                    skippedFileCount += 1
                    errorCount += 1
                    continue
                } catch (_: SecurityException) {
                    skippedFileCount += 1
                    errorCount += 1
                    continue
                } catch (_: IOException) {
                    skippedFileCount += 1
                    errorCount += 1
                    continue
                }

                if (attributes.isSymbolicLink) {
                    if (attributes.isRegularFile) skippedFileCount += 1
                    continue
                }

                val canonicalFile = try {
                    pending.file.canonicalFile
                } catch (_: SecurityException) {
                    if (attributes.isRegularFile) skippedFileCount += 1
                    errorCount += 1
                    continue
                } catch (_: IOException) {
                    if (attributes.isRegularFile) skippedFileCount += 1
                    errorCount += 1
                    continue
                }
                val canonicalPath = canonicalFile.path

                if (shouldSkipEntry(canonicalFile, canonicalPath, options, exclusions, applicationDirectories)) {
                    if (attributes.isRegularFile) skippedFileCount += 1
                    continue
                }

                when {
                    attributes.isDirectory -> {
                        if (!visitedDirectories.add(canonicalPath)) continue
                        val children = try {
                            canonicalFile.listFiles()
                        } catch (_: SecurityException) {
                            null
                        }
                        if (children == null) {
                            errorCount += 1
                            continue
                        }
                        children.sortedBy { it.name.lowercase() }
                            .forEach { queue.addLast(PendingEntry(it, pending.root)) }
                    }

                    attributes.isRegularFile -> {
                        if (attributes.size() == 0L) {
                            skippedFileCount += 1
                            continue
                        }
                        if (!canonicalFile.canRead()) {
                            skippedFileCount += 1
                            errorCount += 1
                            continue
                        }

                        val platformMetadata = try {
                            metadataReader.read(canonicalFile)
                        } catch (_: FileNotFoundException) {
                            skippedFileCount += 1
                            errorCount += 1
                            continue
                        } catch (_: NoSuchFileException) {
                            skippedFileCount += 1
                            errorCount += 1
                            continue
                        } catch (_: SecurityException) {
                            skippedFileCount += 1
                            errorCount += 1
                            continue
                        } catch (_: IOException) {
                            skippedFileCount += 1
                            errorCount += 1
                            continue
                        }
                        if (platformMetadata.isTrashed == true) {
                            skippedFileCount += 1
                            continue
                        }

                        val extension = canonicalFile.extension.lowercase()
                        val category = categoryResolver.resolve(platformMetadata.mimeType, extension)
                        if (category !in options.enabledCategories) {
                            skippedFileCount += 1
                            continue
                        }

                        files += FileMetadata(
                            id = stableFileId(pending.root.id, canonicalPath),
                            canonicalPath = canonicalPath,
                            displayName = canonicalFile.name,
                            extension = extension,
                            mimeType = platformMetadata.mimeType,
                            category = category,
                            sizeBytes = attributes.size(),
                            lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
                            storageVolume = pending.root.id,
                            parentDirectory = canonicalFile.parentFile?.path.orEmpty(),
                            isReadable = true,
                            isWritable = canonicalFile.canWrite(),
                            contentUri = platformMetadata.contentUri,
                            isFavorite = platformMetadata.isFavorite,
                            isTrashed = platformMetadata.isTrashed,
                        )
                        scannedByteCount += attributes.size()
                    }

                    else -> Unit
                }
            } finally {
                onProgress(
                    FileDiscoveryProgress(
                        visitedEntryCount = visitedEntryCount,
                        currentPath = pending.file.absolutePath,
                        discoveredFileCount = files.size,
                        scannedByteCount = scannedByteCount,
                    ),
                )
                currentCoroutineContext().ensureActive()
            }
        }

        FileDiscoveryResult(
            files = files.sortedWith(compareBy(FileMetadata::storageVolume, FileMetadata::canonicalPath)),
            skippedFileCount = skippedFileCount,
            errorCount = errorCount,
        )
    }

    private fun shouldSkipEntry(
        file: File,
        canonicalPath: String,
        options: FileDiscoveryOptions,
        exclusions: List<NormalizedExclusion>,
        applicationDirectories: List<Path>,
    ): Boolean {
        if (!options.scanHiddenFolders && file.name.startsWith('.')) return true
        if (file.isDirectory && isRecycleDirectoryName(file.name)) return true
        if (file.isDirectory && isRestrictedAndroidDirectory(file)) return true
        if (file.isDirectory && options.ignoreSystemFolders && file.name.lowercase() in SYSTEM_DIRECTORY_NAMES) {
            return true
        }

        val path = normalizedPath(canonicalPath)
        if (applicationDirectories.any(path::startsWith)) return true
        return exclusions.any { exclusion ->
            when (exclusion.type) {
                ExclusionType.FILE -> path == exclusion.path
                ExclusionType.FOLDER -> path.startsWith(exclusion.path)
            }
        }
    }

    private fun normalizedExclusion(exclusion: ScanExclusion): NormalizedExclusion =
        NormalizedExclusion(normalizedPath(exclusion.canonicalPath), exclusion.type)

    private fun normalizedPath(path: String): Path = Paths.get(path).toAbsolutePath().normalize()

    private fun isRestrictedAndroidDirectory(file: File): Boolean =
        file.parentFile?.name?.equals("Android", ignoreCase = true) == true &&
            file.name.lowercase() in RESTRICTED_ANDROID_DIRECTORY_NAMES

    private fun isRecycleDirectoryName(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized in RECYCLE_DIRECTORY_NAMES || normalized.startsWith(".trash-")
    }

    private fun stableFileId(volume: String, canonicalPath: String): String =
        UUID.nameUUIDFromBytes("$volume\u0000$canonicalPath".toByteArray(Charsets.UTF_8)).toString()

    private data class PendingEntry(
        val file: File,
        val root: StorageVolumeRoot,
    )

    private data class NormalizedExclusion(
        val path: Path,
        val type: ExclusionType,
    )

    private companion object {
        val RESTRICTED_ANDROID_DIRECTORY_NAMES = setOf("data", "obb")
        val RECYCLE_DIRECTORY_NAMES = setOf(
            ".trash",
            ".trashes",
            "\$recycle.bin",
        )
        val SYSTEM_DIRECTORY_NAMES = setOf("cache", ".cache", "tmp", "temp", "lost.dir")
    }
}

private object DefaultFileMetadataReader : FileMetadataReader {
    override suspend fun read(file: File): PlatformFileMetadata = PlatformFileMetadata(
        mimeType = Files.probeContentType(file.toPath())
            ?: URLConnection.guessContentTypeFromName(file.name),
    )
}
