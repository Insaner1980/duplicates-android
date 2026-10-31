package com.emma.duplicates.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emma.duplicates.data.ScanStore
import org.junit.After
import org.junit.Before

abstract class ScanStoreDatabaseTest {
    protected lateinit var context: Context
    protected lateinit var database: DuplicatesDatabase
    protected lateinit var scanStore: ScanStore

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        database = inMemoryTestDatabase(context)
        scanStore = ScanStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }
}

fun inMemoryTestDatabase(context: Context): DuplicatesDatabase =
    Room
        .inMemoryDatabaseBuilder(context, DuplicatesDatabase::class.java)
        .allowMainThreadQueries()
        .build()

fun indexedPhoto(
    id: String,
    sessionId: String,
    path: String,
): IndexedFileEntity =
    IndexedFileEntity(
        id = id,
        sessionId = sessionId,
        canonicalPath = path,
        displayName = path.substringAfterLast('/'),
        extension = "jpg",
        mimeType = "image/jpeg",
        category = "photos",
        sizeBytes = 100L,
        lastModified = 10L,
        volume = "primary",
        parentPath = "/storage",
        readable = true,
        writable = true,
        favorite = false,
    )
