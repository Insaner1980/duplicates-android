package com.emma.duplicates.data

import com.emma.duplicates.core.database.ToolItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageUsageTest {
    @Test
    fun categoriesPartitionAllocatedBytesAndFoldersAggregateWithoutDoubleCounting() {
        val items =
            listOf(
                file("a", "/volume/DCIM/a.jpg", "PHOTOS", 10L),
                file("b", "/volume/DCIM/nested/b.jpg", "PHOTOS", 20L),
                file("c", "/volume/Movies/c.mp4", "VIDEOS", 40L),
                file("d", "/volume/Download/d.zip", "DOCUMENTS", 5L),
            )
        val usage = storageUsage(items + items.first(), "/volume", 200L, 100L)
        assertEquals(30L, usage.categories.getValue("PHOTOS"))
        assertEquals(40L, usage.categories.getValue("VIDEOS"))
        assertEquals(25L, usage.otherUsedBytes)
        assertEquals(listOf("Movies", "DCIM", "Download"), usage.folders.map { it.name })
        assertEquals(listOf(40L, 30L, 5L), usage.folders.map { it.bytes })
        assertEquals(listOf("nested"), storageUsage(items, "/volume/DCIM", 200L, 100L).folders.map { it.name })
    }

    @Test
    fun incompleteScansDoNotPresentPartialFolderTotalsAsComplete() {
        val items =
            listOf(
                file("a", "/volume/DCIM/a.jpg", "PHOTOS", 10L),
                file("b", "/volume/Movies/nested/b.mp4", "VIDEOS", 40L),
            )
        val usage = storageUsage(items, "/volume", 200L, 100L, complete = false)
        assertEquals(listOf("DCIM", "Movies"), usage.folders.map { it.name })
        assertEquals(listOf(null, null), usage.folders.map { it.bytes })
        assertNull(storageUsage(items, "/volume/Movies", 200L, 100L, complete = false).folders.single().bytes)
    }

    @Test
    fun unknownAllocationAndInconsistentTotalsDoNotBecomeZeroResidual() {
        assertNull(storageUsage(listOf(file("a", "/volume/a", "DOCUMENTS", 150L)), "/volume", 100L, 0L).otherUsedBytes)
        assertNull(storageUsage(listOf(file("a", "/volume/a", "DOCUMENTS", null)), "/volume", 100L, 0L).otherUsedBytes)
        assertNull(storageUsage(emptyList(), "/volume", null, null).otherUsedBytes)
    }

    private fun file(
        id: String,
        path: String,
        category: String,
        allocated: Long?,
    ) = ToolItemEntity("run", id, "primary", path, id, 99L, 1L, category, allocatedBytes = allocated)
}
