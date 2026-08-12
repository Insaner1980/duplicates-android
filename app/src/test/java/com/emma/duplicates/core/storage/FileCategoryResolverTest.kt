package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class FileCategoryResolverTest {
    private val resolver = FileCategoryResolver()

    @Test
    fun `reliable MIME type takes precedence over a misleading extension`() {
        assertEquals(FileCategory.PHOTOS, resolver.resolve("image/jpeg", "txt"))
        assertEquals(FileCategory.VIDEOS, resolver.resolve("video/mp4", "bin"))
        assertEquals(FileCategory.AUDIO, resolver.resolve("audio/flac", "dat"))
        assertEquals(FileCategory.DOCUMENTS, resolver.resolve("application/pdf", "jpg"))
    }

    @Test
    fun `extension is used when MIME type is missing or generic`() {
        assertEquals(FileCategory.PHOTOS, resolver.resolve(null, "HEIC"))
        assertEquals(FileCategory.VIDEOS, resolver.resolve("application/octet-stream", ".mkv"))
        assertEquals(FileCategory.AUDIO, resolver.resolve("binary/octet-stream", "opus"))
        assertEquals(FileCategory.DOCUMENTS, resolver.resolve(null, "docx"))
    }

    @Test
    fun `application media MIME uses the known media extension fallback`() {
        assertEquals(FileCategory.AUDIO, resolver.resolve("application/ogg", "ogg"))
    }

    @Test
    fun `ordinary unknown user file remains scannable as a document`() {
        assertEquals(FileCategory.DOCUMENTS, resolver.resolve(null, "custom"))
    }
}
