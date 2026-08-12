package com.emma.duplicates.core.database

import androidx.room.Embedded
import androidx.room.Relation

data class DuplicateMemberWithFile(
    @Embedded val member: DuplicateMemberEntity,
    @Relation(
        parentColumn = "indexedFileId",
        entityColumn = "id",
    )
    val file: IndexedFileEntity,
)

data class DuplicateGroupWithMembers(
    @Embedded val group: DuplicateGroupEntity,
    @Relation(
        entity = DuplicateMemberEntity::class,
        parentColumn = "id",
        entityColumn = "groupId",
    )
    val members: List<DuplicateMemberWithFile>,
)

data class ScanSessionWithGroups(
    @Embedded val session: ScanSessionEntity,
    @Relation(
        entity = DuplicateGroupEntity::class,
        parentColumn = "id",
        entityColumn = "sessionId",
    )
    val groups: List<DuplicateGroupWithMembers>,
)

data class PendingDeletionOperationWithItems(
    @Embedded val operation: PendingDeletionOperationEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "operationId",
    )
    val items: List<PendingDeletionItemEntity>,
)

data class MemberSelectionState(
    val groupId: String,
    val selectedForDeletion: Boolean,
)
