package com.emma.duplicates.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.emma.duplicates.core.database.IndexedFileEntity
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface PreviewPreparation {
    data class Ready(
        val uri: Uri,
        val mimeType: String,
    ) : PreviewPreparation

    data object Missing : PreviewPreparation

    data object Failed : PreviewPreparation
}

enum class PreviewLaunchResult {
    OPENED,
    NO_COMPATIBLE_VIEWER,
}

class FilePreviewLauncher(
    private val context: Context,
    private val fileUriProvider: (File) -> Uri = { file ->
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file,
        )
    },
) {
    suspend fun prepare(file: IndexedFileEntity): PreviewPreparation =
        withContext(Dispatchers.IO) {
            val mimeType = file.previewMimeType()
            file.contentUri?.let { uri ->
                val parsedUri = uri.toUri()
                return@withContext try {
                    context.contentResolver.openFileDescriptor(parsedUri, "r")?.use { }
                        ?: return@withContext PreviewPreparation.Missing
                    PreviewPreparation.Ready(parsedUri, mimeType)
                } catch (_: FileNotFoundException) {
                    PreviewPreparation.Missing
                } catch (_: IllegalArgumentException) {
                    PreviewPreparation.Missing
                } catch (_: SecurityException) {
                    PreviewPreparation.Failed
                } catch (_: IOException) {
                    PreviewPreparation.Failed
                }
            }

            val source = File(file.canonicalPath)
            if (!source.isFile || !source.canRead()) return@withContext PreviewPreparation.Missing
            try {
                PreviewPreparation.Ready(
                    uri = fileUriProvider(source),
                    mimeType = mimeType,
                )
            } catch (_: SecurityException) {
                PreviewPreparation.Failed
            } catch (_: IllegalArgumentException) {
                PreviewPreparation.Failed
            }
        }

    fun launch(prepared: PreviewPreparation.Ready): PreviewLaunchResult {
        val viewIntent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(prepared.uri, prepared.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply {
                    clipData = ClipData.newUri(context.contentResolver, "preview", prepared.uri)
                }
        return try {
            context.startActivity(Intent.createChooser(viewIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            PreviewLaunchResult.OPENED
        } catch (_: ActivityNotFoundException) {
            PreviewLaunchResult.NO_COMPATIBLE_VIEWER
        }
    }

    private fun IndexedFileEntity.previewMimeType(): String {
        val storedMimeType = mimeType?.trim()?.lowercase(Locale.ENGLISH)
        if (!storedMimeType.isNullOrBlank() && storedMimeType !in GENERIC_MIME_TYPES) {
            return storedMimeType
        }
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension.trim().trimStart('.').lowercase(Locale.ENGLISH))
            ?: "application/octet-stream"
    }

    private companion object {
        val GENERIC_MIME_TYPES = setOf("application/octet-stream", "application/binary")
    }
}
