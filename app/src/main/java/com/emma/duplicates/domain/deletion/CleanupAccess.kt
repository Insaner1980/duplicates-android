package com.emma.duplicates.domain.deletion

import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.ExclusionTypes
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.core.storage.allows
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.nio.file.Paths

internal class CleanupAccess(
    private val database: DuplicatesDatabase,
    private val volumes: StorageVolumeSource,
    private val hasAccess: () -> Boolean,
) {
    fun scopeAvailable(scope: ToolScope): Boolean =
        try {
            val mounted = volumes.mountedVolumes()
            hasAccess() && scope.volumes.isNotEmpty() &&
                scope.volumes.all { captured ->
                    mounted.any {
                        it.id == captured.id && !it.isReadOnly && it.directory.canonicalPath == captured.path &&
                            it.directory.canRead()
                    }
                }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    suspend fun allows(
        item: ToolItemEntity,
        scope: ToolScope,
    ): Boolean {
        if (!scopeAvailable(scope) || !scope.allows(item.canonicalPath, item.volume)) return false
        val path = Paths.get(item.canonicalPath)
        val excluded =
            database.exclusionDao().getAll().any {
                if (it.type ==
                    ExclusionTypes.FOLDER
                ) {
                    path.startsWith(Paths.get(it.canonicalPath))
                } else {
                    path ==
                        Paths.get(it.canonicalPath)
                }
            }
        val protectedDirectory =
            item.directory && (
                (scope.volumes.map { it.path } + scope.includedPaths).any { Paths.get(it) == path } ||
                    database.recoveryDao().observeFiles().first().any {
                        Paths
                            .get(
                                it.originalPath,
                            ).startsWith(path)
                    }
            )
        return !excluded && !protectedDirectory
    }
}

internal suspend fun DuplicatesDatabase.cleanupIdle(): Boolean =
    toolDao().pending().isEmpty() && toolDao().running().isEmpty() &&
        deletionOperationDao().getPending().isEmpty() && scanDao().observeRunningSession().first() == null
