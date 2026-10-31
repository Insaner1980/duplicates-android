package com.emma.duplicates.data

import com.emma.duplicates.core.database.ExclusionEntity
import com.emma.duplicates.core.database.ExclusionTypes
import com.emma.duplicates.core.model.ExclusionType
import com.emma.duplicates.core.storage.StorageVolumeSource
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID

enum class AddExclusionResult {
    ADDED,
    ALREADY_EXCLUDED,
    PARENT_ALREADY_EXCLUDED,
    NOT_ACCESSIBLE,
}

class ExclusionRepository(
    private val scanStore: ScanStore,
    private val volumeSource: StorageVolumeSource,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    val exclusions: Flow<List<ExclusionEntity>> = scanStore.exclusions

    suspend fun add(
        path: String,
        type: ExclusionType,
    ): AddExclusionResult {
        val file = File(path).accessibleCanonicalFile(type) ?: return AddExclusionResult.NOT_ACCESSIBLE
        val candidatePath = file.toPath()
        val existing = scanStore.getExclusions()
        return when {
            existing.any { it.canonicalPath == file.path } -> AddExclusionResult.ALREADY_EXCLUDED
            existing.any { it.contains(candidatePath) } -> AddExclusionResult.PARENT_ALREADY_EXCLUDED
            else -> insert(file, type, existing)
        }
    }

    private suspend fun insert(
        file: File,
        type: ExclusionType,
        existing: List<ExclusionEntity>,
    ): AddExclusionResult {
        val candidatePath = file.toPath()
        if (type == ExclusionType.FOLDER) {
            existing
                .filter { exclusion ->
                    File(exclusion.canonicalPath).toPath().startsWith(candidatePath)
                }.forEach { scanStore.removeExclusion(it.id) }
        }
        val inserted =
            scanStore.addExclusion(
                ExclusionEntity(
                    id = stableId(file.path),
                    type = if (type == ExclusionType.FOLDER) ExclusionTypes.FOLDER else ExclusionTypes.FILE,
                    canonicalPath = file.path,
                    displayName = file.name.ifBlank { file.path },
                    createdAt = currentTimeMillis(),
                ),
            )
        return if (inserted) AddExclusionResult.ADDED else AddExclusionResult.ALREADY_EXCLUDED
    }

    private fun ExclusionEntity.contains(candidate: Path): Boolean =
        type == ExclusionTypes.FOLDER && candidate.startsWith(File(canonicalPath).toPath())

    private fun File.accessibleCanonicalFile(type: ExclusionType): File? {
        val canonical = safeCanonicalPath()?.toFile() ?: return null
        val matchesType =
            when (type) {
                ExclusionType.FOLDER -> canonical.isDirectory
                ExclusionType.FILE -> canonical.isFile
            }
        val roots = volumeSource.mountedVolumes().mapNotNull { it.directory.safeCanonicalPath() }
        return canonical.takeIf { matchesType && it.canRead() && roots.any(it.toPath()::startsWith) }
    }

    suspend fun remove(id: String) {
        scanStore.removeExclusion(id)
    }

    private fun File.safeCanonicalPath(): Path? =
        try {
            canonicalFile.toPath()
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        }

    private fun stableId(path: String): String =
        UUID.nameUUIDFromBytes(path.toByteArray(StandardCharsets.UTF_8)).toString()
}
