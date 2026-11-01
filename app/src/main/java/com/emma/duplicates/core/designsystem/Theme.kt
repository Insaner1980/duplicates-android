package com.emma.duplicates.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DuplicatesColorScheme =
    darkColorScheme(
        primary = Primary,
        onPrimary = OnPrimary,
        primaryContainer = PrimaryContainer,
        onPrimaryContainer = OnPrimaryContainer,
        inversePrimary = PrimaryFocus,
        secondary = SecondaryText,
        onSecondary = Background,
        secondaryContainer = SurfaceContainerHigh,
        onSecondaryContainer = PrimaryText,
        tertiary = PrimaryFocus,
        onTertiary = OnPrimary,
        tertiaryContainer = PrimaryContainer,
        onTertiaryContainer = OnPrimaryContainer,
        background = Background,
        onBackground = PrimaryText,
        surface = Surface,
        onSurface = PrimaryText,
        surfaceVariant = SurfaceContainerHigh,
        onSurfaceVariant = SecondaryText,
        surfaceTint = Primary,
        inverseSurface = PrimaryText,
        inverseOnSurface = Surface,
        outline = Outline,
        outlineVariant = OutlineVariant,
        error = Error,
        onError = OnError,
        errorContainer = ErrorContainer,
        onErrorContainer = OnErrorContainer,
        scrim = Color.Black,
        surfaceBright = SurfaceContainerHighest,
        surfaceContainer = SurfaceContainer,
        surfaceContainerHigh = SurfaceContainerHigh,
        surfaceContainerHighest = SurfaceContainerHighest,
        surfaceContainerLow = SurfaceContainerLow,
        surfaceContainerLowest = Background,
        surfaceDim = Surface,
        primaryFixed = PrimaryFocus,
        primaryFixedDim = Primary,
        onPrimaryFixed = OnPrimary,
        onPrimaryFixedVariant = PrimaryContainer,
        secondaryFixed = PrimaryText,
        secondaryFixedDim = SecondaryText,
        onSecondaryFixed = Background,
        onSecondaryFixedVariant = SurfaceContainerHighest,
        tertiaryFixed = PrimaryFocus,
        tertiaryFixedDim = Primary,
        onTertiaryFixed = OnPrimary,
        onTertiaryFixedVariant = PrimaryContainer,
    )

private val DuplicatesLightColorScheme =
    lightColorScheme(
        primary = Primary,
        onPrimary = OnPrimary,
        primaryContainer = Primary,
        onPrimaryContainer = OnPrimary,
        secondary = LightSecondaryText,
        onSecondary = PrimaryText,
        secondaryContainer = LightSurfaceVariant,
        onSecondaryContainer = Background,
        tertiary = LightSecondaryText,
        onTertiary = PrimaryText,
        background = PrimaryText,
        onBackground = Background,
        surface = PrimaryText,
        onSurface = Background,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightSecondaryText,
        surfaceTint = Color.Transparent,
        surfaceContainer = LightSurfaceVariant,
        surfaceContainerLow = LightSurfaceVariant,
        surfaceContainerHigh = LightSurfaceVariant,
        surfaceContainerHighest = LightSurfaceHighest,
        surfaceContainerLowest = PrimaryText,
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
        inverseSurface = Background,
        inverseOnSurface = PrimaryText,
        error = LightError,
        onError = PrimaryText,
    )

private val DuplicatesShapes =
    Shapes(
        extraSmall = RoundedCornerShape(16.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(20.dp),
    )

@Composable
fun DuplicatesTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DuplicatesColorScheme else DuplicatesLightColorScheme,
        typography = DuplicatesTypography,
        shapes = DuplicatesShapes,
        content = content,
    )
}
