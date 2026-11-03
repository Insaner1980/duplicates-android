package com.emma.duplicates.data

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.StorageStats
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.UserManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@Suppress("DEPRECATION")
class UnusedAppsRepositoryTest {
    private val context = mockk<Context>()
    private val ops = mockk<AppOpsManager>()
    private val users = mockk<UserManager>()
    private val usage = mockk<UsageStatsManager>()
    private val storage = mockk<StorageStatsManager>()
    private val packages = mockk<PackageManager>()
    private val repository = UnusedAppsRepository(context)
    private val now = System.currentTimeMillis()

    @Before
    fun configure() {
        every { context.packageName } returns "duplicates.test"
        every { context.packageManager } returns packages
        every { context.getSystemService(AppOpsManager::class.java) } returns ops
        every { context.getSystemService(UserManager::class.java) } returns users
        every { context.getSystemService(UsageStatsManager::class.java) } returns usage
        every { context.getSystemService(StorageStatsManager::class.java) } returns storage
        every { ops.checkOpNoThrow(any<String>(), any(), any()) } returns AppOpsManager.MODE_ALLOWED
        every { users.isUserUnlocked } returns true
        every { usage.queryAndAggregateUsageStats(any(), any()) } returns mapOf("old" to evidence())
        every { packages.queryIntentActivities(any(), any<Int>()) } returns listOf(launcher("old"))
        every { packages.getPackageInfo(any<String>(), any<Int>()) } answers {
            PackageInfo().apply {
                firstInstallTime = now - TimeUnit.DAYS.toMillis(300)
                applicationInfo =
                    ApplicationInfo().apply {
                        packageName = firstArg()
                        flags = 0
                        nonLocalizedLabel = "Old application"
                    }
            }
        }
        every { packages.getApplicationLabel(any()) } returns "Old application"
        val stats = mockk<StorageStats>()
        every { stats.appBytes } returns 100L
        every { stats.dataBytes } returns 20L
        every { storage.queryStatsForPackage(any(), any(), any()) } returns stats
    }

    @Test
    fun accessDenialRevocationLockedUserAndMissingHistoryAreDistinct() =
        runTest {
            every { ops.checkOpNoThrow(any<String>(), any(), any()) } returns AppOpsManager.MODE_IGNORED
            assertEquals(UsageAccessState.NOT_GRANTED, repository.load(90, false).state)
            assertEquals(UsageAccessState.REVOKED, repository.load(90, true).state)
            every { ops.checkOpNoThrow(any<String>(), any(), any()) } returns AppOpsManager.MODE_ALLOWED
            every { users.isUserUnlocked } returns false
            assertEquals(UsageAccessState.UNAVAILABLE, repository.load(90, false).state)
            every { users.isUserUnlocked } returns true
            every { usage.queryAndAggregateUsageStats(any(), any()) } returns emptyMap()
            assertEquals(UsageAccessState.UNAVAILABLE, repository.load(90, false).state)
        }

    @Test
    fun eligibleApplicationsAreDeduplicatedAndReportAppPlusDataBytes() =
        runTest {
            every { packages.queryIntentActivities(any(), any<Int>()) } returns listOf(launcher("old"), launcher("old"))
            val result = repository.load(90, false)
            assertEquals(UsageAccessState.RESULTS, result.state)
            assertEquals("old", result.apps.single().packageName)
            assertEquals(120L, result.apps.single().sizeBytes)
            assertEquals("Old application", result.apps.single().label)
        }

    @Test
    fun inactivePackageUsesDeviceHistoryFreshnessInsteadOfItsLastEvent() =
        runTest {
            val inactive = evidence()
            every { inactive.lastTimeStamp } returns now - TimeUnit.DAYS.toMillis(180)
            every { usage.queryAndAggregateUsageStats(any(), any()) } returns
                mapOf("old" to inactive, "active" to evidence(now))
            val result = repository.load(90, false)
            assertEquals("old", result.apps.single().packageName)

            every { usage.queryAndAggregateUsageStats(any(), any()) } returns mapOf("old" to inactive)
            assertEquals(UsageAccessState.UNAVAILABLE, repository.load(90, false).state)
        }

    @Test
    fun recentSystemSelfMissingAndUnknownApplicationsAreNotOffered() =
        runTest {
            every { packages.queryIntentActivities(any(), any<Int>()) } returns
                listOf(
                    launcher("recent"),
                    launcher("system"),
                    launcher("duplicates.test"),
                    launcher("missing"),
                    launcher("unknown"),
                )
            every { usage.queryAndAggregateUsageStats(any(), any()) } returns
                mapOf(
                    "recent" to evidence(now),
                    "system" to evidence(),
                    "duplicates.test" to evidence(),
                )
            every { packages.getPackageInfo("system", 0) } returns
                PackageInfo().apply {
                    applicationInfo = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM }
                }
            every { packages.getPackageInfo("missing", 0) } throws PackageManager.NameNotFoundException()
            assertEquals(UsageAccessState.EMPTY, repository.load(90, false).state)
        }

    @Test
    fun unavailablePackageSizeDoesNotHideAnOtherwiseEligibleApplication() =
        runTest {
            for (failure in listOf(
                IOException(),
                SecurityException(),
                PackageManager.NameNotFoundException(),
                IllegalArgumentException(),
            )) {
                every { storage.queryStatsForPackage(any(), any(), any()) } throws failure
                assertNull(
                    repository
                        .load(90, false)
                        .apps
                        .single()
                        .sizeBytes,
                )
            }
            val stats = mockk<StorageStats>()
            every { stats.appBytes } returns Long.MAX_VALUE
            every { stats.dataBytes } returns 1
            every { storage.queryStatsForPackage(any(), any(), any()) } returns stats
            assertNull(
                repository
                    .load(90, false)
                    .apps
                    .single()
                    .sizeBytes,
            )
        }

    @Test
    fun providerFailureAndAccessRevokedDuringLoadDoNotPublishResults() =
        runTest {
            every { usage.queryAndAggregateUsageStats(any(), any()) } throws IllegalStateException()
            assertEquals(UsageAccessState.UNAVAILABLE, repository.load(90, false).state)
            every { usage.queryAndAggregateUsageStats(any(), any()) } throws SecurityException()
            assertEquals(UsageAccessState.UNAVAILABLE, repository.load(90, false).state)
            every { ops.checkOpNoThrow(any<String>(), any(), any()) } returnsMany
                listOf(AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_IGNORED)
            assertEquals(UsageAccessState.REVOKED, repository.load(90, false).state)
            every { usage.queryAndAggregateUsageStats(any(), any()) } returns mapOf("old" to evidence())
            every { ops.checkOpNoThrow(any<String>(), any(), any()) } returnsMany
                listOf(AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_IGNORED)
            assertEquals(UsageAccessState.REVOKED, repository.load(90, false).state)
        }

    private fun launcher(name: String) =
        ResolveInfo().apply {
            activityInfo =
                ActivityInfo().apply { packageName = name }
        }

    private fun evidence(lastUsed: Long = now - TimeUnit.DAYS.toMillis(180)): UsageStats =
        mockk<UsageStats>().also {
            every { it.firstTimeStamp } returns now - TimeUnit.DAYS.toMillis(365)
            every { it.lastTimeStamp } returns now
            every { it.lastTimeUsed } returns lastUsed
            every { it.lastTimeForegroundServiceUsed } returns 0
        }
}
