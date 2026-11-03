package com.emma.duplicates.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolResults
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.model.ToolVolume
import com.emma.duplicates.data.FilePreviewLauncher
import com.emma.duplicates.data.PreviewLaunchResult
import com.emma.duplicates.data.PreviewPreparation
import com.emma.duplicates.data.ToolResultStore
import com.emma.duplicates.ui.preview.PreviewAvailability
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ToolPreviewViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val temporaryFolder = TemporaryFolder()
    private val store = mockk<ToolResultStore>(relaxed = true)
    private val launcher = mockk<FilePreviewLauncher>()
    private val owner = ViewModelStore()
    private lateinit var item: ToolItemEntity
    private lateinit var session: ToolScanSession
    private lateinit var results: MutableStateFlow<ToolResults?>
    private val ready = PreviewPreparation.Ready(Uri.parse("content://fixture/photo"), "image/png")

    @Before
    fun configure() {
        val file = temporaryFolder.newFile("photo.png").apply { writeText("fixture") }
        item =
            ToolItemEntity(
                "run",
                "photo",
                "primary",
                file.canonicalPath,
                file.name,
                file.length(),
                file.lastModified(),
                "PHOTOS",
            )
        session =
            ToolScanSession(
                "run",
                CleanupTool.SIMILAR_PHOTOS.name,
                1,
                ToolScope(listOf(ToolVolume("primary", requireNotNull(file.parentFile).canonicalPath))).encode(),
                active = true,
            )
        results = MutableStateFlow(ToolResults(session, listOf(item)))
        every { store.dao.observeResults("run") } returns results
        coEvery { launcher.prepare(any<ToolItemEntity>()) } returns ready
        every { launcher.launch(ready) } returns PreviewLaunchResult.OPENED
    }

    @After
    fun clearModel() = owner.clear()

    private fun model() =
        ToolPreviewViewModel("run", "photo", store, launcher, ioDispatcher = main.dispatcher)
            .also { owner.put("model", it) }

    @Test
    fun validPhotoCanBecomeKeeperAndKeeperCannotBeKeptAgain() =
        runTest(main.dispatcher) {
            val model = model()
            val photo = model.state.first { it is PreviewScreenState.Photo } as PreviewScreenState.Photo
            assertEquals(item.name, photo.state.filename)
            assertTrue(model.canKeep.value)
            model.keep().join()
            coVerify(exactly = 1) { store.keep("run", "photo") }
            results.value = ToolResults(session, listOf(item.copy(keeper = true)))
            model.canKeep.first { !it }
            model.keep().join()
            coVerify(exactly = 1) { store.keep("run", "photo") }
        }

    @Test
    fun staleMissingOrOutOfScopeFilesAreNeverPrepared() =
        runTest(main.dispatcher) {
            for (candidate in listOf(
                item.copy(sizeBytes = item.sizeBytes + 1),
                item.copy(modifiedAt = item.modifiedAt + 1),
                item.copy(canonicalPath = item.canonicalPath + ".missing"),
                item.copy(volume = "removed"),
                item.copy(valid = false),
            )) {
                results.value = ToolResults(session, listOf(candidate))
                val state = model().state.first { it is PreviewScreenState.Generic } as PreviewScreenState.Generic
                assertEquals(PreviewAvailability.MISSING, state.state.availability)
                assertFalse(state.state.canOpenExternally)
            }
            results.value = null
            val missing = model().state.first { it is PreviewScreenState.Generic } as PreviewScreenState.Generic
            assertEquals("", missing.state.filename)
            coVerify(exactly = 0) { launcher.prepare(any<ToolItemEntity>()) }
        }

    @Test
    fun genericPreviewLaunchFailureAndUnsupportedPreparationAreReported() =
        runTest(main.dispatcher) {
            item = item.copy(category = "DOCUMENTS", name = "document.pdf")
            results.value = ToolResults(session.copy(tool = CleanupTool.OLD_DOWNLOADS.name), listOf(item))
            val model = model()
            val initial = model.state.first { it is PreviewScreenState.Generic } as PreviewScreenState.Generic
            assertTrue(initial.state.canOpenExternally)
            assertFalse(model.canKeep.value)
            every { launcher.launch(ready) } returns PreviewLaunchResult.NO_COMPATIBLE_VIEWER
            model.open()
            val failed =
                model.state.first {
                    (it as? PreviewScreenState.Generic)?.state?.availability ==
                        PreviewAvailability.NO_COMPATIBLE_VIEWER
                }
            assertFalse((failed as PreviewScreenState.Generic).state.canOpenExternally)
            verify(exactly = 1) { launcher.launch(ready) }
            coEvery { launcher.prepare(item) } returns PreviewPreparation.Failed
            val unsupported = model().state.first { it is PreviewScreenState.Generic } as PreviewScreenState.Generic
            assertEquals(PreviewAvailability.UNSUPPORTED, unsupported.state.availability)
            assertFalse(unsupported.state.canOpenExternally)
        }

    @Test
    fun apkNameAndMimeTypeCannotLaunchAnInstaller() =
        runTest(main.dispatcher) {
            for (candidate in listOf(
                item.copy(category = "DOCUMENTS", name = "installer.APK"),
                item.copy(category = "DOCUMENTS", mimeType = "application/vnd.android.package-archive"),
            )) {
                results.value = ToolResults(session, listOf(candidate))
                val model = model()
                val state = model.state.first { it is PreviewScreenState.Generic } as PreviewScreenState.Generic
                assertFalse(state.state.canOpenExternally)
                model.open()
            }
            verify(exactly = 0) { launcher.launch(any()) }
        }
}
