package com.emma.duplicates.core.designsystem

import android.app.Application
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DuplicatesThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyMaterialColorRoleUsesTheDefinedBlackGrayWhiteGoldOrErrorPalette() {
        lateinit var scheme: ColorScheme
        composeRule.setContent {
            DuplicatesTheme {
                scheme = MaterialTheme.colorScheme
            }
        }

        composeRule.runOnIdle {
            val allowed =
                setOf(
                    Background,
                    Surface,
                    SurfaceContainerLow,
                    SurfaceContainer,
                    SurfaceContainerHigh,
                    SurfaceContainerHighest,
                    Primary,
                    PrimaryPressed,
                    PrimaryFocus,
                    OnPrimary,
                    PrimaryContainer,
                    OnPrimaryContainer,
                    PrimaryText,
                    SecondaryText,
                    DisabledText,
                    Outline,
                    OutlineVariant,
                    OtherUsedStorage,
                    FreeStorage,
                    Error,
                    OnError,
                    ErrorContainer,
                    OnErrorContainer,
                    Color.Black,
                ).mapTo(mutableSetOf(), Color::toArgb)
            val actual =
                listOf(
                    scheme.primary,
                    scheme.onPrimary,
                    scheme.primaryContainer,
                    scheme.onPrimaryContainer,
                    scheme.inversePrimary,
                    scheme.secondary,
                    scheme.onSecondary,
                    scheme.secondaryContainer,
                    scheme.onSecondaryContainer,
                    scheme.tertiary,
                    scheme.onTertiary,
                    scheme.tertiaryContainer,
                    scheme.onTertiaryContainer,
                    scheme.background,
                    scheme.onBackground,
                    scheme.surface,
                    scheme.onSurface,
                    scheme.surfaceVariant,
                    scheme.onSurfaceVariant,
                    scheme.surfaceTint,
                    scheme.inverseSurface,
                    scheme.inverseOnSurface,
                    scheme.error,
                    scheme.onError,
                    scheme.errorContainer,
                    scheme.onErrorContainer,
                    scheme.outline,
                    scheme.outlineVariant,
                    scheme.scrim,
                    scheme.surfaceBright,
                    scheme.surfaceContainer,
                    scheme.surfaceContainerHigh,
                    scheme.surfaceContainerHighest,
                    scheme.surfaceContainerLow,
                    scheme.surfaceContainerLowest,
                    scheme.surfaceDim,
                    scheme.primaryFixed,
                    scheme.primaryFixedDim,
                    scheme.onPrimaryFixed,
                    scheme.onPrimaryFixedVariant,
                    scheme.secondaryFixed,
                    scheme.secondaryFixedDim,
                    scheme.onSecondaryFixed,
                    scheme.onSecondaryFixedVariant,
                    scheme.tertiaryFixed,
                    scheme.tertiaryFixedDim,
                    scheme.onTertiaryFixed,
                    scheme.onTertiaryFixedVariant,
                )

            assertTrue(
                "Material color scheme contains a color outside the specified palette",
                actual.all { it.toArgb() in allowed },
            )
        }
    }
}
