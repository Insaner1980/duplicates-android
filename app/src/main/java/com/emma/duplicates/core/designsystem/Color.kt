package com.emma.duplicates.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

val Background = Color(color = 0xFF252422)
val Surface = Background
val SurfaceContainerLow = Color(color = 0xFF403D39)
val SurfaceContainer = SurfaceContainerLow
val SurfaceContainerHigh = SurfaceContainer
val SurfaceContainerHighest = Color(color = 0xFF57534D)
val Primary = Color(color = 0xFFEB5E28)
val PrimaryPressed = Primary
val PrimaryFocus = Primary
val OnPrimary = Background
val PrimaryContainer = Primary
val OnPrimaryContainer = OnPrimary
val PrimaryText = Color(color = 0xFFFFFCF2)
val SecondaryText = Color(color = 0xFFCCC5B9)
val DisabledText = SecondaryText
val Outline = Color(color = 0xFF8D877D)
val OutlineVariant = Color(color = 0xFF57534D)
val OtherUsedStorage = OutlineVariant
val FreeStorage = OutlineVariant
val Error = Color(color = 0xFFFFB4AB)
val OnError = Color(color = 0xFF690005)
val ErrorContainer = Color(color = 0xFF93000A)
val OnErrorContainer = Color(color = 0xFFFFDAD6)

val LightSecondaryText = Color(color = 0xFF625E58)

val LightSurfaceVariant = Color(color = 0xFFF0EBE1)

val LightSurfaceHighest = Color(color = 0xFFE9E3D8)

val LightOutline = Color(color = 0xFF817B71)

val LightOutlineVariant = Color(color = 0xFFD8D2C7)

val LightError = Color(color = 0xFF9C2019)

val LightActionText = Color(color = 0xFFAD3C12)

private const val LIGHT_BACKGROUND_LUMINANCE = 0.5f

@Composable
fun actionTextColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > LIGHT_BACKGROUND_LUMINANCE) LightActionText else Primary
