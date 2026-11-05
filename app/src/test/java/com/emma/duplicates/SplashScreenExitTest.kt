package com.emma.duplicates

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.core.view.WindowCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31, 32])
class SplashScreenExitTest {
    @Test
    fun animatedExitRestoresLightThemeIcons() = checkExit(darkTheme = false, animationsEnabled = true)

    @Test
    fun disabledAnimationsRestoreLightThemeIcons() = checkExit(darkTheme = false, animationsEnabled = false)

    @Test
    fun animatedExitPreservesDarkThemeIcons() = checkExit(darkTheme = true, animationsEnabled = true)

    @Test
    fun disabledAnimationsPreserveDarkThemeIcons() = checkExit(darkTheme = true, animationsEnabled = false)

    @Test
    fun animatedExitUsesTheThemeSelectedDuringTheAnimation() =
        checkExit(darkTheme = true, animationsEnabled = true, switchToLight = true)

    private fun checkExit(
        darkTheme: Boolean,
        animationsEnabled: Boolean,
        switchToLight: Boolean = false,
    ) {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        var style =
            if (darkTheme) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        val splash = mockk<SplashScreenViewProvider>()
        every { splash.view } returns View(activity)
        every { splash.iconView } returns View(activity)
        every { splash.remove() } answers {
            // Android 12 reapplies the XML theme's light icons when removing the splash.
            bars.isAppearanceLightStatusBars = false
            bars.isAppearanceLightNavigationBars = false
        }
        setAnimationScale(if (animationsEnabled) 1f else 0f)
        try {
            activity.animateSplashExit(splash) { style }
            if (switchToLight) {
                style = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val expectedLightAppearance = !darkTheme || switchToLight
            assertEquals(expectedLightAppearance, bars.isAppearanceLightStatusBars)
            assertEquals(expectedLightAppearance, bars.isAppearanceLightNavigationBars)
            verify(exactly = 1) { splash.remove() }
        } finally {
            setAnimationScale(1f)
            activityController.pause().stop().destroy()
        }
    }

    private fun setAnimationScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Unit>(
            ValueAnimator::class.java,
            "setDurationScale",
            ClassParameter.from(Float::class.javaPrimitiveType, scale),
        )
    }
}
