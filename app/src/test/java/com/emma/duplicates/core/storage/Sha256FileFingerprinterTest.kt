package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.FileCategory
import com.emma.duplicates.core.model.FileMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

class Sha256FileFingerprinterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `opening rejects a symlink swapped in after the snapshot`() {
        val file = temporaryFolder.newFile("candidate.bin").apply { writeText("candidate") }
        val target = temporaryFolder.newFile("target.bin").apply { writeText("other content") }
        val metadata = file.metadata()
        val path = file.toPath()
        val source = PathFileContentSource()

        assertEquals(FileSnapshot(metadata.sizeBytes, metadata.lastModifiedMillis), source.snapshot(metadata))
        Files.delete(path)
        Files.createSymbolicLink(path, target.toPath())

        assertThrows(IOException::class.java) { source.open(metadata).close() }
        assertTrue(Files.isSymbolicLink(path))
        assertEquals("other content", target.readText())
    }

    @Test
    fun `small quick fingerprint includes big-endian file size and complete bytes`() =
        runTest {
            val file = temporaryFolder.newFile("small.bin").apply { writeText("small bytes") }
            val outcome = Sha256FileFingerprinter().quickFingerprint(file.metadata())

            assertEquals(
                "5dc1b0573d503cec260e08838d6117381a3407bdb7bcab29a3135dae7639b232",
                outcome.successHash(),
            )
        }

    @Test
    fun `full fingerprint matches SHA-256 of complete bytes`() =
        runTest {
            val file = temporaryFolder.newFile("full.bin").apply { writeText("small bytes") }

            assertEquals(
                "f6a900c70e08adc80c0b65b5a6836df075ef04835623a3819ec221f791ce8484",
                Sha256FileFingerprinter().fullFingerprint(file.metadata()).successHash(),
            )
        }

    @Test
    fun `both fingerprints reject a snapshot changed during hashing`() =
        runTest {
            val file = temporaryFolder.newFile("changed.bin").apply { writeText("same bytes") }.metadata()
            for (quick in listOf(true, false)) {
                var snapshotReads = 0
                val source =
                    object : FileContentSource by PathFileContentSource() {
                        override fun snapshot(file: FileMetadata): FileSnapshot =
                            FileSnapshot(file.sizeBytes, file.lastModifiedMillis + snapshotReads++)
                    }
                val fingerprinter = Sha256FileFingerprinter(source, StandardTestDispatcher(testScheduler))
                val outcome = if (quick) fingerprinter.quickFingerprint(file) else fingerprinter.fullFingerprint(file)

                assertEquals(
                    FingerprintOutcome.Failure(FileProcessingIssue(file.id, FileIssueReason.CHANGED)),
                    outcome,
                )
            }
        }

    @Test
    fun `large quick fingerprint reads first and last 64 KiB while full hash streams all bytes`() =
        runTest {
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
    fun `full fingerprint uses bounded repeated reads for a large file`() =
        runTest {
            val file = temporaryFolder.newFile("streamed.bin").apply { writeBytes(ByteArray(1024 * 1024) { 42 }) }
            val source = RecordingContentSource(PathFileContentSource())

            val outcome = Sha256FileFingerprinter(source).fullFingerprint(file.metadata())

            assertTrue(outcome is FingerprintOutcome.Success)
            assertTrue(source.readCalls > 2)
            assertTrue(source.maximumRequestedBytes <= 64 * 1024)
        }

    @Test
    fun `quick hashing propagates cancellation between reads`() =
        runTest {
            val file =
                temporaryFolder.newFile("cancel-quick.bin").apply {
                    writeBytes(ByteArray(128 * 1024 + 1) { 9 })
                }
            lateinit var hashing: Deferred<FingerprintOutcome>
            val source = RecordingContentSource(PathFileContentSource()) { hashing.cancel() }
            hashing = async { Sha256FileFingerprinter(source).quickFingerprint(file.metadata()) }

            assertCancelled(hashing)
        }

    @Test
    fun `full hashing propagates cancellation between reads`() =
        runTest {
            val file =
                temporaryFolder.newFile("cancel-full.bin").apply {
                    writeBytes(ByteArray(1024 * 1024) { 9 })
                }
            lateinit var hashing: Deferred<FingerprintOutcome>
            val source = RecordingContentSource(PathFileContentSource()) { hashing.cancel() }
            hashing = async { Sha256FileFingerprinter(source).fullFingerprint(file.metadata()) }

            assertCancelled(hashing)
        }

    @Test
    fun `final verification compares actual bytes instead of trusting hashes`() =
        runTest {
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

    @Test
    fun `opening failures retain their reason and close the first comparison channel`() =
        runTest {
            val first = temporaryFolder.newFile("first.bin").apply { writeText("same") }.metadata()
            val second = temporaryFolder.newFile("second.bin").apply { writeText("same") }.metadata()
            val failures =
                listOf(
                    FileNotFoundException() to FileIssueReason.MISSING,
                    NoSuchFileException("missing") to FileIssueReason.MISSING,
                    AccessDeniedException("denied") to FileIssueReason.UNREADABLE,
                    SecurityException() to FileIssueReason.UNREADABLE,
                    IOException() to FileIssueReason.IO_ERROR,
                )
            for ((error, reason) in failures) {
                val delegate = PathFileContentSource()
                lateinit var firstChannel: SeekableByteChannel
                val source =
                    object : FileContentSource by delegate {
                        override fun open(file: FileMetadata): SeekableByteChannel {
                            if (file.id == second.id) throw error
                            return delegate.open(file).also { firstChannel = it }
                        }
                    }
                val fingerprinter = Sha256FileFingerprinter(source, StandardTestDispatcher(testScheduler))
                val issue = FileProcessingIssue(second.id, reason)

                assertEquals(FingerprintOutcome.Failure(issue), fingerprinter.quickFingerprint(second))
                assertEquals(FingerprintOutcome.Failure(issue), fingerprinter.fullFingerprint(second))
                assertEquals(ContentComparisonOutcome.Failure(listOf(issue)), fingerprinter.compare(first, second))
                assertFalse(firstChannel.isOpen)
            }
        }

    private fun FingerprintOutcome.successHash(): String = (this as FingerprintOutcome.Success).sha256

    private fun File.metadata(): FileMetadata =
        FileMetadata(
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

        override fun open(file: FileMetadata): SeekableByteChannel = RecordingChannel(delegate.open(file))

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
