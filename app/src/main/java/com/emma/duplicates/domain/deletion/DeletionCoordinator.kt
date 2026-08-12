package com.emma.duplicates.domain.deletion

class DeletionCoordinator(
    private val storage: DeletionStorage,
    private val mediaDeletionAuthorizer: MediaDeletionAuthorizer,
    private val resultStore: DeletionResultStore,
    private val mediaChunkSize: Int = 2_000,
) {
    init {
        require(mediaChunkSize > 0) { "Media deletion chunk size must be positive" }
    }

    suspend fun delete(groups: List<DeletionGroup>): DeletionResult {
        validateInput(groups)
        val selectedGroups = groups.filter { group -> group.files.any { it.selectedForDeletion } }
        val validations = selectedGroups.associate { group -> group.id to validateGroup(group) }
        val outcomesByFileId = linkedMapOf<String, FileDeletionOutcome>()
        val directCandidates = mutableListOf<Candidate>()
        val mediaCandidates = mutableListOf<Candidate>()

        selectedGroups.forEach { group ->
            val groupValidations = validations.getValue(group.id)
            val hasValidKeeper = groupValidations.any { validation ->
                !validation.file.selectedForDeletion &&
                    validation.membership == Membership.MATCHING &&
                    validation.snapshot?.isReadable == true
            }

            groupValidations.filter { it.file.selectedForDeletion }.forEach { validation ->
                val immediateStatus = validation.immediateDeletionStatus()
                when {
                    immediateStatus != null -> outcomesByFileId[validation.file.id] =
                        FileDeletionOutcome(group.id, validation.file.id, immediateStatus)

                    !hasValidKeeper -> outcomesByFileId[validation.file.id] =
                        FileDeletionOutcome(group.id, validation.file.id, DeletionStatus.BLOCKED_NO_VALID_COPY)

                    validation.file.requiresMediaAuthorization -> mediaCandidates += Candidate(group, validation)
                    else -> directCandidates += Candidate(group, validation)
                }
            }
        }

        val operationId = resultStore.beginDeletion(selectedGroups)
        suspend fun checkpoint() {
            resultStore.checkpointDeletion(
                operationId = operationId,
                update = buildDatabaseUpdate(selectedGroups, validations, outcomesByFileId.values.toList()),
            )
        }

        directCandidates.forEach { candidate ->
            outcomesByFileId[candidate.file.id] = deleteDirect(candidate)
            checkpoint()
        }
        deleteMedia(mediaCandidates, outcomesByFileId, ::checkpoint)

        val orderedOutcomes = selectedGroups.flatMap { group ->
            group.files.filter { it.selectedForDeletion }.map { file -> outcomesByFileId.getValue(file.id) }
        }
        val databaseUpdate = buildDatabaseUpdate(selectedGroups, validations, orderedOutcomes)
        resultStore.completeDeletion(operationId, databaseUpdate)
        return DeletionResult(orderedOutcomes.toList(), databaseUpdate)
    }

    private suspend fun validateGroup(group: DeletionGroup): List<Validation> {
        val initial = group.files.map { file ->
            val snapshot = storage.snapshot(file)
            when {
                snapshot == null -> Validation(file, null, Membership.MISSING)
                snapshot.sizeBytes == file.sizeBytes && snapshot.lastModifiedMillis == file.lastModifiedMillis ->
                    Validation(file, snapshot, Membership.MATCHING)
                !snapshot.isReadable -> Validation(file, snapshot, Membership.REVALIDATION_FAILED)
                else -> {
                    val fullHash = storage.fullHash(file)
                    when {
                        fullHash == null -> Validation(file, snapshot, Membership.REVALIDATION_FAILED)
                        fullHash != group.expectedFullHash -> Validation(file, snapshot, Membership.CHANGED)
                        else -> Validation(file, snapshot, Membership.HASH_MATCH_PENDING)
                    }
                }
            }
        }.toMutableList()

        val pendingIndices = initial.indices.filter { initial[it].membership == Membership.HASH_MATCH_PENDING }
        pendingIndices.forEach { index ->
            val pending = initial[index]
            val reference = initial
                .asSequence()
                .filterIndexed { candidateIndex, _ -> candidateIndex != index }
                .filter { candidate ->
                    candidate.snapshot?.isReadable == true &&
                        (candidate.membership == Membership.MATCHING || candidate.membership == Membership.HASH_MATCH_PENDING)
                }
                .sortedWith(
                    compareBy<Validation> { it.file.selectedForDeletion }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.canonicalPath }
                        .thenBy { it.file.canonicalPath },
                )
                .firstOrNull()
            initial[index] = when {
                reference == null -> pending.copy(membership = Membership.REVALIDATION_FAILED)
                storage.contentsEqual(pending.file, reference.file) -> pending.copy(membership = Membership.MATCHING)
                else -> pending.copy(membership = Membership.CHANGED)
            }
        }
        return initial.toList()
    }

    private suspend fun deleteDirect(candidate: Candidate): FileDeletionOutcome {
        val deletionResult = storage.deleteDirect(candidate.file)
        val afterDeletion = storage.snapshot(candidate.file)
        val status = when {
            afterDeletion == null && deletionResult == DirectDeletionResult.DELETED -> DeletionStatus.DELETED
            afterDeletion == null -> DeletionStatus.VANISHED
            else -> DeletionStatus.DELETE_FAILED
        }
        return FileDeletionOutcome(
            groupId = candidate.group.id,
            fileId = candidate.file.id,
            status = status,
            reclaimedBytes = if (status == DeletionStatus.DELETED) candidate.validation.snapshot!!.sizeBytes else 0L,
        )
    }

    private suspend fun deleteMedia(
        candidates: List<Candidate>,
        outcomesByFileId: MutableMap<String, FileDeletionOutcome>,
        checkpoint: suspend () -> Unit,
    ) {
        var stoppedStatus: DeletionStatus? = null
        for (chunk in candidates.chunked(mediaChunkSize)) {
            val alreadyStoppedStatus = stoppedStatus
            if (alreadyStoppedStatus != null) {
                chunk.forEach { candidate ->
                    outcomesByFileId[candidate.file.id] = candidate.outcome(alreadyStoppedStatus)
                }
                continue
            }

            when (mediaDeletionAuthorizer.requestDeletion(chunk.map { it.file })) {
                MediaAuthorization.APPROVED -> {
                    chunk.forEach { candidate ->
                        val status = if (storage.snapshot(candidate.file) == null) {
                            DeletionStatus.DELETED
                        } else {
                            DeletionStatus.DELETE_FAILED
                        }
                        outcomesByFileId[candidate.file.id] = candidate.outcome(
                            status = status,
                            reclaimedBytes =
                                if (status == DeletionStatus.DELETED) {
                                    candidate.validation.snapshot!!.sizeBytes
                                } else {
                                    0L
                                },
                        )
                    }
                    checkpoint()
                }

                MediaAuthorization.CANCELED -> {
                    chunk.forEach { candidate ->
                        outcomesByFileId[candidate.file.id] = candidate.outcome(DeletionStatus.AUTHORIZATION_CANCELED)
                    }
                    stoppedStatus = DeletionStatus.AUTHORIZATION_CANCELED
                }

                MediaAuthorization.FAILED -> {
                    chunk.forEach { candidate ->
                        outcomesByFileId[candidate.file.id] = candidate.outcome(DeletionStatus.DELETE_FAILED)
                    }
                    stoppedStatus = DeletionStatus.DELETE_FAILED
                }
            }
        }
    }

    private fun buildDatabaseUpdate(
        groups: List<DeletionGroup>,
        validations: Map<String, List<Validation>>,
        outcomes: List<FileDeletionOutcome>,
    ): DeletionDatabaseUpdate {
        val outcomesByFileId = outcomes.associateBy { it.fileId }
        val removedIndexedFileIds = linkedSetOf<String>()
        val removedMemberIds = linkedSetOf<String>()
        val changedIndexedFileIds = linkedSetOf<String>()
        val groupUpdates = groups.map { group ->
            val groupValidations = validations.getValue(group.id)
            groupValidations.forEach { validation ->
                when (validation.membership) {
                    Membership.MISSING -> {
                        removedIndexedFileIds += validation.file.id
                        removedMemberIds += validation.file.memberId
                    }

                    Membership.CHANGED, Membership.REVALIDATION_FAILED -> {
                        changedIndexedFileIds += validation.file.id
                        removedMemberIds += validation.file.memberId
                    }

                    Membership.MATCHING, Membership.HASH_MATCH_PENDING -> Unit
                }
                if (
                    outcomesByFileId[validation.file.id]?.status == DeletionStatus.DELETED ||
                    outcomesByFileId[validation.file.id]?.status == DeletionStatus.VANISHED
                ) {
                    removedIndexedFileIds += validation.file.id
                    removedMemberIds += validation.file.memberId
                }
            }

            val remaining = groupValidations.filter { validation ->
                validation.membership == Membership.MATCHING &&
                    outcomesByFileId[validation.file.id]?.status != DeletionStatus.DELETED &&
                    outcomesByFileId[validation.file.id]?.status != DeletionStatus.VANISHED
            }
            val hasValidUnselectedCopy = remaining.any { validation ->
                !validation.file.selectedForDeletion && validation.snapshot?.isReadable == true
            }
            val remainingReclaimableBytes = if (hasValidUnselectedCopy) {
                remaining.sumOf { validation ->
                    val snapshot = validation.snapshot
                    if (
                        validation.file.selectedForDeletion &&
                        snapshot != null &&
                        snapshot.isReadable &&
                        snapshot.isWritable
                    ) {
                        snapshot.sizeBytes
                    } else {
                        0L
                    }
                }
            } else {
                0L
            }
            GroupDeletionUpdate(
                groupId = group.id,
                remainingMemberIds = remaining.mapTo(linkedSetOf()) { it.file.memberId },
                remainingCopyCount = remaining.size,
                remainingSelectedReclaimableBytes = if (remaining.size >= 2) remainingReclaimableBytes else 0L,
                removeGroup = remaining.size < 2,
            )
        }
        return DeletionDatabaseUpdate(
            removedIndexedFileIds = removedIndexedFileIds.toSet(),
            removedMemberIds = removedMemberIds.toSet(),
            changedIndexedFileIds = changedIndexedFileIds.toSet(),
            groups = groupUpdates.toList(),
        )
    }

    private fun Validation.immediateDeletionStatus(): DeletionStatus? = when (membership) {
        Membership.MISSING -> DeletionStatus.VANISHED
        Membership.CHANGED -> DeletionStatus.CHANGED
        Membership.REVALIDATION_FAILED -> DeletionStatus.REVALIDATION_FAILED
        Membership.HASH_MATCH_PENDING -> DeletionStatus.REVALIDATION_FAILED
        Membership.MATCHING -> if (snapshot?.let { it.isReadable && it.isWritable } == true) {
            null
        } else {
            DeletionStatus.NOT_ACCESSIBLE
        }
    }

    private fun Candidate.outcome(
        status: DeletionStatus,
        reclaimedBytes: Long = 0L,
    ) = FileDeletionOutcome(group.id, file.id, status, reclaimedBytes)

    private fun validateInput(groups: List<DeletionGroup>) {
        require(groups.map { it.id }.distinct().size == groups.size) { "Deletion group IDs must be unique" }
        val files = groups.flatMap { it.files }
        require(files.map { it.id }.distinct().size == files.size) { "Deletion file IDs must be unique" }
    }

    private data class Candidate(
        val group: DeletionGroup,
        val validation: Validation,
    ) {
        val file: DeletionFile get() = validation.file
    }

    private data class Validation(
        val file: DeletionFile,
        val snapshot: CurrentFileSnapshot?,
        val membership: Membership,
    )

    private enum class Membership {
        MATCHING,
        HASH_MATCH_PENDING,
        MISSING,
        CHANGED,
        REVALIDATION_FAILED,
    }
}
