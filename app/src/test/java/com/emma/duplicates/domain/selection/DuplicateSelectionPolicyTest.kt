package com.emma.duplicates.domain.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateSelectionPolicyTest {
    private val policy = DuplicateSelectionPolicy()

    @Test
    fun `auto-selection always leaves at least one valid copy`() {
        val selection = policy.createSelection(
            groupId = "group",
            files = listOf(file("camera", "/storage/emulated/0/DCIM/Camera/photo.jpg"), file("download", "/storage/emulated/0/Download/photo.jpg")),
            autoSelect = true,
        )

        assertEquals(setOf("download"), selection.selectedMemberIds)
        assertEquals(setOf("camera"), selection.members.filterNot { it.selectedForDeletion }.mapTo(mutableSetOf()) { it.file.id })
    }

    @Test
    fun `recommended keep is deterministic regardless of input order`() {
        val files = listOf(
            file("later", "/storage/emulated/0/Documents/z.txt", lastModifiedMillis = 20),
            file("earlier", "/storage/emulated/0/Documents/a.txt", lastModifiedMillis = 10),
        )

        val forward = policy.createSelection("group", files, autoSelect = false)
        val reverse = policy.createSelection("group", files.reversed(), autoSelect = false)

        assertEquals("earlier", forward.recommendedKeepId)
        assertEquals(forward.recommendedKeepId, reverse.recommendedKeepId)
    }

    @Test
    fun `favorite is recommended and protected from automatic selection`() {
        val selection = policy.createSelection(
            "group",
            listOf(
                file("favorite", "/storage/emulated/0/Download/photo.jpg", isFavorite = true),
                file("camera", "/storage/emulated/0/DCIM/Camera/photo.jpg"),
            ),
            autoSelect = true,
        )

        assertEquals("favorite", selection.recommendedKeepId)
        assertFalse(selection.member("favorite").selectedForDeletion)
        assertTrue(selection.member("favorite").protectedFromAutoSelection)
    }

    @Test
    fun `camera copy is preferred over a Download copy`() {
        val selection = policy.createSelection(
            "group",
            listOf(
                file("download", "/storage/emulated/0/Download/photo.jpg", lastModifiedMillis = 1),
                file("camera", "/storage/emulated/0/DCIM/Camera/photo.jpg", lastModifiedMillis = 2),
            ),
            autoSelect = false,
        )

        assertEquals("camera", selection.recommendedKeepId)
    }

    @Test
    fun `secondary location accessibility date path length and lexical priorities are deterministic`() {
        assertEquals(
            "ordinary",
            recommended(
                file("download", "/storage/emulated/0/Download/file.txt", lastModifiedMillis = 1),
                file("ordinary", "/storage/emulated/0/Work/file.txt", lastModifiedMillis = 2),
            ),
        )
        assertEquals(
            "writable",
            recommended(
                file("read-only", "/storage/emulated/0/Work/a.txt", lastModifiedMillis = 1, isWritable = false),
                file("writable", "/storage/emulated/0/Work/z.txt", lastModifiedMillis = 2),
            ),
        )
        assertEquals(
            "earlier",
            recommended(
                file("later", "/storage/emulated/0/Work/a.txt", lastModifiedMillis = 2),
                file("earlier", "/storage/emulated/0/Work/z.txt", lastModifiedMillis = 1),
            ),
        )
        assertEquals(
            "shorter",
            recommended(
                file("longer", "/storage/emulated/0/Work/nested/a.txt", lastModifiedMillis = 1),
                file("shorter", "/storage/emulated/0/Work/b.txt", lastModifiedMillis = 1),
            ),
        )
        assertEquals(
            "alpha",
            recommended(
                file("zeta", "/storage/emulated/0/Work/z.txt", lastModifiedMillis = 1),
                file("alpha", "/storage/emulated/0/Work/a.txt", lastModifiedMillis = 1),
            ),
        )
    }

    @Test
    fun `unavailable favorite cannot displace the valid keep copy`() {
        val selection = policy.createSelection(
            "group",
            listOf(
                file("favorite", "/favorite", isFavorite = true, isPresent = false),
                file("valid", "/valid"),
            ),
            autoSelect = true,
        )

        assertEquals("valid", selection.recommendedKeepId)
        assertTrue(selection.selectedMemberIds.isEmpty())
    }

    @Test
    fun `multiple protected copies remain unselected`() {
        val selection = policy.createSelection(
            "group",
            listOf(
                file("favorite", "/storage/emulated/0/Pictures/a.jpg", isFavorite = true),
                file("protected", "/storage/emulated/0/Pictures/b.jpg", protectedFromAutoSelection = true),
                file("removable", "/storage/emulated/0/Download/c.jpg"),
            ),
            autoSelect = true,
        )

        assertFalse(selection.member("favorite").selectedForDeletion)
        assertFalse(selection.member("protected").selectedForDeletion)
        assertTrue(selection.member("removable").selectedForDeletion)
    }

    @Test
    fun `auto-selection disabled leaves every copy unselected`() {
        val selection = policy.createSelection(
            "group",
            listOf(file("one", "/one"), file("two", "/two")),
            autoSelect = false,
        )

        assertTrue(selection.selectedMemberIds.isEmpty())
    }

    @Test
    fun `user can keep multiple copies by clearing a selection`() {
        val initial = policy.createSelection(
            "group",
            listOf(file("one", "/one"), file("two", "/two"), file("three", "/three")),
            autoSelect = true,
        )
        val selectedId = initial.selectedMemberIds.first()

        val changed = policy.changeSelection(initial, selectedId, selected = false)

        assertTrue(changed is SelectionChange.Applied)
        assertFalse((changed as SelectionChange.Applied).selection.member(selectedId).selectedForDeletion)
    }

    @Test
    fun `attempt to select the final valid copy is blocked`() {
        val initial = policy.createSelection(
            "group",
            listOf(file("keep", "/keep"), file("delete", "/delete")),
            autoSelect = true,
        )

        val changed = policy.changeSelection(initial, initial.recommendedKeepId!!, selected = true)

        assertEquals(SelectionBlockReason.KEEP_AT_LEAST_ONE_COPY, (changed as SelectionChange.Blocked).reason)
        assertEquals(initial, changed.selection)
    }

    @Test
    fun `reclaimable total includes only selected eligible copies and updates with selection`() {
        val initial = policy.createSelection(
            "group",
            listOf(
                file("keep", "/keep", sizeBytes = 100),
                file("eligible", "/eligible", sizeBytes = 100),
                file("read-only", "/read-only", sizeBytes = 100, isWritable = false),
                file("missing", "/missing", sizeBytes = 100, isPresent = false),
            ),
            autoSelect = true,
        )

        assertEquals(100, initial.selectedReclaimableBytes)
        val changed = policy.changeSelection(initial, "eligible", selected = false) as SelectionChange.Applied
        assertEquals(0, changed.selection.selectedReclaimableBytes)
    }

    @Test
    fun `explicit selection may override favorite auto-protection when another valid copy remains`() {
        val initial = policy.createSelection(
            "group",
            listOf(
                file("favorite", "/favorite", isFavorite = true),
                file("keep", "/keep"),
            ),
            autoSelect = false,
        )

        val changed = policy.changeSelection(initial, "favorite", selected = true)

        assertTrue(changed is SelectionChange.Applied)
        assertTrue((changed as SelectionChange.Applied).selection.member("favorite").selectedForDeletion)
    }

    private fun DuplicateSelection.member(id: String): SelectionMember =
        members.single { it.file.id == id }

    private fun recommended(first: SelectionFile, second: SelectionFile): String? =
        policy.createSelection("group", listOf(first, second), autoSelect = false).recommendedKeepId

    private fun file(
        id: String,
        path: String,
        sizeBytes: Long = 100,
        lastModifiedMillis: Long = 1,
        isFavorite: Boolean = false,
        protectedFromAutoSelection: Boolean = false,
        isReadable: Boolean = true,
        isWritable: Boolean = true,
        isPresent: Boolean = true,
        matchesGroup: Boolean = true,
    ) = SelectionFile(
        id = id,
        canonicalPath = path,
        sizeBytes = sizeBytes,
        lastModifiedMillis = lastModifiedMillis,
        isFavorite = isFavorite,
        protectedFromAutoSelection = protectedFromAutoSelection,
        isReadable = isReadable,
        isWritable = isWritable,
        isPresent = isPresent,
        matchesGroup = matchesGroup,
    )
}
