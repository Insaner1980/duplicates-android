package com.emma.duplicates.core.storage

import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.model.ToolScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.ArrayDeque
import java.util.UUID

class EmptyFolderScanner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val listChildren: (File) -> Array<File>? = File::listFiles,
) {
    suspend fun scan(
        sessionId: String,
        scope: ToolScope,
        recoveryOriginalPaths: Set<String> = emptySet(),
        onProgress: suspend (FileDiscoveryProgress) -> Unit = {},
    ): ToolDiscoveryResult =
        withContext(ioDispatcher) {
            Traversal(sessionId, scope, recoveryOriginalPaths, onProgress).scan()
        }

    private inner class Traversal(
        private val sessionId: String,
        private val scope: ToolScope,
        recoveryOriginalPaths: Set<String>,
        private val onProgress: suspend (FileDiscoveryProgress) -> Unit,
    ) {
        private val found = mutableListOf<ToolItemEntity>()
        private val visited = mutableSetOf<String>()
        private val protected = (scope.volumes.map { it.path } + scope.includedPaths).map(Paths::get).toSet()
        private val originals = recoveryOriginalPaths.map(Paths::get)
        private var errors = 0
        private var count = 0

        suspend fun scan(): ToolDiscoveryResult {
            val anchors = scope.includedPaths.ifEmpty { scope.volumes.map { it.path } }
            for (path in anchors) {
                val anchor = File(path)
                val volume = scope.volumes.firstOrNull { anchor.toPath().startsWith(Paths.get(it.path)) }
                if (volume != null && scope.allows(path, volume.id) &&
                    Files.isDirectory(anchor.toPath(), LinkOption.NOFOLLOW_LINKS)
                ) {
                    scanTree(anchor, volume.id)
                }
            }
            return ToolDiscoveryResult(
                found.sortedWith(compareBy({ it.canonicalPath }, { it.volume })),
                errors == 0,
                errors,
            )
        }

        private suspend fun scanTree(
            anchor: File,
            volume: String,
        ) {
            val stack = ArrayDeque<DirectoryFrame>()
            stack.addLast(DirectoryFrame(anchor))
            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val frame = stack.last()
                if (!frame.inspected) inspect(frame, volume)
                val children = frame.children
                if (children != null && frame.nextChild < children.size) {
                    val child = children[frame.nextChild++]
                    if (Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                        scope.allows(child.path, volume)
                    ) {
                        stack.addLast(DirectoryFrame(child))
                    } else {
                        frame.empty = false
                    }
                } else {
                    stack.removeLast()
                    if (!frame.empty) stack.peekLast()?.empty = false
                    record(frame, volume)
                }
            }
        }

        private suspend fun inspect(
            frame: DirectoryFrame,
            volume: String,
        ) {
            frame.inspected = true
            count++
            onProgress(FileDiscoveryProgress(count, frame.file.path, found.size))
            if (!visited.add(frame.file.path) || !scope.allows(frame.file.path, volume)) {
                frame.empty = false
            } else {
                frame.children =
                    try {
                        listChildren(frame.file)
                    } catch (_: SecurityException) {
                        null
                    } catch (_: IOException) {
                        null
                    }
                if (frame.children == null) {
                    errors++
                    frame.empty = false
                }
            }
        }

        private fun record(
            frame: DirectoryFrame,
            volume: String,
        ) {
            val path = frame.file.toPath()
            if (frame.empty && path !in protected && originals.none { it.startsWith(path) }) {
                found +=
                    ToolItemEntity(
                        sessionId = sessionId,
                        id = UUID.nameUUIDFromBytes("$volume\u0000${frame.file.path}".toByteArray()).toString(),
                        volume = volume,
                        canonicalPath = frame.file.path,
                        name = frame.file.name,
                        sizeBytes = 0,
                        modifiedAt = frame.file.lastModified(),
                        category = "DOCUMENTS",
                        directory = true,
                    )
            }
        }
    }

    private class DirectoryFrame(
        val file: File,
    ) {
        var inspected = false
        var children: Array<File>? = null
        var nextChild = 0
        var empty = true
    }
}
