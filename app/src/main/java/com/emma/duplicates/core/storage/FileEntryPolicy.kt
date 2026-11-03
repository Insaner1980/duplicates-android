package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.ExclusionType
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/** The same exclusions apply to file discovery, empty trees, and final cleanup validation. */
internal class FileEntryPolicy(
    private val options: FileDiscoveryOptions,
) {
    private val exclusions =
        options.exclusions.map {
            Paths.get(it.canonicalPath).toAbsolutePath().normalize() to
                it.type
        }
    private val applicationPaths = options.applicationDirectories.map { Paths.get(it).toAbsolutePath().normalize() }

    fun excludes(file: File): Boolean {
        val name = file.name.lowercase()
        val hidden = !options.scanHiddenFolders && name.startsWith('.')
        val restrictedDirectory = file.isDirectory && restrictedDirectory(file, name)
        val path = file.toPath().toAbsolutePath().normalize()
        return hidden || restrictedDirectory || applicationPaths.any(path::startsWith) ||
            exclusions.any { (excluded, type) ->
                if (type == ExclusionType.FOLDER) path.startsWith(excluded) else path == excluded
            }
    }

    private fun restrictedDirectory(
        file: File,
        name: String,
    ): Boolean {
        val privateAndroid = file.parentFile?.name.equals("Android", true) && name in setOf("data", "obb")
        val ignoredSystem = options.ignoreSystemFolders && name in SYSTEM_DIRECTORIES
        return name in RECYCLE_DIRECTORIES || name.startsWith(".trash-") || privateAndroid || ignoredSystem
    }

    fun excludesWithin(
        file: File,
        root: Path,
    ): Boolean = generateSequence(file) { it.parentFile }.takeWhile { it.toPath().startsWith(root) }.any(::excludes)

    private companion object {
        val RECYCLE_DIRECTORIES = setOf(".trash", ".trashes", "\$recycle.bin")
        val SYSTEM_DIRECTORIES = setOf("cache", ".cache", "tmp", "temp", "lost.dir")
    }
}
