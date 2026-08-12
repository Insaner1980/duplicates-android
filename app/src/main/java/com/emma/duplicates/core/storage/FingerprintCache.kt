package com.emma.duplicates.core.storage

import com.emma.duplicates.core.model.FileMetadata

data class FingerprintIdentity(
    val canonicalPath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val storageVolume: String,
) {
    fun matches(file: FileMetadata): Boolean =
        canonicalPath == file.canonicalPath &&
            sizeBytes == file.sizeBytes &&
            lastModifiedMillis == file.lastModifiedMillis &&
            storageVolume == file.storageVolume

    companion object {
        fun from(file: FileMetadata): FingerprintIdentity = FingerprintIdentity(
            canonicalPath = file.canonicalPath,
            sizeBytes = file.sizeBytes,
            lastModifiedMillis = file.lastModifiedMillis,
            storageVolume = file.storageVolume,
        )
    }
}

data class CachedFingerprint(
    val identity: FingerprintIdentity,
    val quickSha256: String,
    val fullSha256: String,
)

interface FingerprintCache {
    suspend fun find(canonicalPath: String, storageVolume: String): CachedFingerprint?

    suspend fun put(fingerprint: CachedFingerprint)

    suspend fun invalidate(canonicalPath: String, storageVolume: String)
}

object EmptyFingerprintCache : FingerprintCache {
    override suspend fun find(canonicalPath: String, storageVolume: String): CachedFingerprint? = null

    override suspend fun put(fingerprint: CachedFingerprint) = Unit

    override suspend fun invalidate(canonicalPath: String, storageVolume: String) = Unit
}
