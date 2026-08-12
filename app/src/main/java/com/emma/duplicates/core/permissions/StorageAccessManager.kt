package com.emma.duplicates.core.permissions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri

class StorageAccessManager(
    private val packageName: String,
    private val accessCheck: () -> Boolean = Environment::isExternalStorageManager,
) {
    fun hasAccess(): Boolean = accessCheck()

    fun appSettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:$packageName".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openSettings(context: Context) {
        try {
            context.startActivity(appSettingsIntent())
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
