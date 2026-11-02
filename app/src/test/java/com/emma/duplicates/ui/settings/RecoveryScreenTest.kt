package com.emma.duplicates.ui.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.emma.duplicates.core.database.RecoveryFile
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun recoveryAt320dpSupportsNormalAndDoubleFont() = checkLayout()

    @Test
    @Config(qualifiers = "w411dp-h900dp-mdpi")
    fun recoveryAtOrdinaryWidthSupportsDoubleFont() = checkLayout()

    private fun checkLayout() {
        val scale = mutableFloatStateOf(1f)
        var restored: String? = null
        val path = "/storage/emulated/0/Documents/long-selected-file-name.pdf"
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.floatValue)) {
                DuplicatesTheme {
                    RecoveryScreen(
                        listOf(RecoveryFile("copy", path, "/private/payload", 0L)),
                        false,
                        null,
                        {},
                        { restored = it },
                        {},
                    )
                }
            }
        }
        listOf(1f, 2f).forEach { fontScale ->
            composeRule.runOnIdle { scale.floatValue = fontScale }
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(path))
            composeRule.onNodeWithText(path).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule
                .onNodeWithText(
                    path,
                ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse(layouts.single().hasVisualOverflow)
            composeRule
                .onNodeWithText("Restore copy")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            assertEquals("copy", restored)
        }
    }
}
