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
            val allowed = allowedPalette()
            val actual = scheme.colorRoles()

            assertTrue(
                "Material color scheme contains a color outside the specified palette",
                actual.all { it.toArgb() in allowed },
            )
        }
    }

    private fun allowedPalette() =
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

    private fun ColorScheme.colorRoles() =
        listOf(
            this.primary,
            this.onPrimary,
            this.primaryContainer,
            this.onPrimaryContainer,
            this.inversePrimary,
            this.secondary,
            this.onSecondary,
            this.secondaryContainer,
            this.onSecondaryContainer,
            this.tertiary,
            this.onTertiary,
            this.tertiaryContainer,
            this.onTertiaryContainer,
            this.background,
            this.onBackground,
            this.surface,
            this.onSurface,
            this.surfaceVariant,
            this.onSurfaceVariant,
            this.surfaceTint,
            this.inverseSurface,
            this.inverseOnSurface,
            this.error,
            this.onError,
            this.errorContainer,
            this.onErrorContainer,
            this.outline,
            this.outlineVariant,
            this.scrim,
            this.surfaceBright,
            this.surfaceContainer,
            this.surfaceContainerHigh,
            this.surfaceContainerHighest,
            this.surfaceContainerLow,
            this.surfaceContainerLowest,
            this.surfaceDim,
            this.primaryFixed,
            this.primaryFixedDim,
            this.onPrimaryFixed,
            this.onPrimaryFixedVariant,
            this.secondaryFixed,
            this.secondaryFixedDim,
            this.onSecondaryFixed,
            this.onSecondaryFixedVariant,
            this.tertiaryFixed,
            this.tertiaryFixedDim,
            this.onTertiaryFixed,
            this.onTertiaryFixedVariant,
        )
}
