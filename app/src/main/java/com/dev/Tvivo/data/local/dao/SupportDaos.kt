package com.dev.Tvivo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.dev.Tvivo.data.local.entities.CatalogSyncEntity
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.data.local.entities.FavouriteEntity
import com.dev.Tvivo.data.local.entities.ResumePositionEntity
import com.dev.Tvivo.data.local.entities.SyncMetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query(
        """
        SELECT * FROM categories
        WHERE accountId = :accountId AND type = :type
        ORDER BY ordering ASC
        """
    )
    fun observe(accountId: String, type: String): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE accountId = :accountId AND type = :type")
    suspend fun deleteAll(accountId: String, type: String)

    /**
     * Handles renamed, removed and reordered categories in one shot — a merge would
     * leave deleted categories in the rail forever.
     */
    @Transaction
    suspend fun replaceAll(accountId: String, type: String, rows: List<CategoryEntity>) {
        deleteAll(accountId, type)
        insertAll(rows)
    }
}

@Dao
interface SyncMetaDao {

    @Query(
        """
        SELECT * FROM sync_meta
        WHERE accountId = :accountId AND contentType = :contentType AND categoryId = :categoryId
        """
    )
    suspend fun get(accountId: String, contentType: String, categoryId: String): SyncMetaEntity?

    /** `ALL` displays the *oldest* category stamp as its freshness. */
    @Query(
        """
        SELECT MIN(lastSyncedAt) FROM sync_meta
        WHERE accountId = :accountId AND contentType = :contentType
        """
    )
    suspend fun oldestStamp(accountId: String, contentType: String): Long?

    @Query(
        """
        SELECT categoryId FROM sync_meta
        WHERE accountId = :accountId AND contentType = :contentType
          AND lastSyncedAt < :staleBefore
        """
    )
    suspend fun staleCategories(
        accountId: String,
        contentType: String,
        staleBefore: Long
    ): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SyncMetaEntity)

    @Query("DELETE FROM sync_meta WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}

@Dao
interface CatalogSyncDao {

    @Query("SELECT * FROM catalog_sync WHERE accountId = :accountId AND contentType = :contentType")
    fun observe(accountId: String, contentType: String): Flow<CatalogSyncEntity?>

    @Query("SELECT * FROM catalog_sync WHERE accountId = :accountId AND contentType = :contentType")
    suspend fun get(accountId: String, contentType: String): CatalogSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CatalogSyncEntity)
}

@Dao
interface ResumeDao {

    @Query(
        """
        SELECT * FROM resume_positions
        WHERE accountId = :accountId AND contentType = :contentType AND itemId = :itemId
        """
    )
    suspend fun get(accountId: String, contentType: String, itemId: String): ResumePositionEntity?

    /**
     * Continue watching: >60 s watched and <92% complete, newest first, capped at 50.
     * The cap is the point — the reference app shows `CONTINUE WATCHING 77`, which means
     * it never removes anything, and nobody has 77 films in progress.
     */
    @Query(
        """
        SELECT * FROM resume_positions
        WHERE accountId = :accountId AND contentType = :contentType
          AND positionMs > 60000
          AND (durationMs = 0 OR positionMs < durationMs * 0.92)
        ORDER BY updatedAt DESC LIMIT 50
        """
    )
    fun continueWatching(accountId: String, contentType: String): Flow<List<ResumePositionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ResumePositionEntity)

    @Query(
        """
        DELETE FROM resume_positions
        WHERE accountId = :accountId AND contentType = :contentType AND itemId = :itemId
        """
    )
    suspend fun remove(accountId: String, contentType: String, itemId: String)
}

@Dao
interface FavouriteDao {

    @Query(
        """
        SELECT * FROM favourites
        WHERE accountId = :accountId AND contentType = :contentType
        ORDER BY addedAt DESC
        """
    )
    fun observe(accountId: String, contentType: String): Flow<List<FavouriteEntity>>

    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM favourites
        WHERE accountId = :accountId AND contentType = :contentType AND itemId = :itemId)
        """
    )
    fun isFavourite(accountId: String, contentType: String, itemId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(row: FavouriteEntity)

    @Query(
        """
        DELETE FROM favourites
        WHERE accountId = :accountId AND contentType = :contentType AND itemId = :itemId
        """
    )
    suspend fun remove(accountId: String, contentType: String, itemId: String)
}
