package com.emma.duplicates.data

import android.database.sqlite.SQLiteException
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.DirectDeletionResult
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidDeletionStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `real file boundary snapshots hashes compares deletes and verifies absence`() = runTest {
        val first = temporaryFolder.newFile("first.txt").apply { writeText("abc") }
        val same = temporaryFolder.newFile("same.txt").apply { writeText("abc") }
        val different = temporaryFolder.newFile("different.txt").apply { writeText("abd") }
        val storage = AndroidDeletionStorage { }
        val firstFile = first.deletionFile()

        val snapshot = storage.snapshot(firstFile)!!
        assertEquals(3L, snapshot.sizeBytes)
        assertTrue(snapshot.isReadable)
        assertTrue(snapshot.isWritable)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", storage.fullHash(firstFile))
        assertTrue(storage.contentsEqual(firstFile, same.deletionFile()))
        assertFalse(storage.contentsEqual(firstFile, different.deletionFile()))

        assertEquals(DirectDeletionResult.DELETED, storage.deleteDirect(firstFile))
        assertNull(storage.snapshot(firstFile))
        assertEquals(DirectDeletionResult.FAILED, storage.deleteDirect(firstFile))
    }

    @Test
    fun `successful direct deletion removes its MediaStore row after the file is absent`() = runTest {
        val file = temporaryFolder.newFile("indexed-document.pdf").apply { writeText("content") }
        val contentUri = "content://media/external/file/42"
        var removedContentUri: String? = null
        val storage = AndroidDeletionStorage { uri ->
            assertFalse(file.exists())
            removedContentUri = uri
        }

        val result = storage.deleteDirect(file.deletionFile(contentUri = contentUri))

        assertEquals(DirectDeletionResult.DELETED, result)
        assertEquals(contentUri, removedContentUri)
    }

    @Test
    fun `failed direct deletion does not remove a MediaStore row`() = runTest {
        val missingFile = File(temporaryFolder.root, "missing.pdf")
        val removedContentUris = mutableListOf<String>()
        val storage = AndroidDeletionStorage { uri -> removedContentUris += uri }

        val result =
            storage.deleteDirect(
                missingFile.deletionFile(contentUri = "content://media/external/file/43"),
            )

        assertEquals(DirectDeletionResult.FAILED, result)
        assertTrue(removedContentUris.isEmpty())
    }

    @Test
    fun `successful direct deletion without a content URI does not remove a MediaStore row`() = runTest {
        val file = temporaryFolder.newFile("unindexed-document.pdf").apply { writeText("content") }
        val removedContentUris = mutableListOf<String>()
        val storage = AndroidDeletionStorage { uri -> removedContentUris += uri }

        val result = storage.deleteDirect(file.deletionFile())

        assertEquals(DirectDeletionResult.DELETED, result)
        assertTrue(removedContentUris.isEmpty())
    }

    @Test
    fun `MediaStore failures do not change a successful direct deletion result`() = runTest {
        val failures =
            listOf<RuntimeException>(
                SecurityException("denied"),
                IllegalArgumentException("invalid URI"),
                SQLiteException("database failure"),
            )

        failures.forEachIndexed { index, failure ->
            val file = temporaryFolder.newFile("failure-$index.pdf").apply { writeText("content") }
            val storage = AndroidDeletionStorage { throw failure }

            val result =
                storage.deleteDirect(
                    file.deletionFile(contentUri = "content://media/external/file/${100 + index}"),
                )

            assertEquals(DirectDeletionResult.DELETED, result)
            assertFalse(file.exists())
        }
    }

    private fun File.deletionFile(contentUri: String? = null) =
        DeletionFile(
            id = name,
            canonicalPath = canonicalPath,
            sizeBytes = length(),
            lastModifiedMillis = lastModified(),
            contentUri = contentUri,
            selectedForDeletion = true,
        )
}
