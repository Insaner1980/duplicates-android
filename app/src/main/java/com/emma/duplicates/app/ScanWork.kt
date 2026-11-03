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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.emma.duplicates.R
import com.emma.duplicates.core.database.ScanPhases
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.data.ToolResultStore
import com.emma.duplicates.ui.tools.titleResource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

class ScanScheduler(
    context: Context,
    private val workerClass: Class<out ListenableWorker>,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val workInfos = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_SCAN_WORK)

    val workActive = workInfos.map { work -> work.any { !it.state.isFinished } }
    val activeTool = workInfos.map { work -> work.firstOrNull { !it.state.isFinished }?.toolRoute() }

    suspend fun startScan(scanStore: ScanStore): ScanNavigationRequest {
        val latest = scanStore.latestSession.first()
        val activeWork = reconcileSession(scanStore)
        if (activeWork && latest?.status == ScanStatuses.RUNNING) return ScanNavigationRequest()
        workManager
            .enqueueUniqueWork(
                UNIQUE_SCAN_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest
                    .Builder(workerClass)
                    .addTag(SCAN_WORK_TAG)
                    .build(),
            ).await()
        return ScanNavigationRequest(latest?.id, awaitNewSession = true)
    }

    suspend fun reconcileSession(scanStore: ScanStore): Boolean {
        val running = scanStore.runningSession.first()
        val work = workInfos.first()
        val activeWork = work.any { !it.state.isFinished }
        if (!activeWork && running != null) {
            if (work.isNotEmpty() && work.all { it.state == WorkInfo.State.CANCELLED }) {
                scanStore.cancelSession(running.id, System.currentTimeMillis())
            } else {
                scanStore.failSession(running.id, System.currentTimeMillis(), running.errorCount + 1)
            }
        }
        return activeWork
    }

    fun stopScan() {
        workManager.cancelUniqueWork(UNIQUE_SCAN_WORK)
    }

    suspend fun activeToolRequest(): ToolRoute? = activeTool.first()

    private fun WorkInfo.toolRoute(): ToolRoute? =
        tags
            .firstOrNull {
                it.startsWith(ToolScanWorker.TOOL_TAG_PREFIX)
            }?.removePrefix(ToolScanWorker.TOOL_TAG_PREFIX)
            ?.let {
                ToolRoute(it, id.toString())
            }

    suspend fun startTool(
        tool: CleanupTool,
        volumeId: String?,
        store: ToolResultStore,
    ): ToolRoute? {
        reconcileTools(store)
        if (workActive.first()) return activeToolRequest()
        val request =
            OneTimeWorkRequest
                .Builder(ToolScanWorker::class.java)
                .setInputData(workDataOf(ToolScanWorker.TOOL_KEY to tool.name, ToolScanWorker.VOLUME_KEY to volumeId))
                .addTag(ToolScanWorker.TOOL_TAG_PREFIX + tool.name)
                .build()
        workManager.enqueueUniqueWork(UNIQUE_SCAN_WORK, ExistingWorkPolicy.KEEP, request).await()
        return activeToolRequest()
    }

    suspend fun reconcileTools(store: ToolResultStore) {
        val work = workInfos.first()
        val activeIds = work.filter { !it.state.isFinished }.map { it.id.toString() }.toSet()
        store.dao.running().filter { it.id !in activeIds }.forEach { session ->
            val canceled = work.any { it.id.toString() == session.id && it.state == WorkInfo.State.CANCELLED }
            store.finish(
                session.id,
                if (canceled) ScanStatuses.CANCELED else ScanStatuses.FAILED,
                if (canceled) null else "interrupted",
            )
        }
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
        tool: CleanupTool? = null,
    ): ForegroundInfo {
        ensureChannel()
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(phase.stringResource()))
                .setSubText(context.getString(tool?.titleResource() ?: R.string.scanning_for_duplicates))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(scanPendingIntent(tool, workerId))
                .addAction(
                    0,
                    context.getString(R.string.notification_stop),
                    WorkManager.getInstance(context).createCancelPendingIntent(workerId),
                ).apply {
                    if (totalWork != null && totalWork > 0L) {
                        val progress =
                            ((completedWork.coerceIn(0L, totalWork) * PERCENT_TOTAL) / totalWork)
                                .toInt()
                        setProgress(PERCENT_TOTAL, progress, false)
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

    private fun scanPendingIntent(
        tool: CleanupTool?,
        workerId: UUID,
    ): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            intent.putExtra(EXTRA_DESTINATION, DESTINATION_SCANNING)
            if (tool != null) {
                intent.putExtra(EXTRA_TOOL, tool.name)
                intent.putExtra(EXTRA_RUN, workerId.toString())
            }
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
        const val EXTRA_TOOL = "cleanup_tool"
        const val EXTRA_RUN = "cleanup_run"
        private const val CHANNEL_ID = "duplicate_scans"
        private const val NOTIFICATION_ID = 1001
        private const val CONTENT_INTENT_REQUEST_CODE = 1002
        private const val PERCENT_TOTAL = 100
    }
}
