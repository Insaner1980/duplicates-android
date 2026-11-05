package com.emma.duplicates.ui.tools

import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolResults
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.core.storage.isOlderThanMonths
import com.emma.duplicates.domain.deletion.CleanupBatch
import com.emma.duplicates.domain.deletion.EmptyFolderBatch
import java.time.ZoneId

data class ToolControls(
    val minimumBytes: Long = 100_000_000L,
    val months: Int = 0,
    val sort: String = "largest",
    val group: Int = 0,
    val folder: String? = null,
)

data class ToolScreenState(
    val tool: CleanupTool,
    val results: ToolResults? = null,
    val latest: ToolScanSession? = null,
    val controls: ToolControls = ToolControls(),
    val running: Boolean = false,
    val busy: Boolean = false,
    val hasAccess: Boolean = false,
    val volumes: List<AvailableStorageVolume> = emptyList(),
    val notice: Int? = null,
    val fileConfirmation: CleanupBatch? = null,
    val folderConfirmation: EmptyFolderBatch? = null,
    val outcome: ToolOutcome? = null,
) {
    val visibleItems: List<ToolItemEntity> get() = toolVisibleItems(tool, results, controls)
    val selected: List<ToolItemEntity> get() = results?.items.orEmpty().filter { it.valid && it.selected && !it.keeper }
    val hiddenCount: Int get() {
        val visibleIds = visibleItems.mapTo(HashSet()) { it.id }
        return selected.count { it.id !in visibleIds }
    }
}

data class ToolOutcome(
    val completed: Int,
    val failed: Int,
    val directories: Boolean,
)

fun toolVisibleItems(
    tool: CleanupTool,
    results: ToolResults?,
    controls: ToolControls,
): List<ToolItemEntity> {
    results ?: return emptyList()
    val scope = ToolScope.decode(results.session.scope)
    val items = results.items.filter { it.valid }
    val stable = compareBy<ToolItemEntity> { it.canonicalPath }.thenBy { it.volume }
    return when (tool) {
        CleanupTool.LARGE_FILES -> {
            items.filter { it.sizeBytes > controls.minimumBytes }.sortedWith(
                when (controls.sort) {
                    "smallest" -> compareBy<ToolItemEntity> { it.sizeBytes }.then(stable)
                    "newest" -> compareByDescending<ToolItemEntity> { it.modifiedAt }.then(stable)
                    "oldest" -> compareBy<ToolItemEntity> { it.modifiedAt }.then(stable)
                    else -> compareByDescending<ToolItemEntity> { it.sizeBytes }.then(stable)
                },
            )
        }

        CleanupTool.OLD_DOWNLOADS, CleanupTool.SCREENSHOTS -> {
            items
                .filter {
                    controls.months == 0 ||
                        isOlderThanMonths(
                            it.modifiedAt,
                            controls.months,
                            results.session.startedAt,
                            ZoneId.of(scope.zoneId),
                        )
                }.sortedWith(
                    if (tool ==
                        CleanupTool.SCREENSHOTS
                    ) {
                        compareByDescending<ToolItemEntity> { it.modifiedAt }.then(stable)
                    } else {
                        compareBy<ToolItemEntity> { it.modifiedAt }.then(stable)
                    },
                )
        }

        CleanupTool.SIMILAR_PHOTOS -> {
            similarPhotoGroups(results).let { groups ->
                groups
                    .getOrNull(
                        controls.group.coerceIn(0, (groups.size - 1).coerceAtLeast(0)),
                    ).orEmpty()
            }
        }

        else -> {
            items.sortedWith(stable)
        }
    }
}

internal fun similarPhotoGroups(results: ToolResults?): List<List<ToolItemEntity>> =
    results
        ?.items
        .orEmpty()
        .filter { it.valid && it.groupId != null }
        .groupBy { it.groupId }
        .values
        .toList()
