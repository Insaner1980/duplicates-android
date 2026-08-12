package com.emma.duplicates.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.core.database.DuplicatesDatabase
import com.emma.duplicates.core.storage.CachedFingerprint
import com.emma.duplicates.core.storage.FingerprintIdentity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomFingerprintCacheTest {
    private lateinit var database: DuplicatesDatabase
    private lateinit var store: ScanStore

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = ScanStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `cache maps complete identity reuses latest value and invalidates by location`() = runTest {
        val cache = RoomFingerprintCache(store, currentTimeMillis = { 500L })
        val first = cached(size = 100L, modified = 10L, quick = "quick-1", full = "full-1")

        cache.put(first)

        assertEquals(first, cache.find(PATH, "primary"))
        assertEquals(500L, store.findFingerprint(PATH, "primary")?.updatedAt)
        assertEquals("full-1", store.findReusableFingerprint(PATH, "primary", 100L, 10L)?.fullHash)

        val changed = cached(size = 101L, modified = 11L, quick = "quick-2", full = "full-2")
        cache.put(changed)

        assertEquals(changed, cache.find(PATH, "primary"))
        assertNull(store.findReusableFingerprint(PATH, "primary", 100L, 10L))
        assertEquals("full-2", store.findReusableFingerprint(PATH, "primary", 101L, 11L)?.fullHash)

        cache.invalidate(PATH, "primary")
        assertNull(cache.find(PATH, "primary"))
    }

    private fun cached(
        size: Long,
        modified: Long,
        quick: String,
        full: String,
    ) = CachedFingerprint(
        identity = FingerprintIdentity(PATH, size, modified, "primary"),
        quickSha256 = quick,
        fullSha256 = full,
    )

    private companion object {
        const val PATH = "/storage/emulated/0/Documents/a.txt"
    }
}
