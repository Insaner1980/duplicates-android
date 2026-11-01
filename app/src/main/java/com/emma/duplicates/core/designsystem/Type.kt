package com.emma.duplicates.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val DuplicatesTypography =
    Typography(
        displayLarge =
            TextStyle(
                fontSize = 36.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.Bold,
            ),
        headlineLarge =
            TextStyle(
                fontSize = 28.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
            ),
        headlineMedium =
            TextStyle(
                fontSize = 22.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
            ),
        titleLarge =
            TextStyle(
                fontSize = 22.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        titleMedium =
            TextStyle(
                fontSize = 18.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        labelLarge =
            TextStyle(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    )
