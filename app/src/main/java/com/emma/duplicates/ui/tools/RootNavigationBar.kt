package com.emma.duplicates.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.actionTextColor

@Composable
fun RootNavigationBar(
    selected: RootTab,
    onSelect: (RootTab) -> Unit,
    modifier: Modifier = Modifier,
    emphasizeSelection: Boolean = true,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth()) {
                RootTab.entries.forEach { tab ->
                    val label = stringResource(tab.label)
                    val description = stringResource(R.string.root_tab_description, label)
                    val selectedColor = actionTextColor()
                    val color = if (selected == tab) selectedColor else MaterialTheme.colorScheme.onSurface
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 72.dp)
                            .selectable(selected == tab, role = Role.Tab, onClick = { onSelect(tab) })
                            .semantics { contentDescription = description }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Column(
                            Modifier
                                .background(
                                    if (selected == tab && emphasizeSelection) {
                                        selectedColor.copy(alpha = 0.12f)
                                    } else {
                                        Color.Transparent
                                    },
                                    RoundedCornerShape(24.dp),
                                ).padding(
                                    horizontal =
                                        if (LocalDensity.current.fontScale >=
                                            LARGE_FONT_SCALE
                                        ) {
                                            12.dp
                                        } else {
                                            24.dp
                                        },
                                    vertical = 4.dp,
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(painterResource(tab.icon), null, Modifier.size(24.dp), tint = color)
                            Text(label, color = color, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
