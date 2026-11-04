package com.emma.duplicates

import android.content.Context
import android.os.Environment
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.data.RecoveryRepository
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.DirectDeletionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecoveryStorageTest {
    @Test
    fun unreadablePayloadHasUnknownSizeAndNoPreview() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val database = Room.inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java).build()
            val folder = recoveryFolder(context, "read")
            val root = requireNotNull(folder.parentFile)
            val id = folder.name
            val payload = File(folder, "payload").apply { writeText("private fixture") }
            val record =
                com.emma.duplicates.core.database
                    .RecoveryFile(id, "/original/photo.jpg", payload.path, 1)
            val repository = RecoveryRepository(database.recoveryDao(), { listOf(root) }, { root }, { true })
            try {
                assertTrue(repository.describe(listOf(record)).single().available)
                check(payload.setReadable(false, false))
                assertFalse(
                    java.nio.file.Files
                        .isReadable(payload.toPath()),
                )
                val description = repository.describe(listOf(record)).single()
                assertFalse(description.available)
                assertEquals(null, description.sizeBytes)
                assertEquals(null, description.previewPath)
            } finally {
                check(payload.setReadable(true, true))
                check(payload.delete())
                check(folder.delete())
                check(root.delete())
                database.close()
            }
        }

    @Test
    fun permanentDeleteRejectsSymlinkAndForeignPath() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val database = Room.inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java).build()
            val directory =
                File(
                    context.cacheDir.canonicalFile,
                    "recovery-safety-${UUID.randomUUID()}",
                ).apply { check(mkdir()) }
            val root = File(directory, "recovery").apply { check(mkdir()) }
            val foreign = File(directory, "foreign").apply { check(mkdir()) }
            val source = File(foreign, "payload").apply { writeText("must survive") }
            val id = UUID.randomUUID().toString()
            val link = File(root, id)
            val dao = database.recoveryDao()
            val repository = RecoveryRepository(dao, { listOf(root) }, { root }, { true })
            try {
                java.nio.file.Files
                    .createSymbolicLink(link.toPath(), foreign.toPath())
                val record =
                    com.emma.duplicates.core.database
                        .RecoveryFile(id, source.path, File(link, "payload").path, 1)
                dao.insert(record)
                assertFalse(repository.deletePermanently(id))
                assertEquals("must survive", source.readText())
                assertEquals(record, dao.get(id))
                assertFalse(repository.describe(listOf(record)).single().available)
                java.nio.file.Files
                    .delete(link.toPath())
                dao.delete(id)
                dao.insert(record.copy(storedPath = source.path))
                assertFalse(repository.deletePermanently(id))
                assertEquals("must survive", source.readText())
            } finally {
                java.nio.file.Files
                    .deleteIfExists(link.toPath())
                source.delete()
                foreign.delete()
                root.delete()
                directory.delete()
                database.close()
            }
        }

    @Test
    fun permanentDeleteCanFinishAfterPayloadWasAlreadyRemoved() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val database = Room.inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java).build()
            val folder = recoveryFolder(context, "retry")
            val root = requireNotNull(folder.parentFile)
            val id = folder.name
            val dao = database.recoveryDao()
            val record =
                com.emma.duplicates.core.database.RecoveryFile(
                    id,
                    "/unused/original",
                    File(folder, "payload").path,
                    1,
                )
            val repository = RecoveryRepository(dao, { listOf(root) }, { root }, { true })
            try {
                dao.insert(record)
                assertTrue(repository.deletePermanently(id))
                assertEquals(null, dao.get(id))
                assertFalse(folder.exists())
            } finally {
                folder.delete()
                root.delete()
                database.close()
            }
        }

    @Test
    fun sharedStorageFileCanBeStagedAndRestoredOnItsOriginalVolume() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val database = Room.inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java).build()
            val directory =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "duplicates-test-${UUID.randomUUID()}",
                )
            check(directory.mkdir())
            val source = File(directory, "selected.txt").apply { writeText("device recovery fixture") }
            val repository = RecoveryRepository(context, database.recoveryDao())
            try {
                val file =
                    DeletionFile(
                        "fixture",
                        canonicalPath = source.path,
                        sizeBytes = source.length(),
                        lastModifiedMillis = source.lastModified(),
                        selectedForDeletion = true,
                    )
                val result =
                    repository.move(file) { staged ->
                        File(staged.canonicalPath).readText() ==
                            "device recovery fixture"
                    }
                assertEquals(DirectDeletionResult.DELETED, result)
                assertFalse(source.exists())
                val saved =
                    database
                        .recoveryDao()
                        .observeFiles()
                        .first()
                        .single()
                assertEquals("device recovery fixture", File(saved.storedPath).readText())
                assertEquals(source.path, repository.restore(saved.id))
                assertEquals("device recovery fixture", source.readText())
                assertTrue(File(saved.storedPath).exists())
                assertPermanentDeletionPreservesOriginal(repository, saved, source, database)
            } finally {
                database.recoveryDao().observeFiles().first().forEach {
                    val payload = File(it.storedPath)
                    payload.delete()
                    payload.parentFile?.delete()
                }
                source.delete()
                directory.delete()
                database.close()
            }
        }

    private fun recoveryFolder(
        context: Context,
        purpose: String,
    ): File {
        val root =
            File(context.cacheDir.canonicalFile, "recovery-$purpose-${UUID.randomUUID()}").apply {
                check(mkdir())
            }
        return File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
    }

    private suspend fun assertPermanentDeletionPreservesOriginal(
        repository: RecoveryRepository,
        saved: com.emma.duplicates.core.database.RecoveryFile,
        source: File,
        database: DuplicatesDatabase,
    ) {
        val restoredHash =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(source.readBytes())
        assertTrue(repository.deletePermanently(saved.id))
        assertFalse(File(saved.storedPath).exists())
        assertTrue(
            database
                .recoveryDao()
                .observeFiles()
                .first()
                .isEmpty(),
        )
        org.junit.Assert.assertArrayEquals(
            restoredHash,
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(source.readBytes()),
        )
    }
}
