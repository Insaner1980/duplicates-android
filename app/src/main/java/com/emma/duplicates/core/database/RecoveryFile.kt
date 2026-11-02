package com.emma.duplicates.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recovery_files")
data class RecoveryFile(
    @PrimaryKey val id: String,
    val originalPath: String,
    val storedPath: String,
    val createdAt: Long,
    val expiresAt: Long? = null,
)

@Dao
interface RecoveryDao {
    @Insert
    suspend fun insert(file: RecoveryFile)

    @Query("SELECT * FROM recovery_files ORDER BY createdAt DESC")
    fun observeFiles(): Flow<List<RecoveryFile>>

    @Query("SELECT * FROM recovery_files WHERE id = :id")
    suspend fun get(id: String): RecoveryFile?

    @Query("UPDATE recovery_files SET expiresAt = :expiresAt WHERE id = :id")
    suspend fun setExpiry(
        id: String,
        expiresAt: Long,
    )

    @Query("SELECT * FROM recovery_files WHERE expiresAt <= :now")
    suspend fun expired(now: Long): List<RecoveryFile>

    @Query("DELETE FROM recovery_files WHERE id = :id")
    suspend fun delete(id: String)
}
