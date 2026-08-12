package com.emma.duplicates.core.format

import android.app.Application
import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.R
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DisplayFormattersTest {
    private lateinit var originalLocale: Locale
    private lateinit var resources: Resources

    @Before
    fun rememberLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("fi-FI"))
        resources = ApplicationProvider.getApplicationContext<Context>().resources
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun decimalSizesAlwaysUseEnglishDecimalPoint() {
        assertEquals("1 byte", DisplayFormatters.size(resources, 1))
        assertEquals("999 bytes", DisplayFormatters.size(resources, 999))
        assertEquals("1 KB", DisplayFormatters.size(resources, 1_000))
        assertEquals("1.5 MB", DisplayFormatters.size(resources, 1_500_000))
        assertEquals("38.4 GB", DisplayFormatters.size(resources, 38_400_000_000))
    }

    @Test
    fun fileDateUsesReadableEnglishFormat() {
        val instant = Instant.parse("2024-05-18T09:30:00Z")

        assertEquals(
            "May 18, 2024, 09:30",
            DisplayFormatters.dateTime(resources, instant.toEpochMilli(), ZoneId.of("UTC")),
        )
    }

    @Test
    fun summarySeparatorPreservesRequiredSpaces() {
        assertEquals(" / ", resources.getString(R.string.summary_separator))
    }

    @Test
    fun storageSegmentsTreatReclaimableBytesAsPartOfUsedStorage() {
        val segments = StorageSegments.calculate(totalBytes = 256, freeBytes = 70, reclaimableBytes = 38)

        assertEquals(148, segments.otherUsedBytes)
        assertEquals(38, segments.reclaimableBytes)
        assertEquals(70, segments.freeBytes)
        assertEquals(256, segments.totalBytes)
        assertEquals(256, segments.otherUsedBytes + segments.reclaimableBytes + segments.freeBytes)
    }

    @Test
    fun storageSegmentsClampStaleReclaimableValueToCurrentUsedBytes() {
        val segments = StorageSegments.calculate(totalBytes = 100, freeBytes = 90, reclaimableBytes = 40)

        assertEquals(0, segments.otherUsedBytes)
        assertEquals(10, segments.reclaimableBytes)
        assertEquals(90, segments.freeBytes)
    }

    @Test
    fun storageSummaryRoundsSegmentsTogetherSoDisplayedValuesSumToTheDisplayedTotal() {
        val labels =
            DisplayFormatters.storageLabels(
                resources,
                StorageSegments(
                    totalBytes = 10_400_000_000L,
                    otherUsedBytes = 3_650_000_000L,
                    reclaimableBytes = 2_149_000_000L,
                    freeBytes = 4_601_000_000L,
                ),
            )

        assertEquals("10.4 GB", labels.total)
        assertEquals("3.7 GB", labels.otherUsed)
        assertEquals("2.1 GB", labels.reclaimable)
        assertEquals("4.6 GB", labels.free)
    }
}
