package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.ExactDuplicateGroup
import com.emma.duplicates.core.model.ExactDuplicateMember
import com.emma.duplicates.core.model.FileMetadata
import java.util.concurrent.atomic.AtomicInteger
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
) {
    private val fullHashWorkerCount = fullHashWorkerCount.also { workerCount ->
        require(workerCount in 1..MAX_FULL_HASH_WORKERS) {
            "Full hashing worker count must be between 1 and $MAX_FULL_HASH_WORKERS"
        }
    }

    suspend fun scan(
        files: List<FileMetadata>,
        onProgress: suspend (ExactDuplicateScanProgress) -> Unit = {},
    ): ExactDuplicateScanResult = withContext(Dispatchers.IO) {
        val issues = linkedMapOf<String, FileProcessingIssue>()
        val orderedFiles = files
            .distinctBy { "${it.storageVolume}\u0000${it.canonicalPath}" }
            .sortedWith(FILE_ORDER)
        val zeroByteFileCount = orderedFiles.count { it.sizeBytes == 0L }
        val sizeCandidates = orderedFiles
            .asSequence()
            .filter { it.sizeBytes > 0L }
            .groupBy(FileMetadata::sizeBytes)
            .toSortedMap()
            .values
            .filter { it.size > 1 }

        var cacheHitCount = 0
        var quickHashedFileCount = 0
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
                val cached = fingerprintCache.find(file.canonicalPath, file.storageVolume)
                val candidate = if (cached != null && cached.identity.matches(file)) {
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
                        is FingerprintOutcome.Success -> QuickCandidate(file, outcome.sha256, null)
                        is FingerprintOutcome.Failure -> {
                            recordIssue(issues, outcome.issue)
                            fingerprintCache.invalidate(file.canonicalPath, file.storageVolume)
                            null
                        }
                    }
                }
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

        val fullHashOutcomes = arrayOfNulls<HashedCandidate>(quickCandidates.size)
        val fullHashedFileCount = AtomicInteger()
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

        coroutineScope {
            val tasks = Channel<IndexedQuickCandidate>(capacity = fullHashWorkerCount * 2)
            val workers = List(fullHashWorkerCount) {
                launch {
                    for (task in tasks) {
                        currentCoroutineContext().ensureActive()
                        fullHashedFileCount.incrementAndGet()
                        when (val outcome = fingerprinter.fullFingerprint(task.candidate.file)) {
                            is FingerprintOutcome.Success -> {
                                val hashed = HashedCandidate(
                                    file = task.candidate.file,
                                    quickSha256 = task.candidate.quickSha256,
                                    fullSha256 = outcome.sha256,
                                )
                                fullHashOutcomes[task.index] = hashed
                                fingerprintCache.put(
                                    CachedFingerprint(
                                        identity = FingerprintIdentity.from(task.candidate.file),
                                        quickSha256 = task.candidate.quickSha256,
                                        fullSha256 = outcome.sha256,
                                    ),
                                )
                            }

                            is FingerprintOutcome.Failure -> {
                                synchronized(issues) {
                                    recordIssue(issues, outcome.issue)
                                }
                                fingerprintCache.invalidate(
                                    task.candidate.file.canonicalPath,
                                    task.candidate.file.storageVolume,
                                )
                            }
                        }
                        progressMutex.withLock {
                            completedFullBytes += task.candidate.file.sizeBytes
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
                }
            }
            for (task in uncachedTasks) tasks.send(task)
            tasks.close()
            workers.joinAll()
        }

        val hashGroups = fullHashOutcomes
            .filterNotNull()
            .groupBy { FullHashKey(it.file.sizeBytes, it.fullSha256) }
            .toSortedMap(compareBy<FullHashKey>({ it.sizeBytes }, { it.fullSha256 }))

        val confirmedGroups = mutableListOf<ExactDuplicateGroup>()
        val totalVerificationItems = hashGroups.values.filter { it.size > 1 }.sumOf { it.size }.toLong()
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
            val verifiedPartitions = verifyPartitions(sameHashFiles, issues)
                .filter { it.size > 1 }
                .sortedBy { partition -> partition.first().file.canonicalPath }
            verifiedPartitions.forEachIndexed { partitionIndex, partition ->
                val members = partition.sortedWith(HASHED_FILE_ORDER).map { hashed ->
                    ExactDuplicateMember(
                        file = hashed.file,
                        quickSha256 = hashed.quickSha256,
                        fullSha256 = hashed.fullSha256,
                    )
                }
                confirmedGroups += ExactDuplicateGroup(
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

        val orderedIssues = synchronized(issues) { issues.values.sortedBy(FileProcessingIssue::fileId) }
        ExactDuplicateScanResult(
            groups = confirmedGroups.sortedWith(
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

    private suspend fun verifyPartitions(
        candidates: List<HashedCandidate>,
        issues: MutableMap<String, FileProcessingIssue>,
    ): List<List<HashedCandidate>> {
        val invalidFileIds = mutableSetOf<String>()
        val partitions = mutableListOf<List<HashedCandidate>>()
        var remaining = candidates.sortedWith(HASHED_FILE_ORDER)

        while (remaining.size > 1) {
            currentCoroutineContext().ensureActive()
            val seed = remaining.first()
            val tail = remaining.drop(1)
            val matching = mutableListOf(seed)
            val different = mutableListOf<HashedCandidate>()
            var requeueAfterSeedFailure: List<HashedCandidate>? = null

            for ((index, candidate) in tail.withIndex()) {
                currentCoroutineContext().ensureActive()
                when (val comparison = fingerprinter.compare(seed.file, candidate.file)) {
                    ContentComparisonOutcome.Equal -> matching += candidate
                    ContentComparisonOutcome.Different -> different += candidate
                    is ContentComparisonOutcome.Failure -> {
                        for (issue in comparison.issues) {
                            if (issues.putIfAbsent(issue.fileId, issue) == null) {
                                invalidFileIds += issue.fileId
                                val failed = (matching + different + tail.drop(index))
                                    .firstOrNull { it.file.id == issue.fileId }
                                if (failed != null) {
                                    fingerprintCache.invalidate(
                                        failed.file.canonicalPath,
                                        failed.file.storageVolume,
                                    )
                                }
                            }
                        }
                        if (seed.file.id in invalidFileIds) {
                            requeueAfterSeedFailure = (matching.drop(1) + different + tail.drop(index))
                                .filterNot { it.file.id in invalidFileIds }
                                .distinctBy { it.file.id }
                                .sortedWith(HASHED_FILE_ORDER)
                            break
                        }
                        if (candidate.file.id !in invalidFileIds) different += candidate
                    }
                }
            }

            if (requeueAfterSeedFailure != null) {
                remaining = requeueAfterSeedFailure
                continue
            }
            if (matching.size > 1) partitions += matching.sortedWith(HASHED_FILE_ORDER)
            remaining = different
                .filterNot { it.file.id in invalidFileIds }
                .distinctBy { it.file.id }
                .sortedWith(HASHED_FILE_ORDER)
        }
        return partitions
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
