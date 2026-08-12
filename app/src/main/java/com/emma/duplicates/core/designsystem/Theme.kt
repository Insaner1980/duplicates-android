package com.emma.duplicates.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
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

private val DuplicatesShapes =
    Shapes(
        extraSmall = RoundedCornerShape(16.dp),
        small = RoundedCornerShape(18.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

@Composable
fun DuplicatesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DuplicatesColorScheme,
        typography = DuplicatesTypography,
        shapes = DuplicatesShapes,
        content = content,
    )
}
