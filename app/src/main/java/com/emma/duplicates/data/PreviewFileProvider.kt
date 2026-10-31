package com.emma.duplicates.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.emma.duplicates.core.storage.AndroidStorageVolumeSource
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

// ContentProvider requires separate entry points for metadata, reading, and rejected mutations.
@Suppress("TooManyFunctions")
class PreviewFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Previews are read-only")
        return ParcelFileDescriptor.open(resolveFile(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String =
        MimeTypeMap
            .getSingleton()
            .getMimeTypeFromExtension(resolveMetadataFile(uri).extension.lowercase(Locale.ROOT))
            ?: "application/octet-stream"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolveMetadataFile(uri)
        val columns = (projection?.toList() ?: COLUMNS).filter { it in COLUMNS }
        return MatrixCursor(columns.toTypedArray(), 1).apply {
            addRow(columns.map { column -> if (column == OpenableColumns.DISPLAY_NAME) file.name else file.length() })
        }
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri = throw SecurityException("Previews are read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw SecurityException("Previews are read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw SecurityException("Previews are read-only")

    private fun resolveMetadataFile(uri: Uri): File =
        try {
            resolveFile(uri)
        } catch (exception: FileNotFoundException) {
            throw IllegalArgumentException(exception.message, exception)
        }

    private fun resolveFile(uri: Uri): File {
        val appContext = requireNotNull(context)
        val segments: List<String> = uri.pathSegments
        if (uri.scheme != "content" || uri.authority != "${appContext.packageName}.files" || segments.size != 2) {
            throw FileNotFoundException("Invalid preview URI")
        }
        val volume =
            AndroidStorageVolumeSource(appContext).mountedVolumes().firstOrNull { it.id == segments[0] }
                ?: throw FileNotFoundException("Preview storage is unavailable")
        return sharedFile(appContext, volume.directory, File(volume.directory, segments[1]))
    }

    companion object {
        private val COLUMNS = listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)

        fun getUriForFile(
            context: Context,
            file: File,
        ): Uri {
            val canonical = file.canonicalFile
            val volume =
                AndroidStorageVolumeSource(context)
                    .mountedVolumes()
                    .filter { canonical.toPath().startsWith(it.directory.canonicalFile.toPath()) }
                    .maxByOrNull { it.directory.canonicalPath.length }
                    ?: throw SecurityException("File is outside shared preview storage")
            val shared = sharedFile(context, volume.directory, canonical)
            val relative =
                volume.directory.canonicalFile
                    .toPath()
                    .relativize(shared.toPath())
                    .toString()
            return Uri
                .Builder()
                .scheme("content")
                .authority("${context.packageName}.files")
                .appendPath(volume.id)
                .appendPath(relative)
                .build()
        }

        private fun sharedFile(
            context: Context,
            directory: File,
            file: File,
        ): File {
            val root = directory.canonicalFile.toPath()
            val canonical = file.canonicalFile
            val path = canonical.toPath()
            val relative = if (path.startsWith(root)) root.relativize(path) else null
            val restricted =
                relative == null || relative.startsWith("Android/data") || relative.startsWith("Android/obb") ||
                    relative.startsWith("Android/media/${context.packageName}")
            if (restricted) throw SecurityException("File is outside shared preview storage")
            if (!canonical.isFile || !canonical.canRead()) throw FileNotFoundException("Preview file is unavailable")
            return canonical
        }
    }
}
