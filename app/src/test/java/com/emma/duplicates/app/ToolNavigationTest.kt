package com.emma.duplicates.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.emma.duplicates.R
import com.emma.duplicates.core.database.ToolResults
import com.emma.duplicates.core.database.ToolScanSession
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.data.UnusedAppsResult
import com.emma.duplicates.data.UsageAccessState
import com.emma.duplicates.ui.tools.titleResource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h900dp-mdpi")
class ToolNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = mockk<AppContainer>(relaxed = true)
    private lateinit var nav: NavHostController
    private val dark = mutableStateOf(true)
    private val font = mutableFloatStateOf(1f)
    private var grants = 0

    @Before
    fun configure() {
        every { container.toolResultStore.active(any()) } returns flowOf<ToolResults?>(null)
        every { container.toolResultStore.latest(any()) } returns flowOf<ToolScanSession?>(null)
        every { container.scanScheduler.workActive } returns flowOf(false)
        every { container.scanScheduler.activeTool } returns flowOf(null)
        every { container.storageAccessManager.hasAccess() } returns false
        every { container.storageVolumeSource.mountedVolumes() } returns emptyList()
        coEvery { container.unusedAppsRepository.load(any(), any()) } returns
            UnusedAppsResult(UsageAccessState.NOT_GRANTED)
        every { container.unusedAppsRepository.hasAccess() } returns false
        every { container.toolResultStore.dao.observeResults(any()) } returns flowOf(null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font.floatValue)) {
                DuplicatesTheme(dark.value) {
                    nav = rememberNavController()
                    NavHost(nav, startDestination = HomeRoute) {
                        composable<HomeRoute> { Text("Start") }
                        composable<ToolRoute> {
                            ToolDestination(container, nav, it, mockk(relaxed = true)) { grants++ }
                        }
                        composable<ToolPreviewRoute> { ToolPreviewDestination(container, nav, it) }
                    }
                }
            }
        }
    }

    @Test
    fun destinationsReturnHomeInBothThemesAndFontSizes() = checkDestinations()

    @Test
    @Config(qualifiers = "w320dp-h800dp-mdpi")
    fun destinationsRemainUsableAt320dpIncludingDoubleFont() = checkDestinations()

    private fun checkDestinations() {
        val tools = CleanupTool.entries.filter { it != CleanupTool.DUPLICATES }
        val variants = listOf(false, true).flatMap { theme -> listOf(1f, 2f).map { theme to it } }
        for ((theme, scale) in variants) {
            compose.runOnIdle {
                dark.value = theme
                font.floatValue = scale
            }
            for (tool in tools) {
                compose.runOnIdle { nav.navigate(ToolRoute(tool.name)) }
                compose.onNodeWithText(compose.activity.getString(tool.titleResource())).assertIsDisplayed()
                if (tool != CleanupTool.UNUSED_APPS) {
                    val before = grants
                    compose
                        .onNodeWithText(
                            compose.activity.getString(R.string.tool_grant_access),
                        ).performScrollTo()
                        .performClick()
                    compose.runOnIdle { assertEquals(before + 1, grants) }
                }
                compose
                    .onNodeWithContentDescription(
                        compose.activity.getString(R.string.back),
                    ).performScrollTo()
                    .performClick()
                compose.onNodeWithText("Start").assertIsDisplayed()
            }
            compose.runOnIdle { nav.navigate(ToolPreviewRoute("missing", "missing")) }
            compose.onNodeWithText(compose.activity.getString(R.string.file_preview)).assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
            compose.onNodeWithText("Start").assertIsDisplayed()
        }
    }
}
