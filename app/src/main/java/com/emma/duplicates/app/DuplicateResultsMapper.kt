package com.emma.duplicates.app

import com.emma.duplicates.core.database.DuplicateGroupWithMembers
import com.emma.duplicates.core.database.DuplicateMemberWithFile
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.DeletionGroup
import com.emma.duplicates.ui.model.UiFileCategory

internal fun DuplicateMemberWithFile.eligibleForDeletion(): Boolean =
    file.readable && file.writable && !file.changedSinceScan

internal fun availableBytes(group: DuplicateGroupWithMembers): Long {
    val valid = group.members.filter { it.file.readable && !it.file.changedSinceScan }
    if (valid.size < 2) return 0L
    val eligible = valid.filter { it.file.writable }
    val total = eligible.sumOf { it.file.sizeBytes.coerceAtLeast(0L) }
    return if (valid.any { !it.file.writable }) {
        total
    } else {
        (total - (eligible.minOfOrNull { it.file.sizeBytes.coerceAtLeast(0L) } ?: 0L)).coerceAtLeast(0L)
    }
}

internal fun toDeletionGroup(
    group: DuplicateGroupWithMembers,
    selectedMemberIds: Set<String>,
): DeletionGroup =
    DeletionGroup(
        id = group.group.id,
        expectedFullHash = group.group.contentHash,
        files =
            group.members.map { member ->
                DeletionFile(
                    id = member.file.id,
                    memberId = member.member.id,
                    canonicalPath = member.file.canonicalPath,
                    sizeBytes = member.file.sizeBytes,
                    lastModifiedMillis = member.file.lastModified,
                    contentUri = member.file.contentUri,
                    requiresMediaAuthorization =
                        member.file.contentUri != null &&
                            member.file.category.toUiCategory() != UiFileCategory.DOCUMENTS,
                    selectedForDeletion = member.member.id in selectedMemberIds,
                )
            },
    )

internal fun displayLocation(
    parentPath: String,
    volume: String,
): String {
    val path = parentPath.replace('\\', '/').trimEnd('/')
    return when {
        path == "/storage/emulated/0" -> ""
        path.startsWith("/storage/emulated/0/") -> path.removePrefix("/storage/emulated/0/").replace("/", " / ")
        path.startsWith("/storage/") -> path.removePrefix("/storage/").replace("/", " / ")
        else -> "$volume / ${path.trimStart('/').replace("/", " / ")}"
    }
}
