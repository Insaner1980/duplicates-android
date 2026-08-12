package com.emma.duplicates.core.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.emma.duplicates.core.model.StorageVolumeRoot
import java.io.File

data class AvailableStorageVolume(
    val id: String,
    val label: String,
    val directory: File,
    val mediaStoreVolumeName: String?,
    val isPrimary: Boolean,
    val isReadOnly: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
) {
    fun toScanRoot(): StorageVolumeRoot = StorageVolumeRoot(directory = directory, id = id)
}

fun interface StorageVolumeSource {
    fun mountedVolumes(): List<AvailableStorageVolume>
}

class AndroidStorageVolumeSource(
    private val context: Context,
) : StorageVolumeSource {
    private val storageManager = context.getSystemService(StorageManager::class.java)

    override fun mountedVolumes(): List<AvailableStorageVolume> =
        storageManager.storageVolumes
            .mapNotNull { volume ->
                val directory = volume.directory ?: return@mapNotNull null
                val isReadOnly = volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
                if (volume.state != Environment.MEDIA_MOUNTED && !isReadOnly) return@mapNotNull null

                val stats = runCatching { StatFs(directory.path) }.getOrNull()
                val totalBytes = stats?.let { it.blockCountLong * it.blockSizeLong } ?: 0L
                val freeBytes = stats?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L
                val mediaStoreName = volume.mediaStoreVolumeName
                AvailableStorageVolume(
                    id =
                        if (volume.isPrimary) {
                            PRIMARY_VOLUME_ID
                        } else {
                            volume.uuid ?: mediaStoreName ?: directory.absolutePath
                        },
                    label = volume.getDescription(context),
                    directory = directory,
                    mediaStoreVolumeName = mediaStoreName,
                    isPrimary = volume.isPrimary,
                    isReadOnly = isReadOnly,
                    totalBytes = totalBytes,
                    freeBytes = freeBytes,
                )
            }.sortedWith(
                compareByDescending<AvailableStorageVolume> { it.isPrimary }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.id },
            )

    companion object {
        const val PRIMARY_VOLUME_ID = "primary"
    }
}
