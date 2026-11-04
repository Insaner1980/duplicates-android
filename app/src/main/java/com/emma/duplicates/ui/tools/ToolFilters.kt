package com.emma.duplicates.ui.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.model.CleanupTool

private val SIZE_CHOICES = listOf(com.emma.duplicates.core.storage.LARGE_FILE_MINIMUM, HALF_GIGABYTE, GIGABYTE)
private val MONTH_CHOICES = listOf(HALF_YEAR_MONTHS, YEAR_MONTHS, 0)
private val SORT_CHOICES = listOf("largest", "smallest", "newest", "oldest")

@Composable
internal fun ToolFilters(
    state: ToolScreenState,
    onControls: (ToolControls) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                androidx.compose.ui.res.pluralStringResource(
                    when (state.tool) {
                        CleanupTool.EMPTY_FOLDERS -> R.plurals.tool_empty_count
                        CleanupTool.SIMILAR_PHOTOS -> R.plurals.tool_photo_count
                        else -> R.plurals.tool_count
                    },
                    state.visibleItems.size,
                    state.visibleItems.size,
                ),
                Modifier.align(Alignment.CenterVertically),
            )
            if (state.tool == CleanupTool.OLD_DOWNLOADS || state.tool == CleanupTool.SCREENSHOTS) {
                AgeFilter(state.controls, onControls)
            }
        }
        if (state.tool == CleanupTool.LARGE_FILES) LargeFileFilters(state.controls, onControls)
    }
}

@Composable
private fun LargeFileFilters(
    controls: ToolControls,
    onControls: (ToolControls) -> Unit,
) {
    val sizes =
        listOf(R.string.tool_over_100mb, R.string.tool_over_500mb, R.string.tool_over_1gb)
            .map { stringResource(it) }
    val sorts =
        listOf(R.string.tool_largest_first, R.string.tool_smallest_first, R.string.sort_newest, R.string.sort_oldest)
            .map { stringResource(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolDropdown(sizes[SIZE_CHOICES.indexOf(controls.minimumBytes).coerceAtLeast(0)], sizes) {
            onControls(controls.copy(minimumBytes = SIZE_CHOICES[it]))
        }
        ToolDropdown(sorts[SORT_CHOICES.indexOf(controls.sort).coerceAtLeast(0)], sorts) {
            onControls(controls.copy(sort = SORT_CHOICES[it]))
        }
    }
}

@Composable
private fun AgeFilter(
    controls: ToolControls,
    onControls: (ToolControls) -> Unit,
) {
    val labels =
        listOf(R.string.tool_over_6months, R.string.tool_over_1year, R.string.tool_all)
            .map { stringResource(it) }
    ToolDropdown(labels[MONTH_CHOICES.indexOf(controls.months).coerceAtLeast(0)], labels) {
        onControls(controls.copy(months = MONTH_CHOICES[it]))
    }
}

@Composable
internal fun SimilarGroupFilter(
    state: ToolScreenState,
    onControls: (ToolControls) -> Unit,
) {
    val count =
        state.results
            ?.items
            .orEmpty()
            .filter { it.valid }
            .mapNotNull { it.groupId }
            .distinct()
            .size
    if (count > 0) {
        val index = state.controls.group.coerceIn(0, count - 1)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onControls(state.controls.copy(group = index - 1)) }, enabled = index > 0) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.tool_previous_group))
            }
            Text(
                stringResource(R.string.tool_group, index + 1, count),
                Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton({ onControls(state.controls.copy(group = index + 1)) }, enabled = index < count - 1) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.tool_next_group))
            }
        }
    }
}

@Composable
internal fun ToolDropdown(
    label: String,
    choices: List<String>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            TextButton({ expanded = true }) {
                Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEachIndexed { index, choice ->
                DropdownMenuItem(text = { Text(choice) }, onClick = {
                    expanded = false
                    onSelect(index)
                })
            }
        }
    }
}

private const val HALF_GIGABYTE = 500_000_000L
private const val GIGABYTE = 1_000_000_000L
private const val HALF_YEAR_MONTHS = 6
private const val YEAR_MONTHS = 12
