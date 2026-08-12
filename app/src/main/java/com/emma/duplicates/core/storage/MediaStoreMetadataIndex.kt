package com.emma.duplicates.core.storage

import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaStoreRefreshResult(
    val indexedFileCount: Int,
    val errorCount: Int,
)

interface PlatformMetadataIndex : FileMetadataReader {
    suspend fun refresh(volumes: List<AvailableStorageVolume>): MediaStoreRefreshResult
}

class MediaStoreMetadataIndex(
    context: Context,
) : PlatformMetadataIndex {
    private val resolver = context.applicationContext.contentResolver

    @Volatile
    private var entries: Map<String, PlatformFileMetadata> = emptyMap()

    override suspend fun refresh(volumes: List<AvailableStorageVolume>): MediaStoreRefreshResult =
        withContext(Dispatchers.IO) {
            val refreshed = HashMap<String, PlatformFileMetadata>()
            var errors = 0

            volumes.mapNotNull { it.mediaStoreVolumeName }.distinct().forEach { volumeName ->
                try {
                    val collection = MediaStore.Files.getContentUri(volumeName)
                    val queryArguments = Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                    }
                    resolver.query(
                        collection,
                        PROJECTION,
                        queryArguments,
                        null,
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                        val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                        val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                        val favoriteColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
                        val trashedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
                        while (cursor.moveToNext()) {
                            val path = cursor.getString(pathColumn) ?: continue
                            val id = cursor.getLong(idColumn)
                            refreshed[normalizedPath(path)] =
                                PlatformFileMetadata(
                                    mimeType = cursor.getString(mimeColumn),
                                    contentUri = ContentUris.withAppendedId(collection, id).toString(),
                                    isFavorite = cursor.getInt(favoriteColumn) != 0,
                                    isTrashed = cursor.getInt(trashedColumn) != 0,
                                )
                        }
                    }
                } catch (_: SecurityException) {
                    errors += 1
                } catch (_: IllegalArgumentException) {
                    errors += 1
                } catch (_: IOException) {
                    errors += 1
                }
            }

            entries = refreshed
            MediaStoreRefreshResult(indexedFileCount = refreshed.size, errorCount = errors)
        }

    override suspend fun read(file: File): PlatformFileMetadata =
        entries[normalizedPath(file.path)]
            ?: PlatformFileMetadata(
                mimeType = URLConnection.guessContentTypeFromName(file.name),
            )

    private fun normalizedPath(path: String): String =
        Paths.get(path).toAbsolutePath().normalize().toString()

    private companion object {
        val PROJECTION = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.IS_TRASHED,
        )
    }
}
