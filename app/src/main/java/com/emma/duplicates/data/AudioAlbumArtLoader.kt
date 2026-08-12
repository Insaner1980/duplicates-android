package com.emma.duplicates.data

import android.content.ContentResolver
import android.content.ContentUris
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import androidx.core.net.toUri
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                val albumId =
                    contentResolver
                        .query(
                            audioUri,
                            arrayOf(MediaStore.Audio.Media.ALBUM_ID),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (!cursor.moveToFirst()) return@use null
                            val albumIdIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                            if (albumIdIndex < 0 || cursor.isNull(albumIdIndex)) null else cursor.getLong(albumIdIndex)
                        } ?: return@withContext null
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
}
