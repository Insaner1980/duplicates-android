package com.emma.duplicates.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class UnusedAppsPolicyTest {
    private val now = TimeUnit.DAYS.toMillis(1000)
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun oldKnownUseQualifiesButRecentServiceUseAndUnknownHistoryDoNot() {
        val old = AppUsageEvidence(now - 365 * day, now, now - 120 * day, 0L, now - 300 * day)
        assertEquals(now - 120 * day, old.unusedSince(90, now))
        assertNull(old.copy(lastServiceAt = now - day).unusedSince(90, now))
        assertNull(old.copy(lastActivityAt = 0L).unusedSince(90, now))
        assertNull(old.copy(historyFrom = now - 30 * day).unusedSince(90, now))
        assertNull(old.copy(historyUntil = now - 30 * day).unusedSince(90, now))
        assertNull(old.copy(installedAt = now - 10 * day).unusedSince(90, now))
        assertNull(old.copy(lastActivityAt = now + day).unusedSince(90, now))
    }
}
