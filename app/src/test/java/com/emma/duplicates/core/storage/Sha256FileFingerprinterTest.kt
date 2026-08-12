package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.FileCategory
import com.emma.duplicates.core.model.FileMetadata
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Sha256FileFingerprinterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `small quick fingerprint includes big-endian file size and complete bytes`() = runTest {
        val file = temporaryFolder.newFile("small.bin").apply { writeText("small bytes") }
        val outcome = Sha256FileFingerprinter().quickFingerprint(file.metadata())

        assertEquals(
            "5dc1b0573d503cec260e08838d6117381a3407bdb7bcab29a3135dae7639b232",
            outcome.successHash(),
        )
    }

    @Test
    fun `large quick fingerprint reads first and last 64 KiB while full hash streams all bytes`() = runTest {
        val size = 128 * 1024 + 1
        val firstBytes = ByteArray(size) { index -> if (index < 64 * 1024 || index >= size - 64 * 1024) 7 else 1 }
        val secondBytes = firstBytes.copyOf().also { it[64 * 1024] = 2 }
        val first = temporaryFolder.newFile("large-a.bin").apply { writeBytes(firstBytes) }
        val second = temporaryFolder.newFile("large-b.bin").apply { writeBytes(secondBytes) }
        val fingerprinter = Sha256FileFingerprinter()

        assertEquals(
            fingerprinter.quickFingerprint(first.metadata()).successHash(),
            fingerprinter.quickFingerprint(second.metadata()).successHash(),
        )
        assertNotEquals(
            fingerprinter.fullFingerprint(first.metadata()).successHash(),
            fingerprinter.fullFingerprint(second.metadata()).successHash(),
        )
    }

    @Test
    fun `full fingerprint uses bounded repeated reads for a large file`() = runTest {
        val file = temporaryFolder.newFile("streamed.bin").apply { writeBytes(ByteArray(1024 * 1024) { 42 }) }
        val source = RecordingContentSource(PathFileContentSource())

        val outcome = Sha256FileFingerprinter(source).fullFingerprint(file.metadata())

        assertTrue(outcome is FingerprintOutcome.Success)
        assertTrue(source.readCalls > 2)
        assertTrue(source.maximumRequestedBytes <= 64 * 1024)
    }

    @Test
    fun `quick hashing propagates cancellation between reads`() = runTest {
        val file = temporaryFolder.newFile("cancel-quick.bin").apply {
            writeBytes(ByteArray(128 * 1024 + 1) { 9 })
        }
        lateinit var hashing: Deferred<FingerprintOutcome>
        val source = RecordingContentSource(PathFileContentSource()) { hashing.cancel() }
        hashing = async { Sha256FileFingerprinter(source).quickFingerprint(file.metadata()) }

        assertCancelled(hashing)
    }

    @Test
    fun `full hashing propagates cancellation between reads`() = runTest {
        val file = temporaryFolder.newFile("cancel-full.bin").apply {
            writeBytes(ByteArray(1024 * 1024) { 9 })
        }
        lateinit var hashing: Deferred<FingerprintOutcome>
        val source = RecordingContentSource(PathFileContentSource()) { hashing.cancel() }
        hashing = async { Sha256FileFingerprinter(source).fullFingerprint(file.metadata()) }

        assertCancelled(hashing)
    }

    @Test
    fun `final verification compares actual bytes instead of trusting hashes`() = runTest {
        val first = temporaryFolder.newFile("compare-a.bin").apply { writeText("same-size-a") }
        val second = temporaryFolder.newFile("compare-b.bin").apply { writeText("same-size-b") }
        val third = temporaryFolder.newFile("compare-c.bin").apply { writeText("same-size-a") }
        val fingerprinter = Sha256FileFingerprinter()

        assertEquals(ContentComparisonOutcome.Different, fingerprinter.compare(first.metadata(), second.metadata()))
        assertEquals(ContentComparisonOutcome.Equal, fingerprinter.compare(first.metadata(), third.metadata()))
    }

    private suspend fun assertCancelled(hashing: Deferred<FingerprintOutcome>) {
        try {
            hashing.await()
            throw AssertionError("Hashing returned after cancellation")
        } catch (_: CancellationException) {
            assertTrue(hashing.isCancelled)
        }
    }

    private fun FingerprintOutcome.successHash(): String =
        (this as FingerprintOutcome.Success).sha256

    private fun File.metadata(): FileMetadata = FileMetadata(
        id = canonicalPath,
        canonicalPath = canonicalPath,
        displayName = name,
        extension = extension,
        mimeType = null,
        category = FileCategory.DOCUMENTS,
        sizeBytes = length(),
        lastModifiedMillis = lastModified(),
        storageVolume = "primary",
        parentDirectory = parentFile!!.canonicalPath,
        isReadable = true,
        isWritable = canWrite(),
        contentUri = null,
        isFavorite = null,
        isTrashed = null,
    )

    private class RecordingContentSource(
        private val delegate: FileContentSource,
        private val onFirstRead: () -> Unit = {},
    ) : FileContentSource {
        var readCalls: Int = 0
            private set
        var maximumRequestedBytes: Int = 0
            private set

        override fun snapshot(file: FileMetadata): FileSnapshot? = delegate.snapshot(file)

        override fun open(file: FileMetadata): SeekableByteChannel =
            RecordingChannel(delegate.open(file))

        private inner class RecordingChannel(
            private val channel: SeekableByteChannel,
        ) : SeekableByteChannel by channel {
            override fun read(destination: ByteBuffer): Int {
                maximumRequestedBytes = maxOf(maximumRequestedBytes, destination.remaining())
                readCalls += 1
                if (readCalls == 1) onFirstRead()
                return channel.read(destination)
            }
        }
    }
}
