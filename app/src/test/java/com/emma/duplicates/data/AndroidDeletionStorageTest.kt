package com.emma.duplicates.data

import android.database.sqlite.SQLiteException
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.DirectDeletionResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AndroidDeletionStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing path on an unavailable volume is not confirmed absent`() =
        runTest {
            val missing = File(temporaryFolder.root, "unmounted/missing.pdf").deletionFile()
            var available = false
            val storage =
                AndroidDeletionStorage(
                    recovery =
                        recoveryRepository(
                            File(temporaryFolder.root, "recovery"),
                        ),
                    isStorageAvailable = {
                        available
                    },
                ) { }

            val unavailable = storage.snapshot(missing)!!
            assertFalse(unavailable.isReadable)
            assertFalse(unavailable.isWritable)

            available = true
            assertNull(storage.snapshot(missing))
        }

    @Test
    fun `volume removed during the missing file check is not confirmed absent`() =
        runTest {
            val missing = File(temporaryFolder.root, "missing.pdf").deletionFile()
            var checks = 0
            val storage =
                AndroidDeletionStorage(
                    recovery =
                        recoveryRepository(
                            File(temporaryFolder.root, "recovery"),
                        ),
                    isStorageAvailable = {
                        ++checks ==
                            1
                    },
                ) { }

            assertFalse(storage.snapshot(missing)!!.isReadable)
            assertEquals(2, checks)
        }

    @Test
    fun `real file boundary snapshots hashes compares deletes and verifies absence`() =
        runTest {
            val first = temporaryFolder.newFile("first.txt").apply { writeText("abc") }
            val same = temporaryFolder.newFile("same.txt").apply { writeText("abc") }
            val different = temporaryFolder.newFile("different.txt").apply { writeText("abd") }
            val storage =
                AndroidDeletionStorage(
                    recovery = recoveryRepository(File(temporaryFolder.root, "recovery")),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                ) {
                }
            val firstFile = first.deletionFile()

            val snapshot = storage.snapshot(firstFile)!!
            assertEquals(3L, snapshot.sizeBytes)
            assertTrue(snapshot.isReadable)
            assertTrue(snapshot.isWritable)
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                storage.fullHash(firstFile),
            )
            assertTrue(storage.contentsEqual(firstFile, same.deletionFile()))
            assertFalse(storage.contentsEqual(firstFile, different.deletionFile()))

            assertEquals(DirectDeletionResult.DELETED, storage.deleteDirect(firstFile) { true })
            assertNull(storage.snapshot(firstFile))
            assertEquals(DirectDeletionResult.FAILED, storage.deleteDirect(firstFile) { true })
        }

    @Test
    fun `successful direct deletion refreshes the deleted path instead of deleting a cached URI`() =
        runTest {
            val file = temporaryFolder.newFile("indexed-document.pdf").apply { writeText("content") }
            val contentUri = "content://media/external/file/42"
            var refreshedPath: String? = null
            val storage =
                AndroidDeletionStorage(recovery = recoveryRepository(File(temporaryFolder.root, "recovery"))) { path ->
                    assertFalse(file.exists())
                    refreshedPath = path
                }

            val result = storage.deleteDirect(file.deletionFile(contentUri = contentUri)) { true }

            assertEquals(DirectDeletionResult.DELETED, result)
            assertEquals(file.canonicalPath, refreshedPath)
        }

    @Test
    fun `failed direct deletion does not remove a MediaStore row`() =
        runTest {
            val missingFile = File(temporaryFolder.root, "missing.pdf")
            val removedContentUris = mutableListOf<String>()
            val storage =
                AndroidDeletionStorage(recovery = recoveryRepository(File(temporaryFolder.root, "recovery"))) { uri ->
                    removedContentUris +=
                        uri
                }

            val result =
                storage.deleteDirect(
                    missingFile.deletionFile(contentUri = "content://media/external/file/43"),
                    verifyStaged = { true },
                )

            assertEquals(DirectDeletionResult.FAILED, result)
            assertTrue(removedContentUris.isEmpty())
        }

    @Test
    fun `successful direct deletion without a content URI does not remove a MediaStore row`() =
        runTest {
            val file = temporaryFolder.newFile("unindexed-document.pdf").apply { writeText("content") }
            val removedContentUris = mutableListOf<String>()
            val storage =
                AndroidDeletionStorage(recovery = recoveryRepository(File(temporaryFolder.root, "recovery"))) { uri ->
                    removedContentUris +=
                        uri
                }

            val result = storage.deleteDirect(file.deletionFile()) { true }

            assertEquals(DirectDeletionResult.DELETED, result)
            assertTrue(removedContentUris.isEmpty())
        }

    @Test
    fun `MediaStore failures do not change a successful direct deletion result`() =
        runTest {
            val failures =
                listOf<RuntimeException>(
                    SecurityException("denied"),
                    IllegalArgumentException("invalid URI"),
                    SQLiteException("database failure"),
                )

            failures.forEachIndexed { index, failure ->
                val file = temporaryFolder.newFile("failure-$index.pdf").apply { writeText("content") }
                val storage =
                    AndroidDeletionStorage(
                        recovery = recoveryRepository(File(temporaryFolder.root, "recovery")),
                    ) { throw failure }

                val result =
                    storage.deleteDirect(
                        file.deletionFile(contentUri = "content://media/external/file/${100 + index}"),
                        verifyStaged = { true },
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
