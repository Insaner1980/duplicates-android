package com.emma.duplicates.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.emma.duplicates.R
import com.emma.duplicates.core.database.ScanPhases
import java.util.UUID

class ScanScheduler(
    context: Context,
    private val workerClass: Class<out ListenableWorker>,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun startScan() =
        workManager.enqueueUniqueWork(
            UNIQUE_SCAN_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(workerClass)
                .addTag(SCAN_WORK_TAG)
                .build(),
        )

    fun stopScan() {
        workManager.cancelUniqueWork(UNIQUE_SCAN_WORK)
    }

    companion object {
        const val UNIQUE_SCAN_WORK = "duplicate-scan"
        const val SCAN_WORK_TAG = "duplicate-scan-work"
    }
}

class ScanNotificationFactory(
    private val context: Context,
) {
    fun create(
        workerId: UUID,
        phase: String,
        completedWork: Long,
        totalWork: Long?,
    ): ForegroundInfo {
        ensureChannel()
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(phase.stringResource()))
                .setSubText(context.getString(R.string.scanning_for_duplicates))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(scanPendingIntent())
                .addAction(
                    0,
                    context.getString(R.string.notification_stop),
                    WorkManager.getInstance(context).createCancelPendingIntent(workerId),
                ).apply {
                    if (totalWork != null && totalWork > 0L) {
                        val progress =
                            ((completedWork.coerceIn(0L, totalWork) * 100L) / totalWork)
                                .toInt()
                        setProgress(100, progress, false)
                    } else {
                        setProgress(0, 0, true)
                    }
                }.build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun scanPendingIntent(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            intent.putExtra(EXTRA_DESTINATION, DESTINATION_SCANNING)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            PendingIntent.getActivity(
                context,
                CONTENT_INTENT_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    private fun String.stringResource(): Int =
        when (this) {
            ScanPhases.COMPARING_CANDIDATES -> R.string.comparing_candidates
            ScanPhases.VERIFYING_DUPLICATES -> R.string.verifying_duplicates
            else -> R.string.finding_files
        }

    companion object {
        const val EXTRA_DESTINATION = "destination"
        const val DESTINATION_SCANNING = "scanning"
        private const val CHANNEL_ID = "duplicate_scans"
        private const val NOTIFICATION_ID = 1001
        private const val CONTENT_INTENT_REQUEST_CODE = 1002
    }
}
