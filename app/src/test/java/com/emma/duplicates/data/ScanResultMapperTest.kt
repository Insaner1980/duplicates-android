package com.emma.duplicates.data

import com.emma.duplicates.core.model.ExactDuplicateGroup
import com.emma.duplicates.core.model.ExactDuplicateMember
import com.emma.duplicates.core.model.FileCategory
import com.emma.duplicates.core.model.FileMetadata
import com.emma.duplicates.core.storage.ExactDuplicateScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanResultMapperTest {
    private val mapper = ScanResultMapper()

    @Test
    fun `mapping preserves scanner hashes platform metadata and safe selection`() {
        val favorite = file(
            id = "favorite",
            path = "/storage/emulated/0/Download/photo.jpg",
            favorite = true,
            contentUri = "content://media/1",
        )
        val camera = file("camera", "/storage/emulated/0/DCIM/Camera/photo-copy.jpg")
        val trashed = file(
            id = "trashed",
            path = "/storage/emulated/0/Other/photo-copy.jpg",
            trashed = true,
        )
        val unique = file("unique", "/storage/emulated/0/Documents/unique.txt")
        val result = result(group(favorite, camera, trashed))

        val mapped = mapper.map("session", listOf(favorite, camera, trashed, unique), result, autoSelect = true)

        val favoriteEntity = mapped.files.single { it.canonicalPath == favorite.canonicalPath }
        assertEquals("quick", favoriteEntity.quickHash)
        assertEquals("full", favoriteEntity.fullHash)
        assertEquals("content://media/1", favoriteEntity.contentUri)
        assertEquals(true, favoriteEntity.favorite)
        assertEquals(false, favoriteEntity.trashed)
        assertEquals(true, mapped.files.single { it.canonicalPath == trashed.canonicalPath }.trashed)
        assertNull(mapped.files.single { it.canonicalPath == unique.canonicalPath }.fullHash)

        val memberByFileId = mapped.members.associateBy { it.indexedFileId }
        val favoriteMember = memberByFileId.getValue(favoriteEntity.id)
        assertTrue(favoriteMember.recommendedKeep)
        assertTrue(favoriteMember.protectedFromAutoSelection)
        assertFalse(favoriteMember.selectedForDeletion)
        assertEquals(2, mapped.members.count { it.selectedForDeletion })
        assertEquals(200L, mapped.groups.single().reclaimableBytes)
    }

    @Test
    fun `stable IDs are deterministic within a session and namespaced between sessions`() {
        val first = file("first", "/storage/emulated/0/Documents/a.txt")
        val second = file("second", "/storage/emulated/0/Documents/b.txt")
        val scanResult = result(group(first, second))

        val old = mapper.map("old-session", listOf(first, second), scanResult, autoSelect = false)
        val oldAgain = mapper.map("old-session", listOf(first, second), scanResult, autoSelect = false)
        val staging = mapper.map("staging-session", listOf(first, second), scanResult, autoSelect = false)

        assertEquals(old.files.map { it.id }, oldAgain.files.map { it.id })
        assertEquals(old.groups.map { it.id }, oldAgain.groups.map { it.id })
        assertEquals(old.members.map { it.id }, oldAgain.members.map { it.id })
        assertTrue(old.files.map { it.id }.toSet().intersect(staging.files.map { it.id }.toSet()).isEmpty())
        assertNotEquals(old.groups.single().id, staging.groups.single().id)
        assertTrue(old.members.map { it.id }.toSet().intersect(staging.members.map { it.id }.toSet()).isEmpty())
    }

    private fun result(group: ExactDuplicateGroup) = ExactDuplicateScanResult(
        groups = listOf(group),
        issues = emptyList(),
        skippedFileCount = 0,
        errorCount = 0,
        cacheHitCount = 0,
        quickHashedFileCount = group.copyCount,
        fullHashedFileCount = group.copyCount,
    )

    private fun group(vararg files: FileMetadata) = ExactDuplicateGroup(
        id = "100:full:0",
        category = FileCategory.PHOTOS,
        sizeBytes = 100L,
        contentHash = "full",
        members = files.map { ExactDuplicateMember(it, quickSha256 = "quick", fullSha256 = "full") },
    )

    private fun file(
        id: String,
        path: String,
        favorite: Boolean = false,
        trashed: Boolean = false,
        contentUri: String? = null,
    ) = FileMetadata(
        id = id,
        canonicalPath = path,
        displayName = path.substringAfterLast('/'),
        extension = path.substringAfterLast('.', ""),
        mimeType = "image/jpeg",
        category = FileCategory.PHOTOS,
        sizeBytes = 100L,
        lastModifiedMillis = 10L,
        storageVolume = "primary",
        parentDirectory = path.substringBeforeLast('/'),
        isReadable = true,
        isWritable = true,
        contentUri = contentUri,
        isFavorite = favorite,
        isTrashed = trashed,
    )
}
