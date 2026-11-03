package com.emma.duplicates.data

import com.emma.duplicates.core.database.ToolItemEntity
import java.nio.file.Paths

data class FolderUsage(
    val path: String,
    val name: String,
    val bytes: Long?,
)

data class StorageUsage(
    val categories: Map<String, Long?>,
    val otherUsedBytes: Long?,
    val folders: List<FolderUsage>,
)

fun storageUsage(
    items: List<ToolItemEntity>,
    folder: String,
    totalBytes: Long?,
    freeBytes: Long?,
): StorageUsage {
    val files = items.filter { it.valid && !it.directory }.distinctBy { it.volume to it.canonicalPath }
    val categories =
        listOf("VIDEOS", "PHOTOS", "AUDIO", "DOCUMENTS").associateWith { category ->
            allocatedTotal(files.filter { it.category == category })
        }
    val allocated = allocatedTotal(files)
    val used =
        if (totalBytes != null && freeBytes != null &&
            freeBytes in 0..totalBytes
        ) {
            totalBytes - freeBytes
        } else {
            null
        }
    val residual = if (allocated != null && used != null && allocated <= used) used - allocated else null
    val root = Paths.get(folder)
    val folders =
        files
            .filter { Paths.get(it.canonicalPath).startsWith(root) }
            .groupBy {
                root
                    .relativize(Paths.get(it.canonicalPath))
                    .toList()
                    .dropLast(1)
                    .firstOrNull()
                    ?.toString()
            }.mapNotNull { (name, children) ->
                name?.let { FolderUsage(root.resolve(it).toString(), it, allocatedTotal(children)) }
            }.sortedWith(compareByDescending<FolderUsage> { it.bytes ?: -1L }.thenBy { it.path })
    return StorageUsage(categories, residual, folders)
}

private fun allocatedTotal(items: List<ToolItemEntity>): Long? {
    if (items.any { it.allocatedBytes == null || it.allocatedBytes < 0 }) return null
    return try {
        items.fold(0L) { total, item -> Math.addExact(total, requireNotNull(item.allocatedBytes)) }
    } catch (_: ArithmeticException) {
        null
    }
}
