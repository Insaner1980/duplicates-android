package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.ExactDuplicateGroup
import com.emma.duplicates.core.model.ExactDuplicateMember
import com.emma.duplicates.core.model.FileMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

enum class ExactDuplicateScanPhase {
    QUICK_HASHING,
    FULL_HASHING,
    VERIFYING,
}

enum class ExactDuplicateProgressUnit {
    ITEMS,
    BYTES,
}

data class ExactDuplicateScanProgress(
    val phase: ExactDuplicateScanPhase,
    val completedWork: Long,
    val totalWork: Long,
    val unit: ExactDuplicateProgressUnit,
)

data class ExactDuplicateScanResult(
    val groups: List<ExactDuplicateGroup>,
    val issues: List<FileProcessingIssue>,
    val skippedFileCount: Int,
    val errorCount: Int,
    val cacheHitCount: Int,
    val quickHashedFileCount: Int,
    val fullHashedFileCount: Int,
) {
    val duplicateFileCount: Int
        get() = groups.sumOf { it.copyCount }

    val duplicateGroupCount: Int
        get() = groups.size

    val reclaimableBytes: Long
        get() = groups.sumOf { it.reclaimableBytes }
}

class ExactDuplicateScanner(
    private val fingerprinter: FileFingerprinter = Sha256FileFingerprinter(),
    private val fingerprintCache: FingerprintCache = EmptyFingerprintCache,
    fullHashWorkerCount: Int = MAX_FULL_HASH_WORKERS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val fullHashWorkerCount =
        fullHashWorkerCount.also { workerCount ->
            require(workerCount in 1..MAX_FULL_HASH_WORKERS) {
                "Full hashing worker count must be between 1 and $MAX_FULL_HASH_WORKERS"
            }
        }

    suspend fun scan(
        files: List<FileMetadata>,
        onProgress: suspend (ExactDuplicateScanProgress) -> Unit = {},
    ): ExactDuplicateScanResult =
        withContext(ioDispatcher) {
            ScanSession(onProgress).scan(files)
        }

    private inner class ScanSession(
        private val onProgress: suspend (ExactDuplicateScanProgress) -> Unit,
    ) {
        private val issues = linkedMapOf<String, FileProcessingIssue>()
        private var cacheHitCount = 0
        private var quickHashedFileCount = 0
        private val fullHashedFileCount = AtomicInteger()

        suspend fun scan(files: List<FileMetadata>): ExactDuplicateScanResult {
            val orderedFiles =
                files
                    .distinctBy { "${it.storageVolume}\u0000${it.canonicalPath}" }
                    .sortedWith(FILE_ORDER)
            val zeroByteFileCount = orderedFiles.count { it.sizeBytes == 0L }
            val sizeCandidates =
                orderedFiles
                    .asSequence()
                    .filter { it.sizeBytes > 0L }
                    .groupBy(FileMetadata::sizeBytes)
                    .toSortedMap()
                    .values
                    .filter { it.size > 1 }

            val quickCandidates = quickHash(sizeCandidates)
            val hashGroups =
                fullHash(quickCandidates)
                    .groupBy { FullHashKey(it.file.sizeBytes, it.fullSha256) }
                    .toSortedMap(compareBy<FullHashKey>({ it.sizeBytes }, { it.fullSha256 }))
            val confirmedGroups = verifyGroups(hashGroups)
            val orderedIssues = synchronized(issues) { issues.values.sortedBy(FileProcessingIssue::fileId) }
            return ExactDuplicateScanResult(
                groups =
                    confirmedGroups.sortedWith(
                        compareByDescending<ExactDuplicateGroup> { it.reclaimableBytes }
                            .thenBy(ExactDuplicateGroup::contentHash)
                            .thenBy(ExactDuplicateGroup::id),
                    ),
                issues = orderedIssues,
                skippedFileCount = zeroByteFileCount + orderedIssues.size,
                errorCount = orderedIssues.size,
                cacheHitCount = cacheHitCount,
                quickHashedFileCount = quickHashedFileCount,
                fullHashedFileCount = fullHashedFileCount.get(),
            )
        }

        private suspend fun quickHash(sizeCandidates: List<List<FileMetadata>>): List<QuickCandidate> {
            val quickCandidates = mutableListOf<QuickCandidate>()
            val totalQuickItems = sizeCandidates.sumOf { it.size }.toLong()
            var completedQuickItems = 0L
            onProgress(
                ExactDuplicateScanProgress(
                    phase = ExactDuplicateScanPhase.QUICK_HASHING,
                    completedWork = 0,
                    totalWork = totalQuickItems,
                    unit = ExactDuplicateProgressUnit.ITEMS,
                ),
            )

            for (sameSizeFiles in sizeCandidates) {
                currentCoroutineContext().ensureActive()
                val sameQuickHash = sortedMapOf<String, MutableList<QuickCandidate>>()
                for (file in sameSizeFiles.sortedWith(FILE_ORDER)) {
                    currentCoroutineContext().ensureActive()
                    val candidate = quickCandidate(file)
                    if (candidate != null) {
                        sameQuickHash.getOrPut(candidate.quickSha256, ::mutableListOf) += candidate
                    }
                    completedQuickItems += 1
                    onProgress(
                        ExactDuplicateScanProgress(
                            phase = ExactDuplicateScanPhase.QUICK_HASHING,
                            completedWork = completedQuickItems,
                            totalWork = totalQuickItems,
                            unit = ExactDuplicateProgressUnit.ITEMS,
                        ),
                    )
                }
                sameQuickHash.values.filter { it.size > 1 }.forEach(quickCandidates::addAll)
            }
            return quickCandidates
        }

        private suspend fun quickCandidate(file: FileMetadata): QuickCandidate? {
            val cached = fingerprintCache.find(file.canonicalPath, file.storageVolume)
            return if (cached != null && cached.identity.matches(file)) {
                val validationIssue = fingerprinter.validate(file)
                if (validationIssue != null) {
                    recordIssue(issues, validationIssue)
                    fingerprintCache.invalidate(file.canonicalPath, file.storageVolume)
                    null
                } else {
                    cacheHitCount += 1
                    QuickCandidate(file, cached.quickSha256, cached.fullSha256)
                }
            } else {
                if (cached != null) {
                    fingerprintCache.invalidate(file.canonicalPath, file.storageVolume)
                }
                quickHashedFileCount += 1
                when (val outcome = fingerprinter.quickFingerprint(file)) {
                    is FingerprintOutcome.Success -> {
                        QuickCandidate(file, outcome.sha256, null)
                    }

                    is FingerprintOutcome.Failure -> {
                        recordIssue(issues, outcome.issue)
                        fingerprintCache.invalidate(file.canonicalPath, file.storageVolume)
                        null
                    }
                }
            }
        }

        private suspend fun fullHash(quickCandidates: List<QuickCandidate>): List<HashedCandidate> {
            val fullHashOutcomes = arrayOfNulls<HashedCandidate>(quickCandidates.size)
            val totalFullBytes = quickCandidates.sumOf { it.file.sizeBytes }
            var completedFullBytes = 0L
            val progressMutex = Mutex()
            onProgress(
                ExactDuplicateScanProgress(
                    phase = ExactDuplicateScanPhase.FULL_HASHING,
                    completedWork = 0,
                    totalWork = totalFullBytes,
                    unit = ExactDuplicateProgressUnit.BYTES,
                ),
            )
            val uncachedTasks = mutableListOf<IndexedQuickCandidate>()
            quickCandidates.forEachIndexed { index, candidate ->
                val cachedFullHash = candidate.cachedFullSha256
                if (cachedFullHash != null) {
                    fullHashOutcomes[index] = HashedCandidate(candidate.file, candidate.quickSha256, cachedFullHash)
                    completedFullBytes += candidate.file.sizeBytes
                    onProgress(
                        ExactDuplicateScanProgress(
                            phase = ExactDuplicateScanPhase.FULL_HASHING,
                            completedWork = completedFullBytes,
                            totalWork = totalFullBytes,
                            unit = ExactDuplicateProgressUnit.BYTES,
                        ),
                    )
                } else {
                    uncachedTasks += IndexedQuickCandidate(index, candidate)
                }
            }

            hashUncached(uncachedTasks, fullHashOutcomes) { candidate ->
                progressMutex.withLock {
                    completedFullBytes += candidate.file.sizeBytes
                    onProgress(
                        ExactDuplicateScanProgress(
                            phase = ExactDuplicateScanPhase.FULL_HASHING,
                            completedWork = completedFullBytes,
                            totalWork = totalFullBytes,
                            unit = ExactDuplicateProgressUnit.BYTES,
                        ),
                    )
                }
            }
            return fullHashOutcomes.filterNotNull()
        }

        private suspend fun hashUncached(
            uncachedTasks: List<IndexedQuickCandidate>,
            fullHashOutcomes: Array<HashedCandidate?>,
            onCompleted: suspend (QuickCandidate) -> Unit,
        ) = coroutineScope {
            val tasks = Channel<IndexedQuickCandidate>(capacity = fullHashWorkerCount * 2)
            val workers =
                List(fullHashWorkerCount) {
                    launch {
                        for (task in tasks) {
                            currentCoroutineContext().ensureActive()
                            fullHashedFileCount.incrementAndGet()
                            fullHashOutcomes[task.index] = hashCandidate(task.candidate)
                            onCompleted(task.candidate)
                        }
                    }
                }
            for (task in uncachedTasks) tasks.send(task)
            tasks.close()
            workers.joinAll()
        }

        private suspend fun hashCandidate(candidate: QuickCandidate): HashedCandidate? =
            when (val outcome = fingerprinter.fullFingerprint(candidate.file)) {
                is FingerprintOutcome.Success -> {
                    fingerprintCache.put(
                        CachedFingerprint(
                            identity = FingerprintIdentity.from(candidate.file),
                            quickSha256 = candidate.quickSha256,
                            fullSha256 = outcome.sha256,
                        ),
                    )
                    HashedCandidate(candidate.file, candidate.quickSha256, outcome.sha256)
                }

                is FingerprintOutcome.Failure -> {
                    synchronized(issues) { recordIssue(issues, outcome.issue) }
                    fingerprintCache.invalidate(candidate.file.canonicalPath, candidate.file.storageVolume)
                    null
                }
            }

        private suspend fun verifyGroups(
            hashGroups: Map<FullHashKey, List<HashedCandidate>>,
        ): List<ExactDuplicateGroup> {
            val confirmedGroups = mutableListOf<ExactDuplicateGroup>()
            val totalVerificationItems =
                hashGroups.values
                    .filter { it.size > 1 }
                    .sumOf { it.size }
                    .toLong()
            var completedVerificationItems = 0L
            onProgress(
                ExactDuplicateScanProgress(
                    phase = ExactDuplicateScanPhase.VERIFYING,
                    completedWork = 0,
                    totalWork = totalVerificationItems,
                    unit = ExactDuplicateProgressUnit.ITEMS,
                ),
            )
            for ((key, sameHashFiles) in hashGroups) {
                if (sameHashFiles.size < 2) continue
                val verifiedPartitions =
                    verifyPartitions(sameHashFiles, issues)
                        .filter { it.size > 1 }
                        .sortedBy { partition -> partition.first().file.canonicalPath }
                verifiedPartitions.forEachIndexed { partitionIndex, partition ->
                    val members =
                        partition.sortedWith(HASHED_FILE_ORDER).map { hashed ->
                            ExactDuplicateMember(
                                file = hashed.file,
                                quickSha256 = hashed.quickSha256,
                                fullSha256 = hashed.fullSha256,
                            )
                        }
                    confirmedGroups +=
                        ExactDuplicateGroup(
                            id = "${key.sizeBytes}:${key.fullSha256}:$partitionIndex",
                            category = members.first().file.category,
                            sizeBytes = key.sizeBytes,
                            contentHash = key.fullSha256,
                            members = members,
                        )
                }
                completedVerificationItems += sameHashFiles.size
                onProgress(
                    ExactDuplicateScanProgress(
                        phase = ExactDuplicateScanPhase.VERIFYING,
                        completedWork = completedVerificationItems,
                        totalWork = totalVerificationItems,
                        unit = ExactDuplicateProgressUnit.ITEMS,
                    ),
                )
            }

            return confirmedGroups
        }
    }

    private suspend fun verifyPartitions(
        candidates: List<HashedCandidate>,
        issues: MutableMap<String, FileProcessingIssue>,
    ): List<List<HashedCandidate>> {
        val invalidFileIds = mutableSetOf<String>()
        val partitions = mutableListOf<List<HashedCandidate>>()
        var remaining = candidates.sortedWith(HASHED_FILE_ORDER)

        while (remaining.size > 1) {
            currentCoroutineContext().ensureActive()
            val partition = verifyPartition(remaining, issues, invalidFileIds)
            if (partition.matching.size > 1) {
                partitions += partition.matching.sortedWith(HASHED_FILE_ORDER)
            }
            remaining = partition.remaining
        }
        return partitions
    }

    private suspend fun verifyPartition(
        candidates: List<HashedCandidate>,
        issues: MutableMap<String, FileProcessingIssue>,
        invalidFileIds: MutableSet<String>,
    ): VerifiedPartition {
        val seed = candidates.first()
        val tail = candidates.drop(1)
        val matching = mutableListOf(seed)
        val different = mutableListOf<HashedCandidate>()

        for ((index, candidate) in tail.withIndex()) {
            currentCoroutineContext().ensureActive()
            when (val comparison = fingerprinter.compare(seed.file, candidate.file)) {
                ContentComparisonOutcome.Equal -> {
                    matching += candidate
                }

                ContentComparisonOutcome.Different -> {
                    different += candidate
                }

                is ContentComparisonOutcome.Failure -> {
                    recordComparisonIssues(
                        comparison.issues,
                        matching + different + tail.drop(index),
                        issues,
                        invalidFileIds,
                    )
                    if (seed.file.id in invalidFileIds) {
                        val requeued =
                            (matching.drop(1) + different + tail.drop(index))
                                .filterNot { it.file.id in invalidFileIds }
                                .distinctBy { it.file.id }
                                .sortedWith(HASHED_FILE_ORDER)
                        return VerifiedPartition(emptyList(), requeued)
                    }
                    if (candidate.file.id !in invalidFileIds) different += candidate
                }
            }
        }
        return VerifiedPartition(
            matching = matching,
            remaining =
                different
                    .filterNot { it.file.id in invalidFileIds }
                    .distinctBy { it.file.id }
                    .sortedWith(HASHED_FILE_ORDER),
        )
    }

    private suspend fun recordComparisonIssues(
        comparisonIssues: List<FileProcessingIssue>,
        candidates: List<HashedCandidate>,
        issues: MutableMap<String, FileProcessingIssue>,
        invalidFileIds: MutableSet<String>,
    ) {
        for (issue in comparisonIssues) {
            if (issues.putIfAbsent(issue.fileId, issue) == null) {
                invalidFileIds += issue.fileId
                val failed = candidates.firstOrNull { it.file.id == issue.fileId }
                if (failed != null) {
                    fingerprintCache.invalidate(failed.file.canonicalPath, failed.file.storageVolume)
                }
            }
        }
    }

    private fun recordIssue(
        issues: MutableMap<String, FileProcessingIssue>,
        issue: FileProcessingIssue,
    ) {
        issues.putIfAbsent(issue.fileId, issue)
    }

    private data class QuickCandidate(
        val file: FileMetadata,
        val quickSha256: String,
        val cachedFullSha256: String?,
    )

    private data class IndexedQuickCandidate(
        val index: Int,
        val candidate: QuickCandidate,
    )

    private data class HashedCandidate(
        val file: FileMetadata,
        val quickSha256: String,
        val fullSha256: String,
    )

    private data class VerifiedPartition(
        val matching: List<HashedCandidate>,
        val remaining: List<HashedCandidate>,
    )

    private data class FullHashKey(
        val sizeBytes: Long,
        val fullSha256: String,
    )

    private companion object {
        const val MAX_FULL_HASH_WORKERS = 2
        val FILE_ORDER = compareBy<FileMetadata>(FileMetadata::canonicalPath, FileMetadata::id)
        val HASHED_FILE_ORDER = compareBy<HashedCandidate>({ it.file.canonicalPath }, { it.file.id })
    }
}
