package com.emma.duplicates.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.core.database.IndexedFileEntity
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class FilePreviewLauncherTest {
    private lateinit var context: Context
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDirectory = File(requireNotNull(context.externalCacheDir), "DuplicatesPreviewTests")
        check(testDirectory.mkdirs() || testDirectory.isDirectory)
    }

    @After
    fun tearDown() {
        testDirectory.deleteRecursively()
    }

    @Test
    fun `large readable shared file is exposed directly without a cache size limit`() = runTest {
        val source = File(testDirectory, "large.mp4")
        RandomAccessFile(source, "rw").use { it.setLength(257L * 1024L * 1024L) }
        val sharedUri = Uri.parse("content://com.emma.duplicates.files/large.mp4")

        val prepared =
            FilePreviewLauncher(context, fileUriProvider = { sharedUri })
                .prepare(source.indexedFile(extension = "mp4"))

        assertTrue("source=$source prepared=$prepared", prepared is PreviewPreparation.Ready)
        assertEquals(sharedUri, (prepared as PreviewPreparation.Ready).uri)
    }

    @Test
    fun `stale content uri is reported missing before preview`() = runTest {
        val source = File(testDirectory, "gone.jpg")

        val prepared =
            FilePreviewLauncher(context).prepare(
                source.indexedFile(
                    extension = "jpg",
                    contentUri = "content://com.emma.duplicates.missing/item/1",
                ),
            )

        assertEquals(PreviewPreparation.Missing, prepared)
    }

    @Test
    fun `extension supplies a useful mime type when metadata is absent`() = runTest {
        val source = File(testDirectory, "report.pdf").apply { writeText("pdf") }

        val prepared =
            FilePreviewLauncher(
                context,
                fileUriProvider = { Uri.parse("content://com.emma.duplicates.files/report.pdf") },
            ).prepare(source.indexedFile(extension = "pdf"))

        assertTrue("source=$source prepared=$prepared", prepared is PreviewPreparation.Ready)
        assertEquals("application/pdf", (prepared as PreviewPreparation.Ready).mimeType)
    }

    private fun File.indexedFile(
        extension: String,
        contentUri: String? = null,
    ) = IndexedFileEntity(
        id = name,
        sessionId = "session",
        canonicalPath = canonicalPath,
        displayName = name,
        extension = extension,
        mimeType = null,
        category = "documents",
        sizeBytes = length(),
        lastModified = lastModified(),
        volume = "primary",
        parentPath = requireNotNull(parent),
        contentUri = contentUri,
        readable = true,
        writable = true,
    )
}
