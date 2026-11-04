package com.emma.duplicates.data

import com.emma.duplicates.core.database.RecoveryDao
import com.emma.duplicates.core.database.RecoveryFile
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.DirectDeletionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class RecoveryRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun describeUsesOnlyAvailableOwnedReadablePayloads() =
        runTest {
            val source = temporaryFolder.newFile("photo.jpg").apply { writeText("payload") }
            val dao = FakeRecoveryDao()
            var mounted = true
            val repository = mountedRepository(dao) { mounted }
            repository.move(source.deletionFile()) { true }
            val record = dao.entries.value.single()
            assertEquals(
                RecoveryEntry(record, 7L, record.storedPath, true),
                repository.describe(listOf(record)).single(),
            )
            mounted = false
            assertEquals(RecoveryEntry(record, null, null, false), repository.describe(listOf(record)).single())
            mounted = true
            val foreign = record.copy(storedPath = source.path)
            assertEquals(RecoveryEntry(foreign, null, null, false), repository.describe(listOf(foreign)).single())
            File(record.storedPath).delete()
            assertEquals(RecoveryEntry(record, null, null, false), repository.describe(listOf(record)).single())
        }

    @Test
    fun unavailableVolumeKeepsRecoveryRecord() =
        runTest {
            val source = temporaryFolder.newFile("selected").apply { writeText("preserve") }
            val dao = FakeRecoveryDao()
            var mounted = true
            val repository = mountedRepository(dao) { mounted }
            repository.move(source.deletionFile()) { true }
            val record = dao.entries.value.single()
            mounted = false
            assertFalse(repository.deletePermanently(record.id))
            assertEquals(listOf(record), dao.entries.value)
            assertEquals("preserve", File(record.storedPath).readText())
        }

    @Test
    fun permanentDeleteRejectsForeignPath() =
        runTest {
            val original = temporaryFolder.newFile("original").apply { writeText("original") }
            val root = temporaryFolder.newFolder("recovery")
            val dao = FakeRecoveryDao()
            val repository = recoveryRepository(root, dao)
            val foreign =
                RecoveryFile(
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                    original.path,
                    original.path,
                    1,
                )
            dao.insert(foreign)
            assertFalse(repository.deletePermanently(foreign.id))
            assertEquals("original", original.readText())
            assertEquals(listOf(foreign), dao.entries.value)
        }

    @Test
    fun permanentDeleteRequiresSecureDirectorySupport() =
        runTest {
            val original = temporaryFolder.newFile("original").apply { writeText("preserve") }
            val root = temporaryFolder.newFolder("recovery")
            val dao = FakeRecoveryDao()
            val repository = recoveryRepository(root, dao)
            repository.move(original.deletionFile()) { true }
            val record = dao.entries.value.single()
            assertTrue(repository.describe(listOf(record)).single().available)
            assertEquals(original.path, repository.restore(record.id))
            val supported =
                java.nio.file.Files.newDirectoryStream(root.toPath()).use {
                    it is java.nio.file.SecureDirectoryStream<*>
                }
            assertEquals(supported, repository.deletePermanently(record.id))
            assertEquals(!supported, File(record.storedPath).exists())
            assertEquals(if (supported) emptyList() else listOf(record), dao.entries.value)
            assertEquals("preserve", original.readText())
        }

    @Test
    fun `replacement after the last comparison is retained and restored without expiry`() =
        runTest {
            val source = temporaryFolder.newFile("selected").apply { writeText("duplicate") }
            val dao = FakeRecoveryDao()
            val repository = recoveryRepository(temporaryFolder.newFolder("recovery"), dao)
            val selected = source.deletionFile()
            source.writeText("unique replacement")

            assertEquals(
                DirectDeletionResult.FAILED,
                repository.move(selected) {
                    File(it.canonicalPath).readText() ==
                        "duplicate"
                },
            )

            val record = dao.entries.value.single()
            assertNull(record.expiresAt)
            assertEquals("unique replacement", File(record.storedPath).readText())
            assertEquals("unique replacement", source.readText())
            repository.removeExpired()
            assertTrue(File(record.storedPath).exists())
        }

    @Test
    fun `failed verification preserves a concurrently recreated original and the recovery payload`() =
        runTest {
            val source = temporaryFolder.newFile("selected").apply { writeText("staged content") }
            val dao = FakeRecoveryDao()
            val repository = recoveryRepository(temporaryFolder.newFolder("recovery"), dao)

            assertEquals(
                DirectDeletionResult.FAILED,
                repository.move(source.deletionFile()) {
                    source.writeText("concurrent original")
                    false
                },
            )

            val record = dao.entries.value.single()
            assertNull(record.expiresAt)
            assertEquals("concurrent original", source.readText())
            assertEquals("staged content", File(record.storedPath).readText())
            val restored = temporaryFolder.root.listFiles()!!.single { it.name.startsWith("selected.restored-") }
            assertEquals("staged content", restored.readText())
        }

    @Test
    fun `cancellation after move leaves a durable recoverable payload`() =
        runTest {
            val source = temporaryFolder.newFile("selected").apply { writeText("content") }
            val dao = FakeRecoveryDao()
            val repository = recoveryRepository(temporaryFolder.newFolder("recovery"), dao)
            try {
                repository.move(source.deletionFile()) { throw CancellationException("interrupted") }
                error("Expected cancellation")
            } catch (_: CancellationException) {
                val record = dao.entries.value.single()
                assertNull(record.expiresAt)
                assertEquals("content", File(record.storedPath).readText())
                assertFalse(source.exists())
                assertEquals(source.path, repository.restore(record.id))
                assertEquals("content", source.readText())
            }
        }

    @Test
    fun `restoration never replaces an existing original and retains the payload`() =
        runTest {
            val source = temporaryFolder.newFile("selected").apply { writeText("original") }
            val dao = FakeRecoveryDao()
            val repository = recoveryRepository(temporaryFolder.newFolder("recovery"), dao)
            assertEquals(DirectDeletionResult.DELETED, repository.move(source.deletionFile()) { true })
            source.writeText("newer unique file")

            val record = dao.entries.value.single()
            val restored = repository.restore(record.id)!!

            assertEquals("newer unique file", source.readText())
            assertEquals("original", File(restored).readText())
            assertEquals("original", File(record.storedPath).readText())
        }

    @Test
    fun `only expired verified copies on available storage are removed`() =
        runTest {
            val source = temporaryFolder.newFile("selected").apply { writeText("content") }
            val root = temporaryFolder.newFolder("recovery")
            val dao = FakeRecoveryDao()
            var clock = 0L
            var mounted = true
            val repository = RecoveryRepository(dao, { listOf(root) }, { root }, { mounted }, now = { clock })
            repository.move(source.deletionFile()) { true }
            val record = dao.entries.value.single()
            clock = TimeUnit.DAYS.toMillis(29)
            repository.removeExpired()
            assertTrue(File(record.storedPath).exists())
            clock = TimeUnit.DAYS.toMillis(31)
            mounted = false
            repository.removeExpired()
            assertEquals(listOf(record), dao.entries.value)
            mounted = true
            repository.removeExpired()
            assertFalse(File(record.storedPath).exists())
            assertTrue(dao.entries.value.isEmpty())
        }

    @Test
    fun `missing recovery volume leaves source untouched`() =
        runTest {
            val source = temporaryFolder.newFile("selected").apply { writeText("content") }
            val dao = FakeRecoveryDao()
            val repository = RecoveryRepository(dao, { emptyList() }, { null }, { false })
            assertEquals(DirectDeletionResult.FAILED, repository.move(source.deletionFile()) { true })
            assertEquals("content", source.readText())
            assertTrue(dao.entries.value.isEmpty())
        }

    private fun mountedRepository(
        dao: RecoveryDao,
        mounted: () -> Boolean,
    ): RecoveryRepository {
        val root = temporaryFolder.newFolder("recovery")
        return RecoveryRepository(dao, { listOf(root) }, { root }, { mounted() })
    }

    private fun File.deletionFile() =
        DeletionFile(
            name,
            canonicalPath = path,
            sizeBytes = length(),
            lastModifiedMillis = lastModified(),
            selectedForDeletion = true,
        )
}

internal fun recoveryRepository(
    root: File,
    dao: RecoveryDao = FakeRecoveryDao(),
) = RecoveryRepository(dao, { listOf(root) }, { root }, { true })

internal class FakeRecoveryDao : RecoveryDao {
    val entries = MutableStateFlow<List<RecoveryFile>>(emptyList())

    override suspend fun insert(file: RecoveryFile) {
        entries.value += file
    }

    override fun observeFiles() = entries

    override suspend fun get(id: String) = entries.value.find { it.id == id }

    override suspend fun setExpiry(
        id: String,
        expiresAt: Long,
    ) {
        entries.value = entries.value.map { if (it.id == id) it.copy(expiresAt = expiresAt) else it }
    }

    override suspend fun expired(now: Long) = entries.value.filter { it.expiresAt != null && it.expiresAt <= now }

    override suspend fun delete(id: String) {
        entries.value = entries.value.filterNot { it.id == id }
    }
}
