package com.dev.Tvivo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.dev.Tvivo.data.local.entities.ProfileAllowEntity
import com.dev.Tvivo.data.local.entities.ProfileEntity
import com.dev.Tvivo.data.local.entities.PROFILE_ADULT
import com.dev.Tvivo.data.local.entities.PROFILE_KIDS
import com.dev.Tvivo.data.local.entities.RecentlyPlayedEntity
import com.dev.Tvivo.data.local.entities.ShelfVisitEntity
import com.dev.Tvivo.data.local.entities.VisitEntity
import com.dev.Tvivo.data.local.entities.WatchedEntity
import kotlinx.coroutines.flow.Flow

/** Shared guard for every profile-owned write. Inherit it on a Room DAO so the
 * check and the write can run in the same @Transaction. */
@Dao
interface ProfileWriteGuard {
    @Query("""INSERT OR IGNORE INTO profile (id, accountKey, name, kind, createdAt)
        VALUES (:accountKey, :accountKey, 'Adult', 'Adult', :createdAt)""")
    suspend fun ensureAdult(accountKey: String, createdAt: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM profile WHERE accountKey = :accountKey AND id = :profileId)")
    suspend fun profileExists(accountKey: String, profileId: String): Boolean

    @Transaction
    suspend fun ensureWritableProfile(accountKey: String, profileId: String) {
        ensureAdult(accountKey, System.currentTimeMillis())
        require(profileExists(accountKey, profileId)) {
            "Unknown profileId for account: $profileId"
        }
    }
}

@Dao
interface ProfileDao : ProfileWriteGuard {
    @Query("SELECT * FROM profile WHERE accountKey = :accountKey ORDER BY createdAt, id")
    fun observe(accountKey: String): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profile WHERE accountKey = :accountKey AND id = :id")
    suspend fun get(accountKey: String, id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: ProfileEntity)

    @Query("""DELETE FROM profile WHERE accountKey = :accountKey AND id = :id
        AND kind = 'Kids' AND id != accountKey""")
    suspend fun deleteRow(accountKey: String, id: String): Int

    /** Deletes a Kids profile and its profile-owned rows via FK cascades.
     * Returns false for an Adult or missing profile; default Adult data is never deleted. */
    @Transaction
    suspend fun deleteProfile(accountKey: String, id: String): Boolean {
        val profile = get(accountKey, id) ?: return false
        if (profile.kind == PROFILE_ADULT || profile.kind != PROFILE_KIDS || id == accountKey) return false
        return deleteRow(accountKey, id) == 1
    }
}

@Dao
interface ProfileAllowDao : ProfileWriteGuard {
    @Query("""SELECT * FROM profile_allow WHERE accountKey = :accountKey
        AND profileId = :profileId ORDER BY contentType, allowKey""")
    fun observe(accountKey: String, profileId: String): Flow<List<ProfileAllowEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRow(row: ProfileAllowEntity)

    @Transaction
    suspend fun upsert(row: ProfileAllowEntity) {
        ensureWritableProfile(row.accountKey, row.profileId)
        upsertRow(row)
    }

    @Query("""DELETE FROM profile_allow WHERE accountKey = :accountKey
        AND profileId = :profileId AND contentType = :contentType AND allowKey = :allowKey""")
    suspend fun remove(accountKey: String, profileId: String, contentType: String, allowKey: String)
}

@Dao
interface WatchedDao : ProfileWriteGuard {
    /** Caller chunks ids to 200, scoped to the account's default Adult profile. */
    @Query("""SELECT * FROM watched WHERE accountKey = :accountKey AND profileId = :accountKey
        AND contentType = :contentType AND itemId IN (:itemIds)""")
    suspend fun forItems(accountKey: String, contentType: String, itemIds: List<String>): List<WatchedEntity>
    @Query("""SELECT * FROM watched WHERE accountKey = :accountKey AND profileId = :profileId
        AND contentType = :contentType AND itemId = :itemId""")
    suspend fun get(accountKey: String, profileId: String, contentType: String, itemId: String): WatchedEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markRow(row: WatchedEntity)

    @Transaction
    suspend fun mark(row: WatchedEntity) {
        ensureWritableProfile(row.accountKey, row.profileId)
        markRow(row)
    }

