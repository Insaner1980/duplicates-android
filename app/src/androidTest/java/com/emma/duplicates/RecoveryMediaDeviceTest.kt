package com.emma.duplicates

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.emma.duplicates.app.DuplicatesApplication
import com.emma.duplicates.domain.deletion.DeletionFile
import com.emma.duplicates.domain.deletion.DirectDeletionResult
import com.emma.duplicates.ui.components.buildVideoThumbnailImageLoader
import com.emma.duplicates.ui.components.buildVideoThumbnailRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecoveryMediaDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun realImageAndExtensionlessVideoRenderAndDeletionPreservesRestoredBytes() {
        val container = (composeRule.activity.application as DuplicatesApplication).container
        val repository = container.recoveryRepository
        val root =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "recovery-media-${UUID.randomUUID()}",
            )
        check(root.mkdir())
        val image = File(root, "Recovery-fixture.png")
        val video = File(root, "Recovery-fixture.mp4")
        val ids = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 120, 130))
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        InstrumentationRegistry.getInstrumentation().context.assets.open("recovery-video.mp4").use { input ->
            video.outputStream().use(input::copyTo)
        }
        val hashes = listOf(image, video).map { hash(it) }
        try {
            runBlocking {
                stageFiles(repository, listOf(image, video), ids, hashes)
                decodeRecoveryMedia(container, ids)
            }
            verifyRecoveryActions(container, listOf(image, video), ids, hashes)
        } finally {
            runBlocking { ids.forEach { repository.deletePermanently(it) } }
            image.delete()
            video.delete()
            check(root.delete())
        }
    }

    private fun hash(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private suspend fun stageFiles(
        repository: com.emma.duplicates.data.RecoveryRepository,
        sources: List<File>,
        ids: List<String>,
        hashes: List<ByteArray>,
    ) {
        sources.forEachIndexed { index, source ->
            val file =
                DeletionFile(
                    ids[index],
                    canonicalPath = source.path,
                    sizeBytes = source.length(),
                    lastModifiedMillis = source.lastModified(),
                    selectedForDeletion = true,
                )
            assertEquals(
                DirectDeletionResult.DELETED,
                repository.moveTracked(file, ids[index]) {
                    hash(File(it.canonicalPath)).contentEquals(hashes[index])
                },
            )
        }
    }

    private suspend fun decodeRecoveryMedia(
        container: com.emma.duplicates.app.AppContainer,
        ids: List<String>,
    ) {
        val repository = container.recoveryRepository
        val entries =
            repository.describe(
                container.database.recoveryDao().observeFiles().first().filter {
                    it.id in
                        ids
                },
            )
        val photoPath = entries.single { it.file.id == ids[0] }.previewPath
        val videoPath = entries.single { it.file.id == ids[1] }.previewPath
        val photoLoader = ImageLoader(composeRule.activity)
        val videoLoader = buildVideoThumbnailImageLoader(composeRule.activity)
        try {
            assertTrue(
                photoLoader.execute(
                    ImageRequest.Builder(composeRule.activity).data(File(requireNotNull(photoPath))).build(),
                ) is SuccessResult,
            )
            assertTrue(
                videoLoader.execute(
                    buildVideoThumbnailRequest(composeRule.activity, File(requireNotNull(videoPath)), 96),
                ) is SuccessResult,
            )
        } finally {
            photoLoader.shutdown()
            videoLoader.shutdown()
        }
    }

    private fun verifyRecoveryActions(
        container: com.emma.duplicates.app.AppContainer,
        sources: List<File>,
        ids: List<String>,
        hashes: List<ByteArray>,
    ) {
        val repository = container.recoveryRepository
        val image = sources[0]
        val video = sources[1]
        composeRule.onNodeWithContentDescription("Recovery tab").performClick()
        composeRule
            .onNodeWithTag(
                "recovery-list",
            ).performScrollToNode(hasContentDescription("Select ${image.name}"))
        composeRule.onNodeWithContentDescription("Select ${image.name}").performClick()
        composeRule.onNodeWithTag("recovery-delete").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        runBlocking {
            assertTrue(
                repository
                    .describe(
                        container.database.recoveryDao().observeFiles().first().filter {
                            it.id in
                                ids
                        },
                    ).all { it.available },
            )
        }
        composeRule.onNodeWithTag("recovery-restore").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("OK").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(hash(image).contentEquals(hashes[0]))
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithContentDescription("Select ${image.name}").performClick()
        composeRule.onNodeWithTag("recovery-delete").performClick()
        composeRule.onNodeWithTag("recovery-confirm-delete").performClick()
        composeRule.waitUntil(10_000) { runBlocking { container.database.recoveryDao().get(ids[0]) == null } }
        assertTrue(hash(image).contentEquals(hashes[0]))
        runBlocking {
            assertTrue(
                repository
                    .describe(
                        listOf(requireNotNull(container.database.recoveryDao().get(ids[1]))),
                    ).single()
                    .available,
            )
        }
        assertFalse(video.exists())
    }
}
