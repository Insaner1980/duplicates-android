package com.emma.duplicates.data

import android.app.Application
import android.content.ContentProvider
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.core.database.IndexedFileEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PreviewFileProviderTest {
    private lateinit var context: Context
    private lateinit var provider: ContentProvider
    private lateinit var sharedDirectory: File
    private lateinit var removableDirectory: File

    @Before
    @Suppress("DEPRECATION")
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sharedDirectory = File(context.cacheDir.parentFile, "shared-volume/Download").apply { mkdirs() }
        removableDirectory =
            File(requireNotNull(sharedDirectory.parentFile).parentFile, "DuplicatesRemovableTests")
                .apply { mkdirs() }
        val storageManager = shadowOf(context.getSystemService(StorageManager::class.java))
        storageManager.resetStorageVolumeList()
        storageManager.addStorageVolume(
            StorageVolumeBuilder(
                "primary",
                requireNotNull(sharedDirectory.parentFile),
                "Internal storage",
                Process.myUserHandle(),
                Environment.MEDIA_MOUNTED,
            ).setIsPrimary(true).build(),
        )
        storageManager.addStorageVolume(
            StorageVolumeBuilder(
                "removable",
                removableDirectory,
                "SD card",
                Process.myUserHandle(),
                Environment.MEDIA_MOUNTED,
            ).setFsUuid("ABCD-1234").setIsPrimary(false).setIsRemovable(true).build(),
        )
        val info =
            requireNotNull(
                context.packageManager.resolveContentProvider(
                    "${context.packageName}.files",
                    PackageManager.GET_META_DATA,
                ),
            )
        provider =
            (Class.forName(info.name).getDeclaredConstructor().newInstance() as ContentProvider).apply {
                attachInfo(this@PreviewFileProviderTest.context, info)
            }
    }

    @After
    fun tearDown() {
        sharedDirectory.deleteRecursively()
        removableDirectory.deleteRecursively()
    }

    @Test
    fun `shared file remains readable directly without copying`() =
        runTest {
            val source = File(sharedDirectory, "report.pdf").apply { writeText("original content") }
            val uri = prepare(source)
            source.writeText("updated content")

            ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(provider.openFile(uri, "r"))).use {
                assertEquals("updated content", it.bufferedReader().readText())
            }
            assertEquals("application/pdf", provider.getType(uri))
            provider
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "_data"), null, null, null)
                .use { cursor ->
                    requireNotNull(cursor)
                    assertTrue(cursor.moveToFirst())
                    assertEquals(2, cursor.columnCount)
                    assertEquals("report.pdf", cursor.getString(0))
                    assertEquals(15L, cursor.getLong(1))
                }
            assertEquals("updated content", source.readText())
        }

    @Test
    fun `preview provider refuses write and truncation modes`() =
        runTest {
            val source = File(sharedDirectory, "report.pdf").apply { writeText("original content") }
            val uri = prepare(source)

            for (mode in listOf("w", "wt", "wa", "rw", "rwt")) {
                assertThrows(SecurityException::class.java) { provider.openFile(uri, mode)?.close() }
            }
            assertEquals("original content", source.readText())
        }

    @Test
    fun `application and restricted storage directories are not shareable`() =
        runTest {
            val root = requireNotNull(sharedDirectory.parentFile)
            val sources =
                listOf(
                    File(requireNotNull(context.externalCacheDir), "private.pdf"),
                    File(context.filesDir, "private.pdf"),
                    File(root, "Android/data/other.app/private.pdf"),
                    File(root, "Android/obb/other.app/private.pdf"),
                    File(root, "Android/media/${context.packageName}/private.pdf"),
                )
            for (source in sources) {
                requireNotNull(source.parentFile).mkdirs()
                source.writeText("private")
                try {
                    assertEquals(PreviewPreparation.Failed, FilePreviewLauncher(context).prepare(source.indexedFile()))
                } finally {
                    source.delete()
                }
            }
        }

    @Test
    fun `removable shared storage remains readable`() =
        runTest {
            val source = File(removableDirectory, "report.pdf").apply { writeText("SD card content") }
            val uri = prepare(source)

            ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(provider.openFile(uri, "r"))).use {
                assertEquals("SD card content", it.bufferedReader().readText())
            }
        }

    @Test
    fun `uri traversal cannot escape the mounted volume`() =
        runTest {
            val source = File(sharedDirectory, "report.pdf").apply { writeText("public") }
            val uri = prepare(source)
            val escaped =
                Uri
                    .Builder()
                    .scheme("content")
                    .authority(uri.authority)
                    .appendPath(uri.pathSegments.first())
                    .appendPath("../../private.pdf")
                    .build()

            assertThrows(SecurityException::class.java) { provider.openFile(escaped, "r")?.close() }
        }

    @Test
    fun `missing files and unmounted volumes are reported unavailable`() =
        runTest {
            val source = File(sharedDirectory, "report.pdf").apply { writeText("content") }
            val uri = prepare(source)
            source.delete()
            assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "r")?.close() }
            assertNull(provider.getType(uri))
            for (mimeType in listOf("application/pdf", "application/*", "*/*")) {
                assertThrows(FileNotFoundException::class.java) {
                    provider.openTypedAssetFile(uri, mimeType, null)?.close()
                }
            }
            assertThrows(IllegalArgumentException::class.java) { provider.query(uri, null, null, null, null)?.close() }

            source.writeText("content")
            shadowOf(context.getSystemService(StorageManager::class.java)).resetStorageVolumeList()
            assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "r")?.close() }
            assertNull(provider.getType(uri))
            for (mimeType in listOf("application/pdf", "application/*", "*/*")) {
                assertThrows(FileNotFoundException::class.java) {
                    provider.openTypedAssetFile(uri, mimeType, null)?.close()
                }
            }
            assertThrows(IllegalArgumentException::class.java) { provider.query(uri, null, null, null, null)?.close() }
        }

    private suspend fun prepare(source: File): Uri {
        val prepared = FilePreviewLauncher(context).prepare(source.indexedFile())
        assertTrue("source=$source prepared=$prepared", prepared is PreviewPreparation.Ready)
        return (prepared as PreviewPreparation.Ready).uri
    }

    private fun File.indexedFile() =
        IndexedFileEntity(
            id = name,
            sessionId = "session",
            canonicalPath = canonicalPath,
            displayName = name,
            extension = "pdf",
            mimeType = null,
            category = "documents",
            sizeBytes = length(),
            lastModified = lastModified(),
            volume = "primary",
            parentPath = requireNotNull(parent),
            contentUri = null,
            readable = true,
            writable = true,
        )
}
