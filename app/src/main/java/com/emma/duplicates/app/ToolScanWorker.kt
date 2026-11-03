package com.emma.duplicates.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.emma.duplicates.core.model.CleanupTool

class ToolScanWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val tool =
            CleanupTool.entries.firstOrNull { it.name == inputData.getString(TOOL_KEY) } ?: return Result.failure()
        val container = (applicationContext as? DuplicatesApplication)?.container ?: AppContainer(applicationContext)
        val notifications = ScanNotificationFactory(applicationContext)
        val success =
            container.toolScanRunner.run(id.toString(), tool, inputData.getString(VOLUME_KEY)) { phase, count ->
                runForegroundOperation { setForeground(notifications.create(id, phase, count.toLong(), null, tool)) }
            }
        return if (success) Result.success() else Result.failure()
    }

    companion object {
        const val TOOL_KEY = "tool"
        const val VOLUME_KEY = "volume"
        const val TOOL_TAG_PREFIX = "tool:"
    }
}
