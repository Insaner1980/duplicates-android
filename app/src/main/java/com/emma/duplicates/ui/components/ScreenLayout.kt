package com.emma.duplicates.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.emma.duplicates.core.designsystem.DuplicatesCard

@Composable
fun HomeIntroContent(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ScreenColumn(modifier = modifier, fillHeight = false) {
        HomeHeader(onOpenSettings)
        Spacer(Modifier.height(20.dp))
        DuplicatesCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    fillHeight: Boolean = true,
    bottomPadding: Dp = 0.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 16.dp
        val sizeModifier =
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
        Column(
            modifier =
                sizeModifier
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = horizontalPadding, vertical = 12.dp)
                    .padding(bottom = bottomPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

fun screenListPadding(horizontalPadding: Dp): PaddingValues =
    PaddingValues(
        start = horizontalPadding,
        end = horizontalPadding,
        top = 24.dp,
        bottom = 32.dp,
    )

internal const val LARGE_FONT_SCALE = 1.5f
