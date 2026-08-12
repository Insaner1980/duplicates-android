package com.emma.duplicates.data

import com.emma.duplicates.core.database.ExclusionEntity
import com.emma.duplicates.core.database.ExclusionTypes
import com.emma.duplicates.core.model.ExclusionType
import com.emma.duplicates.core.storage.StorageVolumeSource
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.flow.Flow

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
        val file =
            try {
                File(path).canonicalFile
            } catch (_: SecurityException) {
                return AddExclusionResult.NOT_ACCESSIBLE
            } catch (_: IOException) {
                return AddExclusionResult.NOT_ACCESSIBLE
            }
        if (!file.canRead() || (type == ExclusionType.FOLDER && !file.isDirectory) ||
            (type == ExclusionType.FILE && !file.isFile)
        ) {
            return AddExclusionResult.NOT_ACCESSIBLE
        }
        val roots = volumeSource.mountedVolumes().mapNotNull { it.directory.safeCanonicalPath() }
        val candidatePath = file.toPath()
        if (roots.none(candidatePath::startsWith)) return AddExclusionResult.NOT_ACCESSIBLE

        val existing = scanStore.getExclusions()
        if (existing.any { it.canonicalPath == file.path }) {
            return AddExclusionResult.ALREADY_EXCLUDED
        }
        if (
            existing.any { exclusion ->
                exclusion.type == ExclusionTypes.FOLDER &&
                    candidatePath.startsWith(File(exclusion.canonicalPath).toPath())
            }
        ) {
            return AddExclusionResult.PARENT_ALREADY_EXCLUDED
        }

        if (type == ExclusionType.FOLDER) {
            existing.filter { exclusion ->
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
