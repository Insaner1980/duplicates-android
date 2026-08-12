package com.emma.duplicates.data

import com.emma.duplicates.core.database.FingerprintCacheEntity
import com.emma.duplicates.core.storage.CachedFingerprint
import com.emma.duplicates.core.storage.FingerprintCache
import com.emma.duplicates.core.storage.FingerprintIdentity

class RoomFingerprintCache(
    private val scanStore: ScanStore,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : FingerprintCache {
    override suspend fun find(
        canonicalPath: String,
        storageVolume: String,
    ): CachedFingerprint? =
        scanStore.findFingerprint(canonicalPath, storageVolume)?.let { cached ->
            CachedFingerprint(
                identity =
                    FingerprintIdentity(
                        canonicalPath = cached.canonicalPath,
                        sizeBytes = cached.sizeBytes,
                        lastModifiedMillis = cached.lastModified,
                        storageVolume = cached.volume,
                    ),
                quickSha256 = cached.quickHash,
                fullSha256 = cached.fullHash,
            )
        }

    override suspend fun put(fingerprint: CachedFingerprint) {
        scanStore.putFingerprint(
            FingerprintCacheEntity(
                canonicalPath = fingerprint.identity.canonicalPath,
                volume = fingerprint.identity.storageVolume,
                sizeBytes = fingerprint.identity.sizeBytes,
                lastModified = fingerprint.identity.lastModifiedMillis,
                quickHash = fingerprint.quickSha256,
                fullHash = fingerprint.fullSha256,
                updatedAt = currentTimeMillis(),
            ),
        )
    }

    override suspend fun invalidate(
        canonicalPath: String,
        storageVolume: String,
    ) {
        scanStore.invalidateFingerprint(canonicalPath, storageVolume)
    }
}
