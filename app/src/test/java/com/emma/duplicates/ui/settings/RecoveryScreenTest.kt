package com.emma.duplicates.ui.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.emma.duplicates.core.database.RecoveryFile
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.data.RecoveryEntry
import com.emma.duplicates.ui.tools.RootNavigationBar
import com.emma.duplicates.ui.tools.RootTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun recoveryAt320dpSupportsBothThemesAndFontScales() = checkLayout("320")

    @Test
    @Config(qualifiers = "w411dp-h900dp-mdpi")
    fun recoveryAtOrdinaryWidthSupportsBothThemesAndFontScales() = checkLayout("411")

    @Test
    @Config(qualifiers = "w640dp-h320dp-mdpi")
    fun recoveryInShortLandscape() = checkLayout("landscape")

    private val state = mutableStateOf(RecoveryUiState())
    private var restored = 0
    private var deleted = 0

    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    private fun show(
        font: androidx.compose.runtime.MutableFloatState,
        dark: androidx.compose.runtime.MutableState<Boolean>,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, font.floatValue),
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides
                    AsyncImagePreviewHandler { coil3.ColorImage(android.graphics.Color.rgb(90, 140, 145)) },
            ) {
                DuplicatesTheme(dark.value) {
                    androidx.compose.material3.Surface(
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            RecoveryScreen(
                                state.value,
                                { id, checked ->
                                    state.value =
                                        state.value.copy(
                                            selectedIds =
                                                if (checked) {
                                                    state.value.selectedIds + id
                                                } else {
                                                    state.value.selectedIds -
                                                        id
                                                },
                                        )
                                },
                                { restored++ },
                                { state.value = state.value.copy(pendingDeleteIds = state.value.selectedIds.toSet()) },
                                {
                                    deleted++
                                    state.value = state.value.copy(pendingDeleteIds = null)
                                },
                                { state.value = state.value.copy(pendingDeleteIds = null) },
                                { state.value = state.value.copy(outcome = null) },
                                Modifier.weight(1f),
                            )
                            RootNavigationBar(RootTab.RECOVERY, {})
                        }
                    }
                }
            }
        }
    }

    private fun checkLayout(width: String) {
        val font = mutableFloatStateOf(1f)
        val dark = mutableStateOf(false)
        show(font, dark)
        for (theme in listOf(false, true)) {
            for (scale in listOf(1f, 2f)) {
                composeRule.runOnIdle {
                    dark.value = theme
                    font.floatValue = scale
                    state.value = fixture()
                }
                if (width ==
                    "landscape"
                ) {
                    composeRule.onNodeWithTag("recovery-list").performScrollToNode(hasTestTag("recovery-restore"))
                }
                composeRule.onNodeWithTag("recovery-restore").assertIsDisplayed().performClick()
                if (width ==
                    "landscape"
                ) {
                    composeRule.onNodeWithTag("recovery-list").performScrollToNode(hasTestTag("recovery-delete"))
                }
                composeRule.onNodeWithTag("recovery-delete").assertIsDisplayed()
                if (width ==
                    "landscape"
                ) {
                    composeRule.onNodeWithTag("recovery-list").performScrollToNode(hasTestTag("recovery-row-one"))
                }
                val themeName = if (theme) "dark" else "light"
                val file =
                    File(
                        "build/outputs/two-tab-ui/visual/recovery-$width-${scale.toInt()}-$themeName.png",
                    )
                file.parentFile!!.mkdirs()
                file.outputStream().use {
                    composeRule
                        .onRoot()
                        .captureToImage()
                        .asAndroidBitmap()
                        .compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                assertReadableText()
            }
        }
        assertEquals(4, restored)
    }

    @Test fun rowSelectionAndDeleteConfirmationAreIndependentAndCancellable() {
        state.value = fixture().copy(selectedIds = emptySet())
        show(mutableFloatStateOf(1f), mutableStateOf(false))
        composeRule.onNodeWithTag("recovery-restore").assertIsNotEnabled()
        composeRule.onNodeWithTag("recovery-delete").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Select Lake.jpg").performClick()
        assertEquals(setOf("one"), state.value.selectedIds)
        composeRule.onNodeWithTag("recovery-delete").performClick()
        composeRule.onNodeWithText("Delete permanently?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, deleted)
        composeRule.onNodeWithTag("recovery-delete").performClick()
        composeRule.onNodeWithTag("recovery-confirm-delete").performClick()
        assertEquals(1, deleted)
        assertEquals(0, restored)
    }

    @Test fun unknownSizesAndExpiryDoNotBecomeFalseZeroes() {
        val entry =
            fixture().entries.first().let {
                it.copy(sizeBytes = null, previewPath = null, available = false, file = it.file.copy(expiresAt = null))
            }
        state.value = RecoveryUiState(listOf(entry), setOf("one"))
        show(mutableFloatStateOf(2f), mutableStateOf(true))
        composeRule.onNodeWithTag("recovery-total").assertTextEquals("—")
        composeRule.onNodeWithTag("recovery-selected-size").assertTextEquals("—")
        composeRule.onNodeWithContentDescription("No expiry date").assertIsDisplayed()
        assertReadableText()
    }

    @Test fun emptyRecoveryKeepsBothActionsDisabled() {
        show(mutableFloatStateOf(1f), mutableStateOf(false))
        composeRule.onNodeWithText("No files").assertIsDisplayed()
        composeRule.onNodeWithText("0 selected").assertIsDisplayed()
        composeRule.onNodeWithTag("recovery-restore").assertIsNotEnabled()
        composeRule.onNodeWithTag("recovery-delete").assertIsNotEnabled()
    }

    private fun assertReadableText() {
        val nodes =
            composeRule
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        composeRule.runOnIdle {
            nodes.forEach { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
                val metadata = tag.startsWith("file-name-") || tag.startsWith("file-path-")
                layouts.forEach { layout ->
                    val clipped =
                        (0 until layout.lineCount).any { line ->
                            layout.getLineLeft(line) < -1f || layout.getLineRight(line) > layout.size.width + 1f ||
                                (!metadata && layout.isLineEllipsized(line))
                        }
                    assertFalse(
                        "Clipped text: ${layout.layoutInput.text}",
                        (
                            if (metadata) {
                                layout.getLineBottom(
                                    layout.lineCount - 1,
                                ) > layout.size.height + 1f
                            } else {
                                layout.didOverflowHeight
                            }
                        ) ||
                            clipped,
                    )
                }
            }
        }
    }

    private fun fixture(): RecoveryUiState {
        val names =
            listOf("Lake.jpg", "Lake-copy.jpg", "Birthday.mp4", "Autumn.mp4", "Receipt-with-a-long-file-name.pdf")
        val sizes = listOf(3_200_000L, 283_600L, 473_500_000L, 1_600_000_000L, 1_100_000L)
        val ids = listOf("one", "two", "three", "four", "five")
        val entries =
            names.mapIndexed { index, name ->
                val file =
                    RecoveryFile(
                        ids[index],
                        "/storage/emulated/0/Pictures/Camera/$name",
                        "/fixture/${ids[index]}/payload",
                        1,
                        1_796_256_000_000L,
                    )
                RecoveryEntry(file, sizes[index], file.storedPath, true)
            }
        return RecoveryUiState(entries, setOf("one", "two", "three"))
    }
}
