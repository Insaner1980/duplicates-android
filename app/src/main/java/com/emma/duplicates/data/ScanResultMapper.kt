package com.emma.duplicates.data

import com.emma.duplicates.core.database.DuplicateGroupEntity
import com.emma.duplicates.core.database.DuplicateMemberEntity
import com.emma.duplicates.core.database.IndexedFileEntity
import com.emma.duplicates.core.model.FileMetadata
import com.emma.duplicates.core.storage.ExactDuplicateScanResult
import com.emma.duplicates.domain.selection.DuplicateSelectionPolicy
import com.emma.duplicates.domain.selection.SelectionFile
import java.nio.charset.StandardCharsets
import java.util.UUID

data class PersistableScanResults(
    val files: List<IndexedFileEntity>,
    val groups: List<DuplicateGroupEntity>,
    val members: List<DuplicateMemberEntity>,
)

class ScanResultMapper(
    private val selectionPolicy: DuplicateSelectionPolicy = DuplicateSelectionPolicy(),
) {
    fun map(
        sessionId: String,
        discoveredFiles: List<FileMetadata>,
        scanResult: ExactDuplicateScanResult,
        autoSelect: Boolean,
    ): PersistableScanResults {
        val hashesByFileId =
            scanResult.groups
                .flatMap { it.members }
                .associateBy { it.file.id }
        val entityIdByFileId =
            discoveredFiles.associate { file ->
                file.id to stableId(sessionId, "file", file.storageVolume, file.canonicalPath)
            }
        val files =
            discoveredFiles.map { file ->
                val hashes = hashesByFileId[file.id]
                IndexedFileEntity(
                    id = entityIdByFileId.getValue(file.id),
                    sessionId = sessionId,
                    canonicalPath = file.canonicalPath,
                    displayName = file.displayName,
                    extension = file.extension,
                    mimeType = file.mimeType,
                    category = file.category.name,
                    sizeBytes = file.sizeBytes,
                    lastModified = file.lastModifiedMillis,
                    volume = file.storageVolume,
                    parentPath = file.parentDirectory,
                    contentUri = file.contentUri,
                    quickHash = hashes?.quickSha256,
                    fullHash = hashes?.fullSha256,
                    readable = file.isReadable,
                    writable = file.isWritable,
                    favorite = file.isFavorite,
                    trashed = file.isTrashed,
                )
            }

        val groups = mutableListOf<DuplicateGroupEntity>()
        val members = mutableListOf<DuplicateMemberEntity>()
        scanResult.groups.forEach { group ->
            val groupId = stableId(sessionId, "group", group.id)
            val selection =
                selectionPolicy.createSelection(
                    groupId = groupId,
                    files =
                        group.members.map { member ->
                            SelectionFile(
                                id = entityIdByFileId.getValue(member.file.id),
                                canonicalPath = member.file.canonicalPath,
                                sizeBytes = member.file.sizeBytes,
                                lastModifiedMillis = member.file.lastModifiedMillis,
                                isFavorite = member.file.isFavorite == true,
                                isReadable = member.file.isReadable,
                                isWritable = member.file.isWritable,
                            )
                        },
                    autoSelect = autoSelect,
                )
            val selectedByFileId = selection.members.associateBy { it.file.id }
            groups +=
                DuplicateGroupEntity(
                    id = groupId,
                    sessionId = sessionId,
                    category = group.category.name,
                    contentHash = group.contentHash,
                    fileSize = group.sizeBytes,
                    copyCount = group.copyCount,
                    reclaimableBytes = selection.selectedReclaimableBytes,
                    displayTitle =
                        group.members.minBy { it.file.canonicalPath }.file.displayName,
                )
            group.members.forEach { member ->
                val indexedFileId = entityIdByFileId.getValue(member.file.id)
                val selected = selectedByFileId.getValue(indexedFileId)
                members +=
                    DuplicateMemberEntity(
                        id = indexedFileId,
                        groupId = groupId,
                        indexedFileId = indexedFileId,
                        recommendedKeep = selected.recommendedKeep,
                        selectedForDeletion = selected.selectedForDeletion,
                        protectedFromAutoSelection = selected.protectedFromAutoSelection,
                    )
            }
        }

        return PersistableScanResults(files = files, groups = groups, members = members)
    }

    private fun stableId(vararg parts: String): String =
        UUID.nameUUIDFromBytes(parts.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)).toString()
}
