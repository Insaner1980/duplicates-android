package com.emma.duplicates.core.permissions

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StorageAccessManagerTest {
    @Test
    fun appSettingsIntentTargetsThisApplication() {
        val manager = StorageAccessManager("com.emma.duplicates") { false }

        val intent = manager.appSettingsIntent()

        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, intent.action)
        assertEquals(Uri.parse("package:com.emma.duplicates"), intent.data)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun accessStateIsReadFreshEachTime() {
        var granted = false
        val manager = StorageAccessManager("com.emma.duplicates") { granted }

        assertFalse(manager.hasAccess())
        granted = true
        assertTrue(manager.hasAccess())
    }

    @Test
    fun unavailableAppSettingsFallsBackToGeneralAllFilesSettings() {
        val launchedActions = mutableListOf<String?>()
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context =
            object : ContextWrapper(base) {
                override fun startActivity(intent: Intent) {
                    launchedActions += intent.action
                    if (launchedActions.size == 1) {
                        throw ActivityNotFoundException("package settings unavailable")
                    }
                }
            }
        val manager = StorageAccessManager("com.emma.duplicates") { false }

        manager.openSettings(context)

        assertEquals(
            listOf(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
            ),
            launchedActions,
        )
    }
}
