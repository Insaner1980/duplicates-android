package com.emma.duplicates.domain.deletion

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.database.RecoveryDao
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.model.ToolVolume
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.StorageVolumeSource
import com.emma.duplicates.data.RecoveryRepository
import com.emma.duplicates.data.ToolResultStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CleanupCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: DuplicatesDatabase
    private lateinit var store: ToolResultStore
    private lateinit var root: File
    private lateinit var recoveryRoot: File
    private lateinit var recovery: RecoveryRepository
    private lateinit var coordinator: CleanupCoordinator
    private var mounted = true

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    DuplicatesDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        store = ToolResultStore(database)
        root = temporary.newFolder("volume")
        recoveryRoot = root.resolve("owned-recovery").apply { mkdir() }
        recovery = RecoveryRepository(database.recoveryDao(), { listOf(recoveryRoot) }, { recoveryRoot }, { mounted })
        coordinator =
            CleanupCoordinator(database, recovery, volumes(), {
                true
            }, MediaDeletionAuthorizer { MediaAuthorization.APPROVED }, Mutex())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun deniedMediaAuthorizationLeavesTheOnlyOriginalAndNoRecoveryCopy() =
        runTest {
            val source = root.resolve("photo.jpg").apply { writeText("only image bytes") }
            seed("media", source, CleanupTool.SCREENSHOTS)
            val snapshot =
                database
                    .toolDao()
                    .items(
                        "media",
                    ).single()
                    .copy(contentUri = "content://media/external/images/media/1", category = "PHOTOS")
            database.toolDao().updateItems(listOf(snapshot))
            val denied =
                CleanupCoordinator(
                    database,
                    recovery,
                    volumes(),
                    { true },
                    MediaDeletionAuthorizer { MediaAuthorization.CANCELED },
                    Mutex(),
                )
            val outcome = denied.execute(denied.prepare("media", setOf(source.name)))
            assertEquals(0, outcome.movedCount)
            assertEquals("only image bytes", source.readText())
            assertTrue(
                database
                    .recoveryDao()
                    .observeFiles()
                    .first()
                    .isEmpty(),
            )
            assertTrue(database.toolDao().pending().isEmpty())
        }

    @Test
    fun partialBatchAndRepeatedCallbackCannotMoveChangedOrAlreadyMovedFiles() =
        runTest {
            val unchanged = root.resolve("unchanged.txt").apply { writeText("keep my content") }
            val changed = root.resolve("changed.txt").apply { writeText("old content") }
            val scope = ToolScope(listOf(ToolVolume("primary", root.canonicalPath)))
            store.start(ToolScanSession("partial", CleanupTool.LARGE_FILES.name, 1L, scope.encode()))
            store.complete("partial", listOf(item("partial", unchanged), item("partial", changed)), 2L)
            store.setSelected("partial", unchanged.name, true)
            store.setSelected("partial", changed.name, true)
            val batch = coordinator.prepare("partial", setOf(unchanged.name, changed.name))
            changed.writeText("new content")
            assertEquals(1, coordinator.execute(batch).movedCount)
            assertEquals(0, coordinator.execute(batch).movedCount)
            assertEquals("new content", changed.readText())
            val record =
                database
                    .recoveryDao()
                    .observeFiles()
                    .first()
                    .single()
            assertEquals("keep my content", File(record.storedPath).readText())
        }

    @Test
    fun uniqueFileMovesWithZeroFreedBytesAndRestoresWithoutDuplicateKeeper() =
        runTest {
            val source = root.resolve("unique.txt").apply { writeText("the only original") }
            seed("one", source)
            seed("overlap", source, CleanupTool.OLD_DOWNLOADS)
            val batch = coordinator.prepare("one", setOf(source.name))
            assertEquals(1, batch.files.size)
            val result = coordinator.execute(batch)
            assertEquals(1, result.movedCount)
            assertEquals(0L, result.freedBytes)
            assertFalse(source.exists())
            val record =
                database
                    .recoveryDao()
                    .observeFiles()
                    .first()
                    .single()
            assertTrue(record.expiresAt != null)
            assertEquals("the only original", File(record.storedPath).readText())
            assertEquals(source.path, recovery.restore(record.id))
            assertEquals("the only original", source.readText())
            assertTrue(
                store
                    .active(CleanupTool.OLD_DOWNLOADS)
                    .first()!!
                    .items
                    .none { it.valid || it.selected },
            )
            assertTrue(database.toolDao().pending().isEmpty())
            assertEquals(0, coordinator.execute(batch).movedCount)
        }

    @Test
    fun sameSizeAndTimestampReplacementAfterConfirmationIsNotMoved() =
        runTest {
            val (source, batch) = prepareOriginalFile()
            val modified = source.lastModified()
            source.writeText("replaced")
            source.setLastModified(modified)
            assertEquals(0, coordinator.execute(batch).movedCount)
            assertEquals("replaced", source.readText())
            assertTrue(
                database
                    .recoveryDao()
                    .observeFiles()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun detachedStorageAndChangedSelectionDoNotMutateFiles() =
        runTest {
            val (source, batch) = prepareOriginalFile()
            mounted = false
            assertEquals(0, coordinator.execute(batch).movedCount)
            mounted = true
            store.setSelected("one", source.name, false)
            assertEquals(0, coordinator.execute(batch).movedCount)
            assertEquals("original", source.readText())
        }

    @Test
    fun changedSimilarKeeperStopsGroupWithoutSwitchingToAnotherPhoto() =
        runTest {
            val first = root.resolve("keep.jpg").apply { writeText("original keeper") }
            val second = root.resolve("remove.jpg").apply { writeText("similar but different") }
            val scope = ToolScope(listOf(ToolVolume("primary", root.canonicalPath)))
            store.start(ToolScanSession("photos", CleanupTool.SIMILAR_PHOTOS.name, 1L, scope.encode()))
            store.complete(
                "photos",
                listOf(
                    item("photos", first).copy(groupId = "group", keeper = true),
                    item("photos", second).copy(groupId = "group"),
                ),
                2L,
            )
            store.setSelected("photos", second.name, true)
            val batch = coordinator.prepare("photos", setOf(second.name))
            first.writeText("changed keeper")
            assertEquals(0, coordinator.execute(batch).movedCount)
            assertEquals("similar but different", second.readText())
        }

    @Test
    fun interruptionAfterRenameKeepsJournalAndOfflineRecoveryWaitsBeforeVerifyingPayload() =
        runTest {
            val source = root.resolve("unique.txt").apply { writeText("only copy") }
            seed("one", source)
            val interruptingDao =
                object : RecoveryDao by database.recoveryDao() {
                    override suspend fun setExpiry(
                        id: String,
                        expiresAt: Long,
                    ): Unit = throw CancellationException("process interruption")
                }
            val interruptedRecovery =
                RecoveryRepository(interruptingDao, { listOf(recoveryRoot) }, { recoveryRoot }, { mounted })
            val interrupted =
                CleanupCoordinator(database, interruptedRecovery, volumes(), {
                    true
                }, MediaDeletionAuthorizer { MediaAuthorization.APPROVED }, Mutex())
            val batch = interrupted.prepare("one", setOf(source.name))
            try {
                interrupted.execute(batch)
                error("Expected interruption")
            } catch (_: CancellationException) {
                assertFalse(source.exists())
            }
            val record =
                database
                    .recoveryDao()
                    .observeFiles()
                    .first()
                    .single()
            assertEquals(null, record.expiresAt)
            assertEquals(
                record.id,
                database
                    .toolDao()
                    .pending()
                    .single()
                    .items
                    .single()
                    .recoveryId,
            )
            val reconciler = reconcileAfterStorageReturns()
            reconciler.reconcile()
            assertTrue(database.toolDao().pending().isEmpty())
            assertTrue(database.recoveryDao().get(record.id)!!.expiresAt != null)
            assertEquals("only copy", File(record.storedPath).readText())
        }

    @Test
    fun emptyTreeRemovalIsDeepestFirstAndNewFilesAlwaysSurvive() =
        runTest {
            val parent = root.resolve("parent").apply { mkdir() }
            val child = parent.resolve("child").apply { mkdir() }
            val scope = ToolScope(listOf(ToolVolume("primary", root.canonicalPath)))
            store.start(ToolScanSession("folders", CleanupTool.EMPTY_FOLDERS.name, 1L, scope.encode()))
            val discovered =
                com.emma.duplicates.core.storage.EmptyFolderScanner().scan(
                    "folders",
                    scope.copy(applicationDirectories = listOf(recoveryRoot.path)),
                )
            store.complete("folders", discovered.items, 2L)
            val parentId = discovered.items.single { it.canonicalPath == parent.path }.id
            store.setSelected("folders", parentId, true)
            val remover = EmptyFolderDeletionCoordinator(database, volumes(), { true }, Mutex())
            val batch = remover.prepare("folders")
            child.resolve("new-file").writeText("must survive")
            assertEquals(0, remover.execute(batch).deletedCount)
            assertEquals("must survive", child.resolve("new-file").readText())
            child.resolve("new-file").delete()
            val directoryOnlyRemovalAvailable =
                java.nio.file.Files.newDirectoryStream(root.toPath()).use {
                    it is java.nio.file.SecureDirectoryStream<*>
                }
            assertEquals(
                if (directoryOnlyRemovalAvailable) 2 else 0,
                remover.execute(remover.prepare("folders")).deletedCount,
            )
            assertEquals(!directoryOnlyRemovalAvailable, parent.exists())
            assertTrue(root.exists())
        }

    @Test
    fun missingSourceAfterPreMoveInterruptionReleasesGateAndInvalidatesResult() =
        runTest {
            val source = root.resolve("removed-elsewhere.txt").apply { writeText("original bytes") }
            seed("interrupted", source)
            val interruptingDao =
                object : RecoveryDao by database.recoveryDao() {
                    override suspend fun insert(file: com.emma.duplicates.core.database.RecoveryFile): Unit =
                        throw CancellationException("process stopped before recording the move")
                }
            val interrupted =
                CleanupCoordinator(
                    database,
                    RecoveryRepository(interruptingDao, { listOf(recoveryRoot) }, { recoveryRoot }, { mounted }),
                    volumes(),
                    { true },
                    MediaDeletionAuthorizer { MediaAuthorization.APPROVED },
                    Mutex(),
                )
            val batch = interrupted.prepare("interrupted", setOf(source.name))
            try {
                interrupted.execute(batch)
                error("Expected interruption")
            } catch (_: CancellationException) {
                assertTrue(source.exists())
            }
            assertEquals(1, database.toolDao().pending().size)
            assertTrue(
                database
                    .recoveryDao()
                    .observeFiles()
                    .first()
                    .isEmpty(),
            )
            assertTrue(source.delete())
            reconcileAfterStorageReturns()
            assertTrue(database.toolDao().pending().isEmpty())
            val old = database.toolDao().items("interrupted").single()
            assertFalse(old.valid)
            assertFalse(old.selected)
            store.start(batch.session.copy(id = "next", active = false, status = "running"))
            assertEquals(
                "next",
                database
                    .toolDao()
                    .running()
                    .single()
                    .id,
            )
        }

    private suspend fun prepareOriginalFile(): Pair<File, CleanupBatch> {
        val source = root.resolve("unique.txt").apply { writeText("original") }
        seed("one", source)
        return source to coordinator.prepare("one", setOf(source.name))
    }

    private suspend fun reconcileAfterStorageReturns(): PendingCleanupReconciler {
        val reconciler = PendingCleanupReconciler(database, recovery, volumes(), { true }, Mutex())
        mounted = false
        reconciler.reconcile()
        assertEquals(1, database.toolDao().pending().size)
        mounted = true
        reconciler.reconcile()
        return reconciler
    }

    private suspend fun seed(
        id: String,
        file: File,
        tool: CleanupTool = CleanupTool.LARGE_FILES,
    ) {
        val scope =
            ToolScope(
                listOf(ToolVolume("primary", root.canonicalPath)),
                applicationDirectories = listOf(recoveryRoot.path),
            )
        store.start(ToolScanSession(id, tool.name, 1L, scope.encode()))
        store.complete(id, listOf(item(id, file)), 2L)
        store.setSelected(id, file.name, true)
    }

    private fun item(
        session: String,
        file: File,
    ) = ToolItemEntity(
        session,
        file.name,
        "primary",
        file.canonicalPath,
        file.name,
        file.length(),
        file.lastModified(),
        "DOCUMENTS",
    )

    private fun volumes() =
        StorageVolumeSource {
            if (mounted) {
                listOf(
                    AvailableStorageVolume("primary", "Test", root, null, true, false, 10000L, 5000L),
                )
            } else {
                emptyList()
            }
        }
}
