package com.emma.duplicates.core.storage

import com.emma.duplicates.core.database.ToolItemEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarPhotoScannerTest {
    @Test
    fun groupingIsDeterministicAndDoesNotJoinTransitiveChains() =
        runTest {
            val images = listOf(photo("a", 0L), photo("b", 63L), photo("c", 4095L))
            val first = groupSimilarPhotos(images)
            assertEquals(listOf(listOf("a", "b")), first.map { group -> group.map { it.id } })
            assertEquals(first, groupSimilarPhotos(images.reversed()))
            assertTrue(first.flatten().none { it.selected })
            assertEquals(1, first.single().count { it.keeper })
        }

    @Test
    fun ratioAndFingerprintThresholdAreBothRequiredAndFavoritesWinKeeper() =
        runTest {
            assertTrue(photoMatch(photo("a", 0), photo("b", 63)))
            assertFalse(photoMatch(photo("a", 0), photo("b", 127)))
            assertFalse(photoMatch(photo("a", 0), photo("b", 0).copy(width = 200)))
            assertFalse(photoMatch(photo("a", 0).copy(height = 0), photo("b", 0)))
            val groups =
                groupSimilarPhotos(
                    listOf(photo("large", 0).copy(width = 200, height = 200), photo("favorite", 0)),
                    setOf("favorite"),
                )
            assertEquals("favorite", groups.single().single { it.keeper }.id)
        }

    @Test
    fun bandLookupDoesNotLoseMatchesAtAnySixChangedBitPositions() =
        runTest {
            val base = 0x5555555555555555L
            val changed = listOf(0, 9, 18, 27, 36, 45).fold(base) { bits, index -> bits xor (1L shl index) }
            assertEquals(2, groupSimilarPhotos(listOf(photo("base", base), photo("changed", changed))).single().size)
        }

    private fun photo(
        id: String,
        fingerprint: Long,
    ) = ToolItemEntity(
        "session",
        id,
        "primary",
        "/pictures/$id.jpg",
        id,
        100L,
        1L,
        "PHOTOS",
        width = 100,
        height = 100,
        fingerprint = fingerprint,
        fingerprintVersion = 1,
    )
}
