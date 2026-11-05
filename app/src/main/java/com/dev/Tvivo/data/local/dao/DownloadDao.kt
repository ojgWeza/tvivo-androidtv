package com.dev.Tvivo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dev.Tvivo.data.local.entities.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE accountId = :accountId ORDER BY createdAt, mediaId")
    fun observe(accountId: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE accountId = :accountId AND kind = :kind AND seriesId = :seriesId AND mediaId = :mediaId")
    suspend fun get(accountId: String, kind: String, seriesId: Int, mediaId: String): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY createdAt, mediaId")
    suspend fun getAll(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DownloadEntity)

    @Query("UPDATE downloads SET partialBytes = :partialBytes, updatedAt = :at WHERE accountId = :accountId AND kind = :kind AND seriesId = :seriesId AND mediaId = :mediaId")
    suspend fun updateProgress(accountId: String, kind: String, seriesId: Int, mediaId: String, partialBytes: Long, at: Long): Int

    @Query("UPDATE downloads SET state = :state, reservedBytes = :reservedBytes, updatedAt = :at, failureReason = :failureReason WHERE accountId = :accountId AND kind = :kind AND seriesId = :seriesId AND mediaId = :mediaId")
    suspend fun updateState(accountId: String, kind: String, seriesId: Int, mediaId: String, state: String, reservedBytes: Long, at: Long, failureReason: String? = null): Int

    @Query("DELETE FROM downloads WHERE accountId = :accountId AND kind = :kind AND seriesId = :seriesId AND mediaId = :mediaId")
    suspend fun delete(accountId: String, kind: String, seriesId: Int, mediaId: String): Int

    @Query("SELECT COALESCE(SUM(partialBytes), 0) FROM downloads")
    suspend fun sumPartialBytes(): Long

    @Query("SELECT COALESCE(SUM(reservedBytes), 0) FROM downloads WHERE state IN ('QUEUED','PAUSED','DOWNLOADING')")
    suspend fun sumReservedBytes(): Long
}
