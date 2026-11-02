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
}
