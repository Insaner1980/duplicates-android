package com.emma.duplicates.data

import android.content.ContentResolver
import android.content.ContentUris
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class AudioAlbumArtLoader(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(
        contentUri: String,
        sizePx: Int,
    ): Bitmap? =
        withContext(ioDispatcher) {
            try {
                val indexedFileUri = contentUri.toUri()
                val volumeName = MediaStore.getVolumeName(indexedFileUri)
                val audioUri =
                    MediaStore.Audio.Media.getContentUri(
                        volumeName,
                        ContentUris.parseId(indexedFileUri),
                    )
                val albumId = findAlbumId(audioUri) ?: return@withContext null
                if (albumId < 0) return@withContext null
                val albumUri =
                    ContentUris.withAppendedId(
                        MediaStore.Audio.Albums.getContentUri(volumeName),
                        albumId,
                    )
                contentResolver.loadThumbnail(albumUri, Size(sizePx, sizePx), null)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IOException) {
                null
            } catch (_: SQLiteException) {
                null
            }
        }

    private fun findAlbumId(audioUri: Uri): Long? =
        contentResolver
            .query(audioUri, arrayOf(MediaStore.Audio.Media.ALBUM_ID), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val column = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                if (column < 0 || cursor.isNull(column)) null else cursor.getLong(column)
            }
}
