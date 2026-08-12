package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.ExclusionType
import com.emma.duplicates.core.model.FileCategory
import com.emma.duplicates.core.model.ScanExclusion
import com.emma.duplicates.core.model.StorageVolumeRoot
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDiscoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `mounted roots produce complete immutable metadata and stable IDs`() = runTest {
        val primary = temporaryFolder.newFolder("primary")
        val removable = temporaryFolder.newFolder("removable")
        val photo = primary.resolve("Camera/Photo.JPG").createBytes(byteArrayOf(1, 2, 3))
        removable.resolve("Music/song.mp3").createBytes(byteArrayOf(4, 5))
        photo.setLastModified(1_700_000_000_000L)

        val discovery = FileDiscovery(
            metadataReader = FileMetadataReader { file ->
                PlatformFileMetadata(
                    mimeType = if (file.extension.equals("jpg", true)) "image/jpeg" else "audio/mpeg",
                    contentUri = "content://media/${file.name}",
                    isFavorite = file == photo,
                    isTrashed = false,
                )
            },
        )
        val roots = listOf(
            StorageVolumeRoot(primary, "primary"),
            StorageVolumeRoot(removable, "usb-1"),
        )

        val first = discovery.discover(roots)
        val second = discovery.discover(roots)

        assertEquals(listOf("Photo.JPG", "song.mp3"), first.files.map { it.displayName })
        val foundPhoto = first.files.first { it.displayName == "Photo.JPG" }
        assertEquals(photo.canonicalPath, foundPhoto.canonicalPath)
        assertEquals("jpg", foundPhoto.extension)
        assertEquals("image/jpeg", foundPhoto.mimeType)
        assertEquals(FileCategory.PHOTOS, foundPhoto.category)
        assertEquals(3L, foundPhoto.sizeBytes)
        assertEquals(photo.lastModified(), foundPhoto.lastModifiedMillis)
        assertEquals("primary", foundPhoto.storageVolume)
        assertEquals(photo.parentFile!!.canonicalPath, foundPhoto.parentDirectory)
        assertTrue(foundPhoto.isReadable)
        assertEquals(photo.canWrite(), foundPhoto.isWritable)
        assertEquals("content://media/Photo.JPG", foundPhoto.contentUri)
        assertEquals(true, foundPhoto.isFavorite)
        assertEquals(false, foundPhoto.isTrashed)
        assertEquals(
            first.files.map { it.id },
            second.files.map { it.id },
        )
        assertNotEquals(first.files[0].id, first.files[1].id)
    }

    @Test
    fun `progress reports discovered files and bytes instead of visited directories`() = runTest {
        val root = temporaryFolder.newFolder("progress")
        root.resolve("first.bin").createBytes(byteArrayOf(1, 2, 3))
        root.resolve("nested/second.bin").createBytes(byteArrayOf(4, 5))
        val updates = mutableListOf<FileDiscoveryProgress>()

        val result = FileDiscovery().discover(
            roots = listOf(StorageVolumeRoot(root, "primary")),
            onProgress = updates::add,
        )

        assertTrue(updates.isNotEmpty())
        assertEquals(0, updates.first().discoveredFileCount)
        assertEquals(0L, updates.first().scannedByteCount)
        assertEquals(2, updates.last().discoveredFileCount)
        assertEquals(5L, updates.last().scannedByteCount)
        assertEquals(result.files.size, updates.last().discoveredFileCount)
        assertTrue(updates.zipWithNext().all { (before, after) ->
            before.discoveredFileCount <= after.discoveredFileCount &&
                before.scannedByteCount <= after.scannedByteCount
        })
    }

    @Test
    fun `default filters skip zero bytes exclusions hidden technical and blocked Android folders`() = runTest {
        val root = temporaryFolder.newFolder("storage")
        root.resolve("visible.txt").createText("keep")
        root.resolve("empty.txt").createNewFile()
        val excludedFile = root.resolve("excluded.txt").createText("skip")
        val excludedFolder = root.resolve("ExcludedFolder").apply { mkdirs() }
        excludedFolder.resolve("nested.txt").createText("skip")
        root.resolve(".hidden/secret.txt").createText("skip")
        root.resolve("cache/technical.bin").createText("skip")
        root.resolve(".Trash/deleted.txt").createText("skip")
        root.resolve("Android/data/app/private.txt").createText("skip")
        root.resolve("Android/obb/app/package.obb").createText("skip")
        root.resolve("Android/media/app/photo.jpg").createText("keep-media")

        val result = FileDiscovery().discover(
            roots = listOf(StorageVolumeRoot(root, "primary")),
            options = FileDiscoveryOptions(
                exclusions = setOf(
                    ScanExclusion(excludedFile.canonicalPath, ExclusionType.FILE),
                    ScanExclusion(excludedFolder.canonicalPath, ExclusionType.FOLDER),
                ),
            ),
        )

        assertEquals(setOf("visible.txt", "photo.jpg"), result.files.map { it.displayName }.toSet())
        assertTrue(result.skippedFileCount >= 2)
        assertEquals(0, result.errorCount)
    }

    @Test
    fun `hidden and technical folders are included only when their filters are disabled`() = runTest {
        val root = temporaryFolder.newFolder("unfiltered")
        root.resolve(".hidden/secret.txt").createText("hidden")
        root.resolve("cache/technical.bin").createText("cache")
        root.resolve("Android/data/app/private.txt").createText("blocked")

        val result = FileDiscovery().discover(
            roots = listOf(StorageVolumeRoot(root, "primary")),
            options = FileDiscoveryOptions(
                scanHiddenFolders = true,
                ignoreSystemFolders = false,
            ),
        )

        assertEquals(setOf("secret.txt", "technical.bin"), result.files.map { it.displayName }.toSet())
    }

    @Test
    fun `restricted Android data remains blocked even if supplied as a root`() = runTest {
        val storage = temporaryFolder.newFolder("restricted-root")
        val androidData = storage.resolve("Android/data").apply { mkdirs() }
        androidData.resolve("private.txt").createText("blocked")

        val result = FileDiscovery().discover(
            roots = listOf(StorageVolumeRoot(androidData, "primary")),
            options = FileDiscoveryOptions(scanHiddenFolders = true, ignoreSystemFolders = false),
        )

        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `numbered recycle bin folders stay excluded when hidden folders are enabled`() = runTest {
        val root = temporaryFolder.newFolder("recycle-prefix")
        root.resolve(".Trash-1234/deleted.txt").createText("blocked")

        val result = FileDiscovery().discover(
            roots = listOf(StorageVolumeRoot(root, "primary")),
            options = FileDiscoveryOptions(scanHiddenFolders = true, ignoreSystemFolders = false),
        )

        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `trashed and disabled-category files are not candidates`() = runTest {
        val root = temporaryFolder.newFolder("categories")
        root.resolve("photo.jpg").createText("photo")
        root.resolve("video.mp4").createText("video")
        root.resolve("trashed.jpg").createText("trash")
        val discovery = FileDiscovery(
            metadataReader = FileMetadataReader { file ->
                PlatformFileMetadata(
                    mimeType = when (file.extension) {
                        "jpg" -> "image/jpeg"
                        else -> "video/mp4"
                    },
                    isTrashed = file.name == "trashed.jpg",
                )
            },
        )

        val result = discovery.discover(
            roots = listOf(StorageVolumeRoot(root, "primary")),
            options = FileDiscoveryOptions(enabledCategories = setOf(FileCategory.PHOTOS)),
        )

        assertEquals(listOf("photo.jpg"), result.files.map { it.displayName })
        assertEquals(2, result.skippedFileCount)
    }

    @Test
    fun `application directories are excluded even when system filtering is disabled`() = runTest {
        val root = temporaryFolder.newFolder("app-own")
        val appFiles = root.resolve("Android/media/com.emma.duplicates/files").apply { mkdirs() }
        appFiles.resolve("private.txt").createText("skip")
        root.resolve("Android/media/another.app/user.jpg").createText("keep")

        val result = FileDiscovery().discover(
            roots = listOf(StorageVolumeRoot(root, "primary")),
            options = FileDiscoveryOptions(
                ignoreSystemFolders = false,
                applicationDirectories = setOf(appFiles.canonicalPath),
            ),
        )

        assertEquals(listOf("user.jpg"), result.files.map { it.displayName })
    }

    @Test
    fun `traversal cancellation is propagated without partial success`() = runTest {
        val root = temporaryFolder.newFolder("cancel")
        repeat(20) { index -> root.resolve("folder-$index/file.txt").createText("$index") }
        var visits = 0

        val scan = async {
            FileDiscovery().discover(
                roots = listOf(StorageVolumeRoot(root, "primary")),
                onProgress = {
                    visits += 1
                    if (visits == 3) currentCoroutineContext()[Job]!!.cancel()
                },
            )
        }

        try {
            scan.await()
            throw AssertionError("Traversal returned after cancellation")
        } catch (_: CancellationException) {
            assertTrue(scan.isCancelled)
            assertEquals(3, visits)
        }
    }

    @Test
    fun `disappearing and unreadable files are counted while readable files continue`() = runTest {
        val root = temporaryFolder.newFolder("errors")
        root.resolve("good.txt").createText("good")
        root.resolve("gone.txt").createText("gone")
        root.resolve("denied.txt").createText("denied")
        val discovery = FileDiscovery(
            metadataReader = FileMetadataReader { file ->
                when (file.name) {
                    "gone.txt" -> throw FileNotFoundException(file.name)
                    "denied.txt" -> throw SecurityException(file.name)
                    else -> PlatformFileMetadata(mimeType = "text/plain")
                }
            },
        )

        val result = discovery.discover(listOf(StorageVolumeRoot(root, "primary")))

        assertEquals(listOf("good.txt"), result.files.map { it.displayName })
        assertEquals(2, result.skippedFileCount)
        assertEquals(2, result.errorCount)
    }

    @Test
    fun `overlapping roots and directory links never duplicate traversal`() = runTest {
        val root = temporaryFolder.newFolder("cycles")
        val content = root.resolve("content").apply { mkdirs() }
        content.resolve("one.txt").createText("one")
        val link = root.resolve("content-link").toPath()
        try {
            Files.createSymbolicLink(link, content.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        val result = FileDiscovery().discover(
            roots = listOf(
                StorageVolumeRoot(root, "primary"),
                StorageVolumeRoot(content, "primary"),
            ),
        )

        assertEquals(listOf("one.txt"), result.files.map { it.displayName })
        assertFalse(result.files.any { it.canonicalPath.contains("content-link") })
    }

    private fun File.resolve(relativePath: String): File = File(this, relativePath)

    private fun File.createText(value: String): File = apply {
        parentFile?.mkdirs()
        writeText(value, Charsets.UTF_8)
    }

    private fun File.createBytes(value: ByteArray): File = apply {
        parentFile?.mkdirs()
        writeBytes(value)
    }
}
