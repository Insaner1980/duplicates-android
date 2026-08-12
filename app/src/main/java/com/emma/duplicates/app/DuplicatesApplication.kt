package com.emma.duplicates.app

import android.app.Application
import android.content.Context
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.permissions.StorageAccessManager
import com.emma.duplicates.core.storage.AndroidStorageVolumeSource
import com.emma.duplicates.core.storage.ExactDuplicateScanner
import com.emma.duplicates.core.storage.FileDiscovery
import com.emma.duplicates.core.storage.MediaStoreMetadataIndex
import com.emma.duplicates.data.RoomFingerprintCache
import com.emma.duplicates.data.AndroidDeletionStorage
import com.emma.duplicates.data.RoomDeletionResultStore
import com.emma.duplicates.data.ScanResultMapper
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.data.ExclusionRepository
import com.emma.duplicates.data.FilePreviewLauncher
import com.emma.duplicates.data.StorageBrowser
import com.emma.duplicates.data.preferences.PreferencesRepository
import com.emma.duplicates.data.preferences.duplicatesPreferencesDataStore
import com.emma.duplicates.domain.deletion.DeletionCoordinator
import com.emma.duplicates.domain.deletion.MediaDeletionAuthorizer
import com.emma.duplicates.domain.deletion.PendingDeletionReconciler

class DuplicatesApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(
    context: Context,
) {
    private val applicationContext = context.applicationContext

    val storageAccessManager = StorageAccessManager(applicationContext.packageName)
    val storageVolumeSource = AndroidStorageVolumeSource(applicationContext)
    val mediaStoreMetadataIndex = MediaStoreMetadataIndex(applicationContext)
    val preferencesRepository =
        PreferencesRepository(applicationContext.duplicatesPreferencesDataStore)
    val database by lazy { DuplicatesDatabase.create(applicationContext) }
    val scanStore by lazy { ScanStore(database) }
    val fileDiscovery by lazy { FileDiscovery(metadataReader = mediaStoreMetadataIndex) }
    val exactDuplicateScanner by lazy {
        ExactDuplicateScanner(fingerprintCache = RoomFingerprintCache(scanStore))
    }
    val scanResultMapper = ScanResultMapper()
    val exclusionRepository by lazy { ExclusionRepository(scanStore, storageVolumeSource) }
    @Suppress("DEPRECATION")
    val storageBrowser by lazy {
        StorageBrowser(
            volumeSource = storageVolumeSource,
            applicationDirectories =
                buildSet {
                    add(applicationContext.filesDir.path)
                    add(applicationContext.cacheDir.path)
                    add(applicationContext.noBackupFilesDir.path)
                    applicationContext.externalCacheDirs.filterNotNull().mapTo(this) { it.path }
                    applicationContext.getExternalFilesDirs(null).filterNotNull().mapTo(this) { it.path }
                    applicationContext.externalMediaDirs.filterNotNull().mapTo(this) { it.path }
                },
        )
    }
    val filePreviewLauncher = FilePreviewLauncher(applicationContext)
    val scanRunner by lazy {
        ScanRunner(
            context = applicationContext,
            storageAccessManager = storageAccessManager,
            storageVolumeSource = storageVolumeSource,
            mediaStoreMetadataIndex = mediaStoreMetadataIndex,
            preferencesRepository = preferencesRepository,
            scanStore = scanStore,
            fileDiscovery = fileDiscovery,
            exactDuplicateScanner = exactDuplicateScanner,
            scanResultMapper = scanResultMapper,
        )
    }
    val scanScheduler = ScanScheduler(applicationContext, DuplicateScanWorker::class.java)
    private val deletionStorage by lazy {
        AndroidDeletionStorage(applicationContext.contentResolver)
    }
    private val deletionResultStore by lazy { RoomDeletionResultStore(scanStore) }
    val pendingDeletionReconciler by lazy {
        PendingDeletionReconciler(deletionStorage, deletionResultStore)
    }

    fun deletionCoordinator(authorizer: MediaDeletionAuthorizer): DeletionCoordinator =
        DeletionCoordinator(
            storage = deletionStorage,
            mediaDeletionAuthorizer = authorizer,
            resultStore = deletionResultStore,
        )
}
