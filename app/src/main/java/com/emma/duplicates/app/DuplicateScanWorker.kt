package com.emma.duplicates.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.emma.duplicates.core.database.ScanPhases
import java.util.UUID
import kotlinx.coroutines.CancellationException

class DuplicateScanWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val container =
            (applicationContext as? DuplicatesApplication)?.container
                ?: AppContainer(applicationContext)
        val notificationFactory = ScanNotificationFactory(applicationContext)
        return when (
            runScanAttempt { sessionId ->
                container.scanRunner.run(
                    sessionId = sessionId,
                    onProgress = { progress ->
                        runForegroundOperation {
                            setForeground(
                                notificationFactory.create(
                                    workerId = id,
                                    phase = progress.phase,
                                    completedWork = progress.completedWork,
                                    totalWork = progress.totalWork,
                                ),
                            )
                        }
                    },
                    onSessionStarted = {
                        runForegroundOperation {
                            setForeground(
                                notificationFactory.create(
                                    workerId = id,
                                    phase = ScanPhases.FINDING_FILES,
                                    completedWork = 0L,
                                    totalWork = null,
                                ),
                            )
                        }
                    },
                )
            }
        ) {
            ScanRunOutcome.COMPLETED -> Result.success()
            ScanRunOutcome.STORAGE_ACCESS_REQUIRED,
            ScanRunOutcome.EMPTY_SCOPE,
            ScanRunOutcome.FAILED,
            -> Result.failure()
        }
    }
}

internal class ForegroundWorkerFailureException(cause: Exception) : Exception(cause)

internal suspend fun runForegroundOperation(operation: suspend () -> Unit) {
    try {
        operation()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        throw ForegroundWorkerFailureException(failure)
    }
}

internal suspend fun runScanAttempt(
    scan: suspend (sessionId: String) -> ScanRunOutcome,
): ScanRunOutcome = scan(UUID.randomUUID().toString())
