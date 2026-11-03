package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.ExclusionType
import com.emma.duplicates.core.model.FileCategory
import com.emma.duplicates.core.model.FileMetadata
import com.emma.duplicates.core.model.ScanExclusion
import com.emma.duplicates.core.model.StorageVolumeRoot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.UUID

data class PlatformFileMetadata(
    val mimeType: String? = null,
    val contentUri: String? = null,
    val isFavorite: Boolean? = null,
    val isTrashed: Boolean? = null,
    val relativePath: String? = null,
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
    val includedPaths: Set<String> = emptySet(),
    val includeZeroByteFiles: Boolean = false,
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
    private val metadataReader: FileMetadataReader = defaultFileMetadataReader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun discover(
        roots: List<StorageVolumeRoot>,
        options: FileDiscoveryOptions = FileDiscoveryOptions(),
        onProgress: suspend (FileDiscoveryProgress) -> Unit = {},
    ): FileDiscoveryResult =
        withContext(ioDispatcher) {
            Traversal(options).discover(roots, onProgress)
        }

    private inner class Traversal(
        private val options: FileDiscoveryOptions,
    ) {
        private val files = mutableListOf<FileMetadata>()
        private val visitedDirectories = mutableSetOf<String>()
        private val queue = ArrayDeque<PendingEntry>()
        private val entryPolicy = FileEntryPolicy(options)
        private val includedPaths = options.includedPaths.map(::normalizedPath)
        private var skippedFileCount = 0
        private var errorCount = 0
        private var visitedEntryCount = 0
        private var scannedByteCount = 0L

        suspend fun discover(
            roots: List<StorageVolumeRoot>,
            onProgress: suspend (FileDiscoveryProgress) -> Unit,
        ): FileDiscoveryResult {
            roots
                .sortedWith(compareBy(StorageVolumeRoot::id, { it.directory.absolutePath }))
                .forEach { queue.addLast(PendingEntry(it.directory, it)) }

            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val pending = queue.removeFirst()
                visitedEntryCount += 1
                try {
                    discoverEntry(pending)
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

            return FileDiscoveryResult(
                files = files.sortedWith(compareBy(FileMetadata::storageVolume, FileMetadata::canonicalPath)),
                skippedFileCount = skippedFileCount,
                errorCount = errorCount,
            )
        }

        private suspend fun discoverEntry(pending: PendingEntry) {
            val path =
                pending.file
                    .toPath()
                    .toAbsolutePath()
                    .normalize()
            val included = includedPaths.isEmpty() || includedPaths.any { path.startsWith(it) || it.startsWith(path) }
            val attributes = pending.file.takeIf { included }?.let(::readAttributes) ?: return
            if (attributes.isSymbolicLink) {
                if (attributes.isRegularFile) skippedFileCount += 1
            } else {
                val canonicalFile = canonicalFile(pending.file, attributes) ?: return
                when {
                    entryPolicy.excludes(canonicalFile) -> {
                        if (attributes.isRegularFile) skippedFileCount += 1
                    }

                    attributes.isDirectory -> {
                        discoverDirectory(canonicalFile, pending.root)
                    }

                    attributes.isRegularFile -> {
                        discoverFile(canonicalFile, pending.root, attributes)
                    }
                }
            }
        }

        private fun readAttributes(file: File): BasicFileAttributes? =
            try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: SecurityException) {
                skippedFileCount += 1
                errorCount += 1
                null
            } catch (_: IOException) {
                skippedFileCount += 1
                errorCount += 1
                null
            }

        private fun canonicalFile(
            file: File,
            attributes: BasicFileAttributes,
        ): File? =
            try {
                file.canonicalFile
            } catch (_: SecurityException) {
                if (attributes.isRegularFile) skippedFileCount += 1
                errorCount += 1
                null
            } catch (_: IOException) {
                if (attributes.isRegularFile) skippedFileCount += 1
                errorCount += 1
                null
            }

        private fun discoverDirectory(
            file: File,
            root: StorageVolumeRoot,
        ) {
            if (!visitedDirectories.add(file.path)) return
            val children =
                try {
                    file.listFiles()
                } catch (_: SecurityException) {
                    null
                }
            if (children == null) {
                errorCount += 1
            } else {
                children.sortedBy { it.name.lowercase() }.forEach { queue.addLast(PendingEntry(it, root)) }
            }
        }

        private suspend fun discoverFile(
            file: File,
            root: StorageVolumeRoot,
            attributes: BasicFileAttributes,
        ) {
            when {
                attributes.size() == 0L && !options.includeZeroByteFiles -> {
                    skippedFileCount += 1
                }

                !file.canRead() -> {
                    skippedFileCount += 1
                    errorCount += 1
                }

                else -> {
                    readMetadata(file)?.let { addFile(file, root, attributes, it) }
                }
            }
        }

        private suspend fun readMetadata(file: File): PlatformFileMetadata? =
            try {
                metadataReader.read(file)
            } catch (_: SecurityException) {
                skippedFileCount += 1
                errorCount += 1
                null
            } catch (_: IOException) {
                skippedFileCount += 1
                errorCount += 1
                null
            }

        private fun addFile(
            file: File,
            root: StorageVolumeRoot,
            attributes: BasicFileAttributes,
            metadata: PlatformFileMetadata,
        ) {
            if (metadata.isTrashed == true) {
                skippedFileCount += 1
                return
            }
            val extension = file.extension.lowercase()
            val category = categoryResolver.resolve(metadata.mimeType, extension)
            if (category !in options.enabledCategories) {
                skippedFileCount += 1
            } else {
                files +=
                    FileMetadata(
                        id = stableFileId(root.id, file.path),
                        canonicalPath = file.path,
                        displayName = file.name,
                        extension = extension,
                        mimeType = metadata.mimeType,
                        category = category,
                        sizeBytes = attributes.size(),
                        lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
                        storageVolume = root.id,
                        parentDirectory = file.parentFile?.path.orEmpty(),
                        isReadable = true,
                        isWritable = file.canWrite(),
                        contentUri = metadata.contentUri,
                        isFavorite = metadata.isFavorite,
                        isTrashed = metadata.isTrashed,
                        relativePath = metadata.relativePath,
                    )
                scannedByteCount += attributes.size()
            }
        }
    }

    private fun normalizedPath(path: String): Path = Paths.get(path).toAbsolutePath().normalize()

    private fun stableFileId(
        volume: String,
        canonicalPath: String,
    ): String = UUID.nameUUIDFromBytes("$volume\u0000$canonicalPath".toByteArray(Charsets.UTF_8)).toString()

    private data class PendingEntry(
        val file: File,
        val root: StorageVolumeRoot,
    )
}

private val defaultFileMetadataReader =
    FileMetadataReader { file ->
        PlatformFileMetadata(
            mimeType =
                Files.probeContentType(file.toPath())
                    ?: URLConnection.guessContentTypeFromName(file.name),
        )
    }
