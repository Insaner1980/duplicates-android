package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.FileCategory
import com.emma.duplicates.core.model.FileMetadata
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExactDuplicateScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `same bytes and same filename form one exact group`() = runTest {
        val first = temporaryFolder.root.resolve("one/shared.txt").createText("identical")
        val second = temporaryFolder.root.resolve("two/shared.txt").createText("identical")

        val result = ExactDuplicateScanner().scan(listOf(first.metadata(), second.metadata()))

        assertSingleGroup(result, first, second)
    }

    @Test
    fun `same bytes and different filenames form one exact group`() = runTest {
        val first = temporaryFolder.root.resolve("first-name.txt").createText("identical")
        val second = temporaryFolder.root.resolve("second-name.bin").createText("identical")

        val result = ExactDuplicateScanner().scan(listOf(first.metadata(), second.metadata()))

        assertSingleGroup(result, first, second)
    }

    @Test
    fun `same bytes in different folders form one exact group`() = runTest {
        val first = temporaryFolder.root.resolve("Camera/photo.jpg").createText("identical")
        val second = temporaryFolder.root.resolve("Download/photo-copy.jpg").createText("identical")

        val result = ExactDuplicateScanner().scan(listOf(first.metadata(), second.metadata()))

        assertSingleGroup(result, first, second)
    }

    @Test
    fun `same bytes with different timestamps form one exact group`() = runTest {
        val first = temporaryFolder.root.resolve("old.txt").createText("identical")
        val second = temporaryFolder.root.resolve("new.txt").createText("identical")
        assertTrue(first.setLastModified(1_600_000_000_000L))
        assertTrue(second.setLastModified(1_700_000_000_000L))

        val result = ExactDuplicateScanner().scan(listOf(first.metadata(), second.metadata()))

        assertSingleGroup(result, first, second)
    }

    @Test
    fun `same size with different content never forms a group`() = runTest {
        val first = temporaryFolder.root.resolve("first.bin").createText("content-a")
        val second = temporaryFolder.root.resolve("second.bin").createText("content-b")

        val result = ExactDuplicateScanner().scan(listOf(first.metadata(), second.metadata()))

        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `same filename with different content never forms a group`() = runTest {
        val first = temporaryFolder.root.resolve("one/repeated.txt").createText("content-a")
        val second = temporaryFolder.root.resolve("two/repeated.txt").createText("content-b")

        val result = ExactDuplicateScanner().scan(listOf(first.metadata(), second.metadata()))

        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `quick fingerprint collision is rejected by different full hashes`() = runTest {
        val first = fakeMetadata("/storage/first.bin", sizeBytes = 50)
        val second = fakeMetadata("/storage/second.bin", sizeBytes = 50)
        val fingerprinter = ScriptedFingerprinter(
            quickHashes = mapOf(first.id to "same-quick", second.id to "same-quick"),
            fullHashes = mapOf(first.id to "full-a", second.id to "full-b"),
        )

        val result = ExactDuplicateScanner(fingerprinter = fingerprinter).scan(listOf(first, second))

        assertTrue(result.groups.isEmpty())
        assertEquals(2, result.fullHashedFileCount)
    }

    @Test
    fun `unique-size files are never hashed`() = runTest {
        val first = fakeMetadata("/storage/one.bin", sizeBytes = 10)
        val second = fakeMetadata("/storage/two.bin", sizeBytes = 20)
        val fingerprinter = ScriptedFingerprinter()

        val result = ExactDuplicateScanner(fingerprinter = fingerprinter).scan(listOf(first, second))

        assertTrue(result.groups.isEmpty())
        assertEquals(0, fingerprinter.quickCalls.get())
        assertEquals(0, fingerprinter.fullCalls.get())
    }

    @Test
    fun `file removed during quick hashing is skipped without failing the scan`() = runTest {
        val removed = fakeMetadata("/storage/removed.bin", sizeBytes = 50)
        val remaining = fakeMetadata("/storage/remaining.bin", sizeBytes = 50)
        val fingerprinter = ScriptedFingerprinter(
            quickFailures = mapOf(
                removed.id to FileProcessingIssue(removed.id, FileIssueReason.MISSING),
            ),
        )

        val result = ExactDuplicateScanner(fingerprinter = fingerprinter).scan(listOf(removed, remaining))

        assertTrue(result.groups.isEmpty())
        assertEquals(listOf(FileIssueReason.MISSING), result.issues.map { it.reason })
        assertEquals(1, result.skippedFileCount)
        assertEquals(1, result.errorCount)
    }

    @Test
    fun `file modified during full hashing is excluded from results`() = runTest {
        val changed = fakeMetadata("/storage/changed.bin", sizeBytes = 50)
        val stable = fakeMetadata("/storage/stable.bin", sizeBytes = 50)
        val fingerprinter = ScriptedFingerprinter(
            fullFailures = mapOf(
                changed.id to FileProcessingIssue(changed.id, FileIssueReason.CHANGED),
            ),
        )

        val result = ExactDuplicateScanner(fingerprinter = fingerprinter).scan(listOf(changed, stable))

        assertTrue(result.groups.isEmpty())
        assertEquals(listOf(FileIssueReason.CHANGED), result.issues.map { it.reason })
        assertEquals(1, result.skippedFileCount)
        assertEquals(1, result.errorCount)
    }

    @Test
    fun `unchanged cached fingerprints bypass quick and full hashing`() = runTest {
        val first = fakeMetadata("/storage/a.bin", sizeBytes = 50, lastModified = 100)
        val second = fakeMetadata("/storage/b.bin", sizeBytes = 50, lastModified = 100)
        val cache = MemoryFingerprintCache().apply {
            store(first, "quick", "full")
            store(second, "quick", "full")
        }
        val fingerprinter = ScriptedFingerprinter()

        val result = ExactDuplicateScanner(fingerprinter, cache).scan(listOf(first, second))

        assertEquals(1, result.groups.size)
        assertEquals(2, result.cacheHitCount)
        assertEquals(0, fingerprinter.quickCalls.get())
        assertEquals(0, fingerprinter.fullCalls.get())
        assertEquals(1, fingerprinter.compareCalls.get())
    }

    @Test
    fun `changed identity invalidates a stale cached fingerprint`() = runTest {
        val changed = fakeMetadata("/storage/a.bin", sizeBytes = 50, lastModified = 200)
        val stable = fakeMetadata("/storage/b.bin", sizeBytes = 50, lastModified = 200)
        val cache = MemoryFingerprintCache().apply {
            values[key(changed)] = CachedFingerprint(
                identity = FingerprintIdentity(
                    canonicalPath = changed.canonicalPath,
                    sizeBytes = changed.sizeBytes,
                    lastModifiedMillis = 100,
                    storageVolume = changed.storageVolume,
                ),
                quickSha256 = "old-quick",
                fullSha256 = "old-full",
            )
        }
        val fingerprinter = ScriptedFingerprinter()

        ExactDuplicateScanner(fingerprinter, cache).scan(listOf(changed, stable))

        assertEquals(listOf(key(changed)), cache.invalidated)
        assertEquals(2, fingerprinter.quickCalls.get())
        assertEquals(2, fingerprinter.fullCalls.get())
        assertEquals(2, cache.values.size)
    }

    @Test
    fun `final byte comparison splits a theoretical full-hash collision`() = runTest {
        val first = fakeMetadata("/storage/a.bin", sizeBytes = 50)
        val second = fakeMetadata("/storage/b.bin", sizeBytes = 50)
        val fingerprinter = ScriptedFingerprinter(comparison = ContentComparisonOutcome.Different)

        val result = ExactDuplicateScanner(fingerprinter = fingerprinter).scan(listOf(first, second))

        assertTrue(result.groups.isEmpty())
        assertEquals(1, fingerprinter.compareCalls.get())
    }

    @Test
    fun `group and member ordering is deterministic regardless of input order`() = runTest {
        val files = listOf(
            fakeMetadata("/storage/z-two.bin", sizeBytes = 20),
            fakeMetadata("/storage/z-one.bin", sizeBytes = 20),
            fakeMetadata("/storage/a-three.bin", sizeBytes = 15),
            fakeMetadata("/storage/a-one.bin", sizeBytes = 15),
            fakeMetadata("/storage/a-two.bin", sizeBytes = 15),
        )
        val fullHashes = files.associate { file ->
            file.id to if (file.sizeBytes == 20L) "hash-z" else "hash-a"
        }
        val scanner = ExactDuplicateScanner(ScriptedFingerprinter(fullHashes = fullHashes))

        val first = scanner.scan(files).groups
        val second = scanner.scan(files.reversed()).groups

        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(
            first.map { group -> group.members.map { it.file.canonicalPath } },
            second.map { group -> group.members.map { it.file.canonicalPath } },
        )
        assertEquals(listOf(30L, 20L), first.map { it.reclaimableBytes })
        assertTrue(first.all { group -> group.members.map { it.file.canonicalPath } == group.members.map { it.file.canonicalPath }.sorted() })
    }

    @Test
    fun `full hashing uses at most two long-lived workers`() = runTest {
        val files = (1..8).map { index -> fakeMetadata("/storage/$index.bin", sizeBytes = 50) }
        val fingerprinter = ScriptedFingerprinter(fullHashDelayMillis = 20)

        val result = ExactDuplicateScanner(fingerprinter = fingerprinter).scan(files)

        assertEquals(1, result.groups.size)
        assertEquals(2, fingerprinter.maximumConcurrentFullHashes.get())
        assertFalse(fingerprinter.maximumConcurrentFullHashes.get() > 2)
    }

    @Test
    fun `progress has ordered phases with item quick verify work and byte weighted full work`() = runTest {
        val files = (1..3).map { index -> fakeMetadata("/storage/progress-$index.bin", sizeBytes = 50) }
        val updates = CopyOnWriteArrayList<ExactDuplicateScanProgress>()

        ExactDuplicateScanner(ScriptedFingerprinter()).scan(files, updates::add)

        assertTrue(updates.all { it.completedWork in 0..it.totalWork })
        assertEquals(
            listOf(
                ExactDuplicateScanPhase.QUICK_HASHING,
                ExactDuplicateScanPhase.FULL_HASHING,
                ExactDuplicateScanPhase.VERIFYING,
            ),
            updates.map { it.phase }.distinct(),
        )
        val quick = updates.last { it.phase == ExactDuplicateScanPhase.QUICK_HASHING }
        assertEquals(ExactDuplicateProgressUnit.ITEMS, quick.unit)
        assertEquals(3L, quick.completedWork)
        assertEquals(3L, quick.totalWork)
        val full = updates.last { it.phase == ExactDuplicateScanPhase.FULL_HASHING }
        assertEquals(ExactDuplicateProgressUnit.BYTES, full.unit)
        assertEquals(150L, full.completedWork)
        assertEquals(150L, full.totalWork)
        val verifying = updates.last { it.phase == ExactDuplicateScanPhase.VERIFYING }
        assertEquals(ExactDuplicateProgressUnit.ITEMS, verifying.unit)
        assertEquals(3L, verifying.completedWork)
        assertEquals(3L, verifying.totalWork)
        assertTrue(updates.groupBy { it.phase }.values.all { phaseUpdates ->
            phaseUpdates.zipWithNext().all { (before, after) -> before.completedWork <= after.completedWork }
        })
    }

    private fun assertSingleGroup(result: ExactDuplicateScanResult, vararg expected: File) {
        assertEquals(1, result.groups.size)
        val group = result.groups.single()
        assertEquals(expected.map { it.canonicalPath }.sorted(), group.members.map { it.file.canonicalPath })
        assertEquals(expected.first().length(), group.sizeBytes)
        assertEquals(expected.first().length() * (expected.size - 1), group.reclaimableBytes)
        assertEquals(0, result.errorCount)
    }

    private fun File.createText(content: String): File = apply {
        parentFile?.mkdirs()
        writeText(content, Charsets.UTF_8)
    }

    private fun File.metadata(): FileMetadata = fakeMetadata(
        canonicalPath = canonicalPath,
        sizeBytes = length(),
        lastModified = lastModified(),
        displayName = name,
        extension = extension,
    )

    private fun fakeMetadata(
        canonicalPath: String,
        sizeBytes: Long,
        lastModified: Long = 100,
        displayName: String = canonicalPath.substringAfterLast('/').substringAfterLast('\\'),
        extension: String = displayName.substringAfterLast('.', ""),
    ): FileMetadata = FileMetadata(
        id = canonicalPath,
        canonicalPath = canonicalPath,
        displayName = displayName,
        extension = extension,
        mimeType = null,
        category = FileCategory.DOCUMENTS,
        sizeBytes = sizeBytes,
        lastModifiedMillis = lastModified,
        storageVolume = "primary",
        parentDirectory = canonicalPath.substringBeforeLast('/', ""),
        isReadable = true,
        isWritable = true,
        contentUri = null,
        isFavorite = null,
        isTrashed = null,
    )

    private class ScriptedFingerprinter(
        private val quickHashes: Map<String, String> = emptyMap(),
        private val fullHashes: Map<String, String> = emptyMap(),
        private val quickFailures: Map<String, FileProcessingIssue> = emptyMap(),
        private val fullFailures: Map<String, FileProcessingIssue> = emptyMap(),
        private val comparison: ContentComparisonOutcome = ContentComparisonOutcome.Equal,
        private val fullHashDelayMillis: Long = 0,
    ) : FileFingerprinter {
        val quickCalls = AtomicInteger()
        val fullCalls = AtomicInteger()
        val compareCalls = AtomicInteger()
        val maximumConcurrentFullHashes = AtomicInteger()
        private val activeFullHashes = AtomicInteger()

        override suspend fun validate(file: FileMetadata): FileProcessingIssue? = null

        override suspend fun quickFingerprint(file: FileMetadata): FingerprintOutcome {
            quickCalls.incrementAndGet()
            return quickFailures[file.id]?.let(FingerprintOutcome::Failure)
                ?: FingerprintOutcome.Success(quickHashes[file.id] ?: "quick-${file.sizeBytes}")
        }

        override suspend fun fullFingerprint(file: FileMetadata): FingerprintOutcome {
            fullCalls.incrementAndGet()
            val active = activeFullHashes.incrementAndGet()
            maximumConcurrentFullHashes.updateAndGet { previous -> maxOf(previous, active) }
            try {
                if (fullHashDelayMillis > 0) delay(fullHashDelayMillis)
                return fullFailures[file.id]?.let(FingerprintOutcome::Failure)
                    ?: FingerprintOutcome.Success(fullHashes[file.id] ?: "full-${file.sizeBytes}")
            } finally {
                activeFullHashes.decrementAndGet()
            }
        }

        override suspend fun compare(
            first: FileMetadata,
            second: FileMetadata,
        ): ContentComparisonOutcome {
            compareCalls.incrementAndGet()
            return comparison
        }
    }

    private inner class MemoryFingerprintCache : FingerprintCache {
        val values = ConcurrentHashMap<String, CachedFingerprint>()
        val invalidated = CopyOnWriteArrayList<String>()

        override suspend fun find(canonicalPath: String, storageVolume: String): CachedFingerprint? =
            values["$storageVolume\u0000$canonicalPath"]

        override suspend fun put(fingerprint: CachedFingerprint) {
            values[key(fingerprint.identity.canonicalPath, fingerprint.identity.storageVolume)] = fingerprint
        }

        override suspend fun invalidate(canonicalPath: String, storageVolume: String) {
            val key = key(canonicalPath, storageVolume)
            invalidated += key
            values.remove(key)
        }

        fun store(file: FileMetadata, quickHash: String, fullHash: String) {
            values[key(file)] = CachedFingerprint(
                identity = FingerprintIdentity(
                    canonicalPath = file.canonicalPath,
                    sizeBytes = file.sizeBytes,
                    lastModifiedMillis = file.lastModifiedMillis,
                    storageVolume = file.storageVolume,
                ),
                quickSha256 = quickHash,
                fullSha256 = fullHash,
            )
        }
    }

    private fun key(file: FileMetadata): String = key(file.canonicalPath, file.storageVolume)

    private fun key(canonicalPath: String, storageVolume: String): String =
        "$storageVolume\u0000$canonicalPath"
}
