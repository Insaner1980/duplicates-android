package com.emma.duplicates.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.os.Environment
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.model.ToolVolume
import com.emma.duplicates.data.RecoveryRepository
import com.emma.duplicates.data.ToolResultStore
import com.emma.duplicates.domain.deletion.CleanupCoordinator
import com.emma.duplicates.domain.deletion.MediaAuthorization
import com.emma.duplicates.domain.deletion.MediaDeletionAuthorizer
import com.emma.duplicates.domain.deletion.removeEmptyDirectory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageCleanupDeviceTest {
    private lateinit var root: File
    private lateinit var database: DuplicatesDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "cleanup-fixture-${UUID.randomUUID()}",
            )
        check(root.mkdir())
        database = Room.inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java).build()
    }

    @After fun tearDown() {
        database.close()
        check(
            root.canonicalFile == root.absoluteFile && root.name.startsWith("cleanup-fixture-") &&
                !Files.isSymbolicLink(root.toPath()),
        )
        Files.walk(root.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test fun directoryOnlyRemovalRetainsNewFilesAndRemovesEmptyTree() =
        runBlocking {
            val parent = root.resolve("empty").apply { mkdir() }
            val child = parent.resolve("child").apply { mkdir() }

            fun remove(file: File): Boolean {
                val key =
                    Files
                        .readAttributes(
                            file.toPath(),
                            BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        ).fileKey()
                        ?.toString()
                return removeEmptyDirectory(root.toPath(), file.toPath(), key)
            }
            val newFile = child.resolve("arrived.txt").apply { writeText("keep") }
            assertFalse(remove(child))
            assertEquals("keep", newFile.readText())
            assertTrue(newFile.delete())
            assertTrue(remove(child))
            assertTrue(remove(parent))
        }

    @Test fun ordinaryUniqueFileRoundTripUsesItsOwnBytes() =
        runBlocking {
            val source = root.resolve("unique.pdf").apply { writeText("unique original bytes") }
            val recoveryRoot = root.resolve("recovery").apply { mkdir() }
            val store = ToolResultStore(database)
            val scope = ToolScope(listOf(ToolVolume("fixture", root.canonicalPath)))
            store.start(ToolScanSession("run", CleanupTool.OLD_DOWNLOADS.name, 1L, scope.encode()))
            val item = item(source)
            store.complete("run", listOf(item), 2L)
            store.setSelected("run", item.id, true)
            val recovery =
                RecoveryRepository(database.recoveryDao(), { listOf(recoveryRoot) }, { recoveryRoot }, { true })
            val volumes =
                StorageVolumeSource {
                    listOf(
                        AvailableStorageVolume(
                            "fixture",
                            "Fixture",
                            root,
                            null,
                            true,
                            false,
                            root.totalSpace,
                            root.freeSpace,
                        ),
                    )
                }
            val coordinator =
                CleanupCoordinator(database, recovery, volumes, {
                    true
                }, MediaDeletionAuthorizer { MediaAuthorization.APPROVED }, Mutex())
            assertNotNull(
                "File snapshot at ${source.path}",
                com.emma.duplicates.domain.deletion
                    .verifyCleanupFile(item),
            )
            val batch = coordinator.prepare("run", setOf(item.id))
            assertEquals("Eligible selection at ${source.path}", 1, batch.files.size)
            val result = coordinator.execute(batch)
            assertEquals("Move outcomes: ${result.outcomes}", 1, result.movedCount)
            assertEquals(0L, result.freedBytes)
            assertFalse(source.exists())
            val record =
                database
                    .recoveryDao()
                    .observeFiles()
                    .first()
                    .single()
            assertEquals(source.path, recovery.restore(record.id))
            assertEquals("unique original bytes", source.readText())
        }

    @Test fun boundedFingerprintHandlesRecompressionResizeOrientationAndCorruption() =
        runBlocking {
            val original = writeImage("original.jpg", 240 to 160, 95, false)
            val resized = writeImage("resized.jpg", 120 to 80, 65, false)
            val edited = writeImage("edited.jpg", 240 to 160, 80, false, 3)
            val unrelated = writeImage("unrelated.jpg", 240 to 160, 90, true)
            val fingerprint = requireNotNull(fingerprintPhoto(item(original)))
            val smallFingerprint = requireNotNull(fingerprintPhoto(item(resized)))
            val editedFingerprint = requireNotNull(fingerprintPhoto(item(edited)))
            assertTrue(
                "Resize hashes: ${fingerprint.fingerprint} / ${smallFingerprint.fingerprint}",
                photoMatch(fingerprint, smallFingerprint),
            )
            assertTrue(
                "Edit hashes: ${fingerprint.fingerprint} / ${editedFingerprint.fingerprint}",
                photoMatch(fingerprint, editedFingerprint),
            )
            assertFalse(photoMatch(fingerprint, requireNotNull(fingerprintPhoto(item(unrelated)))))
            val rotated = root.resolve("rotated.jpg").apply { original.copyTo(this) }
            ExifInterface(rotated.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val oriented = requireNotNull(fingerprintPhoto(item(rotated)))
            assertEquals(160, oriented.width)
            assertEquals(240, oriented.height)
            val broken = root.resolve("broken.jpg").apply { writeText("not an image") }
            assertNull(fingerprintPhoto(item(broken)))
            val flat = root.resolve("flat.png")
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.GRAY)
            flat.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            assertNull(fingerprintPhoto(item(flat)))
        }

    @Test fun runnerUsesCapturedSelectionAndPreservesCanceledResults() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val container =
                com.emma.duplicates.app
                    .AppContainer(context)
            val preferences = container.preferencesRepository.preferences.first()
            val ids = mutableListOf<String>()
            try {
                org.junit.Assume.assumeTrue(container.toolResultStore.latest(CleanupTool.OLD_DOWNLOADS).first() == null)
                root.resolve("ordinary.txt").writeText("unique fixture")
                container.preferencesRepository.setScanSelection(true, setOf(root.canonicalPath), setOf("primary"))
                val completeId = UUID.randomUUID().toString().also(ids::add)
                assertTrue(container.toolScanRunner.run(completeId, CleanupTool.OLD_DOWNLOADS))
                val result = requireNotNull(container.toolResultStore.active(CleanupTool.OLD_DOWNLOADS).first())
                assertEquals(listOf("ordinary.txt"), result.items.map { it.name })
                assertTrue(result.items.none { it.selected })
                assertEquals(listOf(root.canonicalPath), ToolScope.decode(result.session.scope).includedPaths)
                val canceledId = UUID.randomUUID().toString().also(ids::add)
                try {
                    container.toolScanRunner.run(canceledId, CleanupTool.OLD_DOWNLOADS) { _, _ ->
                        throw kotlinx.coroutines.CancellationException("fixture interruption")
                    }
                    org.junit.Assert.fail("Run should cancel")
                } catch (_: kotlinx.coroutines.CancellationException) {
                    assertEquals(
                        com.emma.duplicates.core.database.ScanStatuses.CANCELED,
                        container.toolResultStore.dao
                            .session(canceledId)
                            ?.status,
                    )
                }
                assertEquals(
                    completeId,
                    container.toolResultStore
                        .active(CleanupTool.OLD_DOWNLOADS)
                        .first()
                        ?.session
                        ?.id,
                )
            } finally {
                container.preferencesRepository.setScanSelection(
                    preferences.scanSelectedItemsOnly,
                    preferences.selectedScanPaths,
                    preferences.selectedVolumeIds,
                )
                ids.forEach { id ->
                    container.database.openHelper.writableDatabase.execSQL(
                        "DELETE FROM tool_sessions WHERE id = ?",
                        arrayOf(id),
                    )
                }
                container.database.close()
            }
        }

    private fun writeImage(
        name: String,
        size: Pair<Int, Int>,
        quality: Int,
        inverted: Boolean,
        edit: Int = 0,
    ): File {
        val (width, height) = size
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val wave = if ((y * 8 / height) % 2 == 0) x * 180 / width else 180 - x * 180 / width
                val gray = (if (inverted) 220 - wave else 30 + wave).plus(edit).coerceIn(0, 255)
                bitmap.setPixel(x, y, Color.rgb(gray, gray, gray))
            }
        }
        val file = root.resolve(name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        bitmap.recycle()
        return file
    }

    private fun item(file: File) =
        ToolItemEntity(
            "run",
            file.name,
            "fixture",
            file.canonicalPath,
            file.name,
            file.length(),
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).lastModifiedTime().toMillis(),
            "PHOTOS",
        )
}
