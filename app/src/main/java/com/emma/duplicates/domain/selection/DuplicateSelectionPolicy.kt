package com.emma.duplicates.domain.selection

import java.util.Locale

data class SelectionFile(
    val id: String,
    val canonicalPath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val isFavorite: Boolean = false,
    val protectedFromAutoSelection: Boolean = false,
    val isReadable: Boolean = true,
    val isWritable: Boolean = true,
    val isPresent: Boolean = true,
    val matchesGroup: Boolean = true,
) {
    val isValidCopy: Boolean
        get() = isPresent && isReadable && matchesGroup

    val isDeletionEligible: Boolean
        get() = isValidCopy && isWritable
}

data class SelectionMember(
    val file: SelectionFile,
    val recommendedKeep: Boolean,
    val selectedForDeletion: Boolean,
    val protectedFromAutoSelection: Boolean,
)

data class DuplicateSelection(
    val groupId: String,
    val members: List<SelectionMember>,
) {
    val recommendedKeepId: String?
        get() = members.firstOrNull { it.recommendedKeep }?.file?.id

    val selectedMemberIds: Set<String>
        get() = members.filterTo(mutableListOf()) { it.selectedForDeletion }.mapTo(linkedSetOf()) { it.file.id }

    val selectedReclaimableBytes: Long
        get() = members.sumOf { member ->
            if (member.selectedForDeletion && member.file.isDeletionEligible) member.file.sizeBytes else 0L
        }
}

enum class SelectionBlockReason {
    KEEP_AT_LEAST_ONE_COPY,
    NOT_ELIGIBLE,
}

sealed interface SelectionChange {
    val selection: DuplicateSelection

    data class Applied(
        override val selection: DuplicateSelection,
    ) : SelectionChange

    data class Blocked(
        override val selection: DuplicateSelection,
        val reason: SelectionBlockReason,
    ) : SelectionChange
}

class DuplicateSelectionPolicy {
    fun createSelection(
        groupId: String,
        files: List<SelectionFile>,
        autoSelect: Boolean,
    ): DuplicateSelection {
        require(files.map { it.id }.distinct().size == files.size) { "Selection file IDs must be unique" }

        val recommendedKeepId = files.minWithOrNull(recommendedKeepComparator)?.id
        val hasValidCopy = files.any { it.isValidCopy }
        return DuplicateSelection(
            groupId = groupId,
            members = files.map { file ->
                val protected = file.isFavorite || file.protectedFromAutoSelection
                SelectionMember(
                    file = file,
                    recommendedKeep = file.id == recommendedKeepId,
                    selectedForDeletion =
                        autoSelect &&
                            hasValidCopy &&
                            file.id != recommendedKeepId &&
                            !protected &&
                            file.isDeletionEligible,
                    protectedFromAutoSelection = protected,
                )
            },
        )
    }

    fun changeSelection(
        current: DuplicateSelection,
        memberId: String,
        selected: Boolean,
    ): SelectionChange {
        val target = current.members.firstOrNull { it.file.id == memberId }
            ?: return SelectionChange.Blocked(current, SelectionBlockReason.NOT_ELIGIBLE)
        if (target.selectedForDeletion == selected) return SelectionChange.Applied(current)
        if (selected && !target.file.isDeletionEligible) {
            return SelectionChange.Blocked(current, SelectionBlockReason.NOT_ELIGIBLE)
        }
        if (
            selected &&
            current.members.none { member ->
                member.file.id != memberId &&
                    !member.selectedForDeletion &&
                    member.file.isValidCopy
            }
        ) {
            return SelectionChange.Blocked(current, SelectionBlockReason.KEEP_AT_LEAST_ONE_COPY)
        }

        return SelectionChange.Applied(
            current.copy(
                members = current.members.map { member ->
                    if (member.file.id == memberId) member.copy(selectedForDeletion = selected) else member
                },
            ),
        )
    }

    private val recommendedKeepComparator =
        compareByDescending<SelectionFile> { it.isValidCopy }
            .thenByDescending { it.isFavorite }
            .thenByDescending { it.canonicalPath.isPrimaryUserLocation() }
            .thenBy { it.canonicalPath.isTemporaryOrSecondaryLocation() }
            .thenByDescending { it.isPresent && it.isReadable && it.isWritable }
            .thenBy { it.lastModifiedMillis.takeIf { timestamp -> timestamp > 0L } ?: Long.MAX_VALUE }
            .thenBy { it.canonicalPath.normalizedPath().length }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.canonicalPath.normalizedPath() }
            .thenBy { it.canonicalPath.normalizedPath() }
            .thenBy { it.id }
}

private fun String.normalizedPath(): String = replace('\\', '/')

private fun String.pathSegments(): List<String> =
    normalizedPath()
        .split('/')
        .filter { it.isNotEmpty() }
        .map { it.lowercase(Locale.ROOT) }

private fun String.isPrimaryUserLocation(): Boolean {
    val segments = pathSegments()
    if (segments.zipWithNext().any { (first, second) -> first == "dcim" && second == "camera" }) return true
    return segments.any { it in PRIMARY_USER_DIRECTORY_NAMES }
}

private fun String.isTemporaryOrSecondaryLocation(): Boolean =
    pathSegments().any { it in SECONDARY_DIRECTORY_NAMES }

private val PRIMARY_USER_DIRECTORY_NAMES = setOf(
    "pictures",
    "music",
    "movies",
    "documents",
)

private val SECONDARY_DIRECTORY_NAMES = setOf(
    "download",
    "downloads",
    "cache",
    "caches",
    ".cache",
    "tmp",
    "temp",
    "temporary",
    "whatsapp",
    "telegram",
    "signal",
    "messenger",
    "messages",
)
