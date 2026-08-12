package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.FileCategory

class FileCategoryResolver {
    fun resolve(mimeType: String?, extension: String): FileCategory {
        reliableMimeCategory(mimeType)?.let { return it }

        return when (extension.trim().trimStart('.').lowercase()) {
            in PHOTO_EXTENSIONS -> FileCategory.PHOTOS
            in VIDEO_EXTENSIONS -> FileCategory.VIDEOS
            in AUDIO_EXTENSIONS -> FileCategory.AUDIO
            else -> FileCategory.DOCUMENTS
        }
    }

    private fun reliableMimeCategory(mimeType: String?): FileCategory? {
        val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when {
            normalized.startsWith("image/") -> FileCategory.PHOTOS
            normalized.startsWith("video/") -> FileCategory.VIDEOS
            normalized.startsWith("audio/") -> FileCategory.AUDIO
            normalized.startsWith("text/") -> FileCategory.DOCUMENTS
            normalized in DOCUMENT_APPLICATION_MIME_TYPES ||
                normalized.startsWith("application/vnd.openxmlformats-officedocument.") ||
                normalized.startsWith("application/vnd.oasis.opendocument.") ->
                FileCategory.DOCUMENTS
            else -> null
        }
    }

    private companion object {
        val PHOTO_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif", "bmp", "tiff", "dng",
        )
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp")
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "amr")
        val DOCUMENT_APPLICATION_MIME_TYPES = setOf(
            "application/epub+zip",
            "application/gzip",
            "application/json",
            "application/msword",
            "application/pdf",
            "application/rtf",
            "application/vnd.android.package-archive",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.rar",
            "application/x-7z-compressed",
            "application/x-gzip",
            "application/x-rar-compressed",
            "application/x-tar",
            "application/xml",
            "application/zip",
        )
    }
}