    @Query("""DELETE FROM watched WHERE accountKey = :accountKey AND profileId = :profileId
        AND contentType = :contentType AND itemId = :itemId""")
    suspend fun unmark(accountKey: String, profileId: String, contentType: String, itemId: String)
}

@Dao
interface VisitDao : ProfileWriteGuard {
    @Query("""SELECT * FROM visits WHERE accountKey = :accountKey AND profileId = :profileId
        AND contentType = :contentType AND categoryId = :categoryId""")
    suspend fun get(accountKey: String, profileId: String, contentType: String, categoryId: String): VisitEntity?

    @Query("""SELECT * FROM visits WHERE accountKey = :accountKey AND profileId = :profileId
        AND contentType = :contentType ORDER BY count DESC, lastVisitedAt DESC, categoryId""")
    fun mostVisited(accountKey: String, profileId: String, contentType: String): Flow<List<VisitEntity>>

    @Query("""SELECT * FROM visits WHERE accountKey = :accountKey AND profileId = :profileId
        AND contentType = :contentType AND count > 0
        ORDER BY count DESC, lastVisitedAt DESC, categoryId LIMIT 3""")
    suspend fun topThree(accountKey: String, profileId: String, contentType: String): List<VisitEntity>

    @Query("""INSERT OR IGNORE INTO visits
        (profileId, accountKey, contentType, categoryId, count, lastVisitedAt)
        VALUES (:profileId, :accountKey, :contentType, :categoryId, 0, :visitedAt)""")
    suspend fun seed(accountKey: String, profileId: String, contentType: String, categoryId: String, visitedAt: Long)

    @Query("""UPDATE visits SET count = count + 1, lastVisitedAt = :visitedAt
        WHERE accountKey = :accountKey AND profileId = :profileId
          AND contentType = :contentType AND categoryId = :categoryId""")
    suspend fun increment(accountKey: String, profileId: String, contentType: String, categoryId: String, visitedAt: Long)

    @Transaction
    suspend fun record(accountKey: String, profileId: String, contentType: String, categoryId: String, visitedAt: Long) {
        ensureWritableProfile(accountKey, profileId)
        seed(accountKey, profileId, contentType, categoryId, visitedAt)
        increment(accountKey, profileId, contentType, categoryId, visitedAt)
    }
}

@Dao
interface ShelfVisitDao : ProfileWriteGuard {
    @Query("""SELECT * FROM shelf_visits WHERE accountKey = :accountKey AND profileId = :profileId
        AND contentType = :contentType AND categoryId = :categoryId""")
    suspend fun get(accountKey: String, profileId: String, contentType: String, categoryId: String): ShelfVisitEntity?

    /** First lookup uses now as the comparison point without writing a synthetic visit. */
    suspend fun lastVisitOrNow(accountKey: String, profileId: String, contentType: String, categoryId: String, now: Long): Long =
        get(accountKey, profileId, contentType, categoryId)?.lastVisitedAt ?: now

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordRow(row: ShelfVisitEntity)

    @Transaction
    suspend fun record(row: ShelfVisitEntity) {
        ensureWritableProfile(row.accountKey, row.profileId)
        recordRow(row)
    }
}

@Dao
interface RecentlyPlayedDao : ProfileWriteGuard {
    @Query("""SELECT * FROM recently_played WHERE accountKey = :accountKey AND profileId = :profileId
        AND contentType = :contentType ORDER BY playedAt DESC, itemId LIMIT :limit""")
    fun recent(accountKey: String, profileId: String, contentType: String, limit: Int): Flow<List<RecentlyPlayedEntity>>

    @Query("""SELECT * FROM recently_played WHERE accountKey = :accountKey AND profileId = :profileId
        AND contentType = :contentType ORDER BY playedAt DESC, itemId LIMIT :limit""")
    suspend fun recentSnapshot(accountKey: String, profileId: String, contentType: String, limit: Int): List<RecentlyPlayedEntity>

    @Query("""SELECT COUNT(*) FROM recently_played WHERE accountKey = :accountKey
        AND profileId = :profileId AND contentType = :contentType""")
    suspend fun count(accountKey: String, profileId: String, contentType: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordRow(row: RecentlyPlayedEntity)

    @Transaction
    suspend fun record(row: RecentlyPlayedEntity) {
        ensureWritableProfile(row.accountKey, row.profileId)
        recordRow(row)
    }
}
