package com.emma.duplicates.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.emma.duplicates.core.database.ScanStatuses
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.database.ToolResults
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.model.ToolVolume
import com.emma.duplicates.core.storage.AvailableStorageVolume
import com.emma.duplicates.data.UnusedApp
import com.emma.duplicates.data.UnusedAppsResult
import com.emma.duplicates.data.UsageAccessState
import com.emma.duplicates.ui.tools.RootNavigationBar
import com.emma.duplicates.ui.tools.RootTab
import com.emma.duplicates.ui.tools.StorageUsageScreen
import com.emma.duplicates.ui.tools.ToolResultsScreen
import com.emma.duplicates.ui.tools.ToolScreenState
import com.emma.duplicates.ui.tools.UnusedAppsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private fun photo(
    file: File,
    index: Int,
    screenshot: Boolean,
) {
    requireNotNull(file.parentFile).mkdirs()
    val bitmap =
        Bitmap.createBitmap(
            if (screenshot) 240 else 480,
            if (screenshot) 480 else 320,
            Bitmap.Config.ARGB_8888,
        )
    val canvas = Canvas(bitmap)
    val paint = Paint()
    paint.color = android.graphics.Color.rgb(185, 210, 223)
    canvas.drawPaint(paint)
    paint.color = android.graphics.Color.rgb(248, 184, 98)
    canvas.drawCircle(320f + index * 4, 104f, 48f, paint)
    paint.color = android.graphics.Color.rgb(71, 117, 127)
    canvas.drawRect(0f, 170f, 480f, 320f, paint)
    paint.color = android.graphics.Color.rgb(49, 77, 63)
    canvas.drawOval(-80f, 170f, 300f, 420f, paint)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}

internal fun toolLayoutFixture(
    tool: CleanupTool,
    fixtureRoot: File,
): ToolScreenState {
    val root = fixtureRoot.path.replace('\\', '/')
    val scope = ToolScope(listOf(ToolVolume("primary", root)))
    val items = fixtureItems(tool, root)
    return ToolScreenState(
        tool,
        ToolResults(
            ToolScanSession(
                "fixture",
                tool.name,
                1_800_000_000_000L,
                scope.encode(),
                ScanStatuses.COMPLETED,
                active = true,
                totalBytes = 128_000_000_000L,
                freeBytes = 41_600_000_000L,
            ),
            items,
        ),
        hasAccess = true,
        volumes =
            listOf(
                AvailableStorageVolume(
                    "primary",
                    "Internal storage",
                    File(root),
                    "external_primary",
                    true,
                    false,
                    128_000_000_000L,
                    41_600_000_000L,
                ),
            ),
    )
}

private fun fixtureItems(
    tool: CleanupTool,
    root: String,
): List<ToolItemEntity> {
    val names = fixtureNames(tool)
    val photos = tool == CleanupTool.SIMILAR_PHOTOS || tool == CleanupTool.SCREENSHOTS
    val items =
        names.mapIndexed { index, name ->
            val folder = fixtureFolder(tool, index, photos)
            if (photos) photo(File(root, "$folder/$name"), index, tool == CleanupTool.SCREENSHOTS)
            ToolItemEntity(
                "fixture",
                "$index",
                "primary",
                "$root/$folder/$name",
                name,
                if (photos) 4_200_000L - index * 200_000L else 2_400_000_000L - index * 400_000_000L,
                1_700_000_000_000L,
                when (name.substringAfterLast('.')) {
                    "jpg", "png" -> "PHOTOS"
                    "mp4" -> "VIDEOS"
                    "mp3" -> "AUDIO"
                    else -> "DOCUMENTS"
                },
                directory = tool == CleanupTool.EMPTY_FOLDERS,
                selected = index == 1,
                groupId = if (tool == CleanupTool.SIMILAR_PHOTOS) "group" else null,
                keeper = tool == CleanupTool.SIMILAR_PHOTOS && index == 0,
                allocatedBytes = 1_000_000_000L,
            )
        }
    return items
}

private fun fixtureNames(tool: CleanupTool): List<String> {
    val names =
        when (tool) {
            CleanupTool.SCREENSHOTS -> {
                listOf("Recipe.png", "Timetable.png", "Receipt.png", "Map.png", "Shop.png", "Notes.png")
            }

            CleanupTool.STORAGE_USAGE -> {
                listOf("movie.mp4", "photo.jpg", "music.mp3", "archive.zip")
            }

            CleanupTool.EMPTY_FOLDERS -> {
                listOf("Old photos", "Export", "Temp", "Drafts", "2024")
            }

            CleanupTool.LARGE_FILES -> {
                listOf("Holiday video.mp4", "Backup.zip", "Concert.mp4", "Maps.zip")
            }

            CleanupTool.OLD_DOWNLOADS -> {
                listOf(
                    "Installer.apk",
                    "Archive.zip",
                    "Travel tickets.pdf",
                    "Instructions.pdf",
                )
            }

            else -> {
                listOf("Sunset.jpg", "Sunset copy.jpg", "Shore.jpg", "Lake.jpg")
            }
        }
    return names
}

private fun fixtureFolder(
    tool: CleanupTool,
    index: Int,
    photos: Boolean,
): String =
    when {
        tool == CleanupTool.STORAGE_USAGE -> listOf("DCIM", "Pictures", "Music", "Download")[index]
        photos -> "Pictures/Screenshots"
        else -> "Download"
    }
