package com.emma.duplicates.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.CleanupMetadata
import com.emma.duplicates.core.designsystem.actionTextColor
import com.emma.duplicates.core.model.CleanupTool

@Composable
internal fun ToolSelectionControls(
    state: ToolScreenState,
    onSelectVisible: (Set<String>, Boolean) -> Unit,
) {
    val eligible = state.visibleItems.filter { !it.keeper }
    val ids = eligible.mapTo(HashSet()) { it.id }
    val similar = state.tool == CleanupTool.SIMILAR_PHOTOS
    val enabled = !state.busy && !state.running
    val colors = ButtonDefaults.textButtonColors(contentColor = actionTextColor())
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            { onSelectVisible(ids, true) },
            Modifier.testTag("tool-select-all"),
            enabled = enabled && eligible.any { !it.selected },
            colors = colors,
        ) {
            Text(
                if (similar) {
                    stringResource(
                        R.string.tool_select_others,
                    )
                } else {
                    stringResource(R.string.tool_select_all, ids.size)
                },
                style = CleanupMetadata,
            )
        }
        TextButton(
            { onSelectVisible(ids, false) },
            Modifier.testTag("tool-clear-selection"),
            enabled = enabled && eligible.any { it.selected },
            colors = colors,
        ) {
            Text(
                stringResource(if (similar) R.string.tool_clear_group else R.string.tool_clear_selection),
                style = CleanupMetadata,
            )
        }
    }
}
