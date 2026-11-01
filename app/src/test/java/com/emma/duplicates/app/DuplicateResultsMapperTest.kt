package com.emma.duplicates.app

import com.emma.duplicates.core.database.DuplicateGroupEntity
import com.emma.duplicates.core.database.DuplicateGroupWithMembers
import com.emma.duplicates.core.database.DuplicateMemberEntity
import com.emma.duplicates.core.database.DuplicateMemberWithFile
import com.emma.duplicates.core.database.IndexedFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DuplicateResultsMapperTest {
    @Test
    fun potentialSavingsRetainAValidCopyAndIgnoreSelection() {
        val a = member("a")
        val b = member("b")
        val c = member("c")
        assertEquals(0L, availableBytes(group(emptyList())))
        assertEquals(0L, availableBytes(group(listOf(a))))
        assertEquals(200L, availableBytes(group(listOf(a, b, c))))
        assertEquals(
            200L,
            availableBytes(group(listOf(a.copy(member = a.member.copy(selectedForDeletion = true)), b, c))),
        )
        assertEquals(200L, availableBytes(group(listOf(a.copy(file = a.file.copy(writable = false)), b, c))))
        assertEquals(100L, availableBytes(group(listOf(a.copy(file = a.file.copy(readable = false)), b, c))))
        assertEquals(0L, availableBytes(group(listOf(a.copy(file = a.file.copy(changedSinceScan = true)), b))))
    }

    @Test
    fun deletionMappingIncludesKeepersAndUsesMemberAndFileIdentitiesSeparately() {
        val mapped = toDeletionGroup(group(listOf(member("a"), member("b"))), setOf("member-b"))
        assertEquals(listOf("file-a", "file-b"), mapped.files.map { it.id })
        assertEquals(listOf("member-b"), mapped.files.filter { it.selectedForDeletion }.map { it.memberId })
        assertFalse(mapped.files.first().requiresMediaAuthorization)
    }

    private fun group(members: List<DuplicateMemberWithFile>) =
        DuplicateGroupWithMembers(
            DuplicateGroupEntity("group", "session", "DOCUMENTS", "hash", 100L, members.size, 0L, "file.txt"),
            members,
        )

    private fun member(id: String) =
        DuplicateMemberWithFile(
            DuplicateMemberEntity("member-$id", "group", "file-$id", id == "a", false, false),
            IndexedFileEntity(
                "file-$id",
                "session",
                "/storage/emulated/0/Documents/$id.txt",
                "$id.txt",
                "txt",
                "text/plain",
                "DOCUMENTS",
                100L,
                1L,
                "primary",
                "/storage/emulated/0/Documents",
                readable = true,
                writable = true,
            ),
        )
}
