package com.emma.duplicates.data

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserManager
import android.os.storage.StorageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AppUsageEvidence(
    val historyFrom: Long,
    val historyUntil: Long,
    val lastActivityAt: Long,
    val lastServiceAt: Long,
    val installedAt: Long,
) {
    fun unusedSince(
        days: Int,
        now: Long,
    ): Long? {
        val cutoff = now - TimeUnit.DAYS.toMillis(days.toLong())
        val lastUse = maxOf(lastActivityAt, lastServiceAt)
        return lastUse.takeIf {
            historyFrom in 1..cutoff && historyUntil >= now - TimeUnit.DAYS.toMillis(1) &&
                installedAt in 1..cutoff && lastActivityAt > 0 && lastServiceAt >= 0 &&
                it in installedAt..cutoff && it <= now
        }
    }
}

data class UnusedApp(
    val packageName: String,
    val label: String,
    val lastUsed: Long,
    val sizeBytes: Long?,
)

enum class UsageAccessState { NOT_GRANTED, LOADING, RESULTS, EMPTY, UNAVAILABLE, REVOKED }

data class UnusedAppsResult(
    val state: UsageAccessState,
    val apps: List<UnusedApp> = emptyList(),
)

class UnusedAppsRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun hasAccess(): Boolean =
        context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED

    suspend fun load(
        days: Int,
        previouslyGranted: Boolean,
    ): UnusedAppsResult =
        withContext(ioDispatcher) {
            if (!hasAccess()) {
                return@withContext UnusedAppsResult(
                    if (previouslyGranted) UsageAccessState.REVOKED else UsageAccessState.NOT_GRANTED,
                )
            }
            if (!context
                    .getSystemService(
                        UserManager::class.java,
                    ).isUserUnlocked
            ) {
                return@withContext UnusedAppsResult(UsageAccessState.UNAVAILABLE)
            }
            try {
                loadUsage(days)
            } catch (_: SecurityException) {
                UnusedAppsResult(if (hasAccess()) UsageAccessState.UNAVAILABLE else UsageAccessState.REVOKED)
            } catch (_: IllegalStateException) {
                UnusedAppsResult(UsageAccessState.UNAVAILABLE)
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun loadUsage(days: Int): UnusedAppsResult {
        val now = System.currentTimeMillis()
        val history: Map<String, android.app.usage.UsageStats>? =
            context
                .getSystemService(UsageStatsManager::class.java)
                .queryAndAggregateUsageStats(now - TimeUnit.DAYS.toMillis(HISTORY_DAYS), now)
        if (history.isNullOrEmpty()) return UnusedAppsResult(UsageAccessState.UNAVAILABLE)
        val packages = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps =
            packages
                .queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .mapNotNull { name ->
                    currentCoroutineContext().ensureActive()
                    unusedApp(name, history[name], days, now)
                }.sortedWith(compareBy<UnusedApp> { it.lastUsed }.thenBy { it.packageName })
        return if (!hasAccess()) {
            UnusedAppsResult(UsageAccessState.REVOKED)
        } else {
            UnusedAppsResult(if (apps.isEmpty()) UsageAccessState.EMPTY else UsageAccessState.RESULTS, apps)
        }
    }

    @Suppress("DEPRECATION")
    private fun unusedApp(
        name: String,
        usage: android.app.usage.UsageStats?,
        days: Int,
        now: Long,
    ): UnusedApp? =
        try {
            val packages = context.packageManager
            val info = packages.getPackageInfo(name, 0)
            val application = info.applicationInfo
            val eligible =
                application != null && name != context.packageName &&
                    application.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (!eligible || usage == null) {
                null
            } else {
                val lastUse =
                    AppUsageEvidence(
                        usage.firstTimeStamp,
                        usage.lastTimeStamp,
                        usage.lastTimeUsed,
                        usage.lastTimeForegroundServiceUsed,
                        info.firstInstallTime,
                    ).unusedSince(days, now)
                lastUse?.let {
                    UnusedApp(
                        name,
                        application.loadLabel(packages).toString(),
                        it,
                        packageBytes(application),
                    )
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun packageBytes(application: ApplicationInfo): Long? =
        try {
            val stats =
                context.getSystemService(StorageStatsManager::class.java).queryStatsForPackage(
                    application.storageUuid ?: StorageManager.UUID_DEFAULT,
                    application.packageName,
                    Process.myUserHandle(),
                )
            Math.addExact(stats.appBytes, stats.dataBytes)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ArithmeticException) {
            null
        }

    private companion object {
        const val HISTORY_DAYS = 365L
    }
}
