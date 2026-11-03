package com.emma.duplicates.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R

@Composable
fun RootNavigationBar(
    selected: RootTab,
    onSelect: (RootTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth()) {
                RootTab.entries.forEach { tab ->
                    val label = stringResource(tab.label)
                    val description = stringResource(R.string.root_tab_description, label)
                    val selectedColor =
                        if (MaterialTheme.colorScheme.background.luminance() > LIGHT_BACKGROUND_LUMINANCE) {
                            LIGHT_SELECTED_COLOR
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    val color = if (selected == tab) selectedColor else MaterialTheme.colorScheme.onSurface
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 64.dp)
                            .selectable(selected == tab, role = Role.Tab, onClick = { onSelect(tab) })
                            .semantics { contentDescription = description }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(painterResource(tab.icon), null, Modifier.size(24.dp), tint = color)
                        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private const val LIGHT_BACKGROUND_LUMINANCE = 0.5f
private const val LIGHT_SELECTED_ARGB = 0xFFAD3C12
private val LIGHT_SELECTED_COLOR = Color(LIGHT_SELECTED_ARGB)
