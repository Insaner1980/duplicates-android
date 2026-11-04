package com.emma.duplicates

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en")
class ToolStringResourcesTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun selectionCountsPreserveZeroSingularAndPluralLabels() {
        for (count in listOf(0, 1, 2)) {
            assertEquals("$count selected", resources.getQuantityString(R.plurals.tool_selected_count, count, count))
            assertEquals(
                "$count selected outside this view",
                resources.getQuantityString(R.plurals.tool_hidden_selected, count, count),
            )
        }
    }

    @Test
    fun unusedDaysUseSingularOnlyForOneDay() {
        for (count in listOf(0, 1, 2, 90)) {
            val unit = if (count == 1) "day" else "days"
            assertEquals(
                "$count $unit",
                resources.getQuantityString(R.plurals.tool_unused_days, count, count),
            )
        }
    }
}
