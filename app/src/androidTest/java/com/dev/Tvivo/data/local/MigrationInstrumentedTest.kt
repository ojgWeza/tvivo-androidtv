package com.dev.Tvivo.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device SQLite companion to the Robolectric exported-schema migration test. */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java
    )

    private val name = "exported-schema-migration.db"
    private val accounts = listOf("fixture-a", "fixture-b", "fixture-c")
    private val types = listOf("vod", "live", "series")

    @Test
    fun exported_v6_and_v7_preserve_profile_and_user_state_on_upgrade() {
        for (version in listOf(6, 7)) {
            val databaseName = "drive-v$version.db"
            helper.createDatabase(databaseName, version).use { db ->
                db.execSQL("INSERT INTO profile (id,accountKey,name,kind,createdAt) VALUES ('adult','account','Adult','Adult',1)")
                db.execSQL("INSERT INTO favourites (accountId,profileId,contentType,itemId,addedAt) VALUES ('account','adult','vod','movie',2)")
                db.execSQL("INSERT INTO resume_positions (accountId,profileId,contentType,itemId,positionMs,durationMs,updatedAt) VALUES ('account','adult','vod','movie',30000,90000,3)")
                db.execSQL("INSERT INTO watched (profileId,accountKey,contentType,itemId,watchedAt) VALUES ('adult','account','series','episode',4)")
                db.execSQL("INSERT INTO recently_played (profileId,accountKey,contentType,itemId,playedAt) VALUES ('adult','account','vod','movie',5)")
            }
            val migrations = if (version == 6) arrayOf(AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
                else arrayOf(AppDatabase.MIGRATION_7_8)
            helper.runMigrationsAndValidate(databaseName, 8, true, *migrations).use { db ->
                for ((table, column, expected) in listOf(
                    Triple("profile", "id", "adult"), Triple("favourites", "itemId", "movie"),
                    Triple("resume_positions", "itemId", "movie"), Triple("watched", "itemId", "episode"),
                    Triple("recently_played", "itemId", "movie")
                )) {
                    db.query("SELECT $column FROM $table").use { rows ->
                        assertTrue(rows.moveToFirst())
                        assertEquals(expected, rows.getString(0))
                        assertEquals(1, rows.count)
                    }
                }
                db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            }
        }
    }

    @Test
    fun exported_v5_migrates_to_valid_v8_without_losing_user_rows() {
        val before = helper.createDatabase(name, 5).use { db ->
            seed(db)
            oldUserRows(db)
        }
        assertUserRows(before)

        // MigrationTestHelper reads 8.json and checks the complete resulting schema.
        helper.runMigrationsAndValidate(name, 8, true,
            AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8).use { db ->
            val after = oldUserRows(db)
            assertEquals(before, after)
            assertUserRows(after)
            for (table in listOf("favourites", "resume_positions")) {
                db.query("SELECT accountId, profileId FROM $table").use { rows ->
                    while (rows.moveToNext()) assertEquals(rows.getString(0), rows.getString(1))
                }
            }
            mapOf(
                "categories" to 9, "sync_meta" to 9, "catalog_sync" to 9,
                "vod_streams" to 3, "live_streams" to 3, "series" to 3,
                "series_episodes" to 3
            ).forEach { (table, count) ->
                db.query("SELECT COUNT(*) FROM $table").use { rows ->
                    rows.moveToFirst()
                    assertEquals(count, rows.getInt(0))
                }
            }
            db.query("SELECT id, accountKey, name, kind, createdAt FROM profile").use { rows ->
                assertEquals(accounts.size, rows.count)
                val profileAccounts = mutableSetOf<String>()
                while (rows.moveToNext()) {
                    profileAccounts += rows.getString(1)
                    assertEquals(rows.getString(1), rows.getString(0))
                    assertEquals("Adult", rows.getString(2))
                    assertEquals("Adult", rows.getString(3))
                    assertTrue(rows.getLong(4) > 0)
                }
                assertEquals(accounts.toSet(), profileAccounts)
            }
            db.query("PRAGMA foreign_key_check").use { rows -> assertEquals(0, rows.count) }
        }
    }

    private fun seed(db: SupportSQLiteDatabase) {
        accounts.forEachIndexed { accountIndex, account ->
            types.forEachIndexed { typeIndex, type ->
                val category = "cat-$type"
                db.execSQL(
                    "INSERT INTO categories (accountId,type,categoryId,name,ordering) VALUES (?,?,?,?,?)",
                    arrayOf(account, type, category, "Fixture $type", typeIndex)
                )
                db.execSQL(
                    "INSERT INTO sync_meta (accountId,contentType,categoryId,lastSyncedAt,generation) VALUES (?,?,?,?,?)",
                    arrayOf(account, type, category, 1000L + accountIndex, 7)
                )
                db.execSQL(
                    "INSERT INTO catalog_sync (accountId,contentType,state,done,total,updatedAt) VALUES (?,?,?,?,?,?)",
                    arrayOf(account, type, "complete", 2, 2, 2000L + accountIndex)
                )
                repeat(2) { itemIndex ->
                    val item = "item-$type-$itemIndex"
                    val stamp = 10_000L + accountIndex * 100 + typeIndex * 10 + itemIndex
                    db.execSQL(
                        "INSERT INTO favourites (accountId,contentType,itemId,addedAt) VALUES (?,?,?,?)",
                        arrayOf(account, type, item, stamp)
                    )
                    db.execSQL(
                        "INSERT INTO resume_positions (accountId,contentType,itemId,positionMs,durationMs,updatedAt) VALUES (?,?,?,?,?,?)",
                        arrayOf(account, type, item, 120_000L + stamp, 900_000L + stamp, stamp + 500)
                    )
                }
            }
            db.execSQL(
                "INSERT INTO vod_streams (accountId,streamId,categoryId,name,nameDisplay,nameNormalized,containerExtension,plot,rating,added,num,generation) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(account, 101, "cat-vod", "Movie $account", "Movie $account", "movie $account", "mp4", "Fixture plot", 7.5, 12345L, 1, 7)
            )
            db.execSQL(
                "INSERT INTO live_streams (accountId,streamId,categoryId,name,nameDisplay,nameNormalized,ext,added,num,generation) VALUES (?,?,?,?,?,?,?,?,?,?)",
                arrayOf(account, 201, "cat-live", "Channel $account", "Channel $account", "channel $account", "ts", 12346L, 2, 7)
            )
            db.execSQL(
                "INSERT INTO series (accountId,seriesId,categoryId,name,nameDisplay,nameNormalized,plot,rating,added,num,generation) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(account, 301, "cat-series", "Show $account", "Show $account", "show $account", "Fixture series", 8.0, 12347L, 3, 7)
            )
            db.execSQL(
                "INSERT INTO series_episodes (accountId,seriesId,episodeId,seasonNumber,episodeNum,title,containerExtension,durationSecs,added) VALUES (?,?,?,?,?,?,?,?,?)",
                arrayOf(account, 301, "episode-1", 1, 1, "Pilot $account", "mkv", 2400, 12348L)
            )
        }
    }

    private fun oldUserRows(db: SupportSQLiteDatabase): Map<String, Map<String, List<List<String>>>> =
        listOf(
            "favourites" to "accountId,contentType,itemId,addedAt",
            "resume_positions" to "accountId,contentType,itemId,positionMs,durationMs,updatedAt"
        ).associate { (table, columns) ->
            val byAccount = mutableMapOf<String, MutableList<List<String>>>()
            db.query("SELECT $columns FROM $table ORDER BY accountId,contentType,itemId").use { rows ->
                while (rows.moveToNext()) {
                    val values = (0 until rows.columnCount).map { rows.getString(it) }
                    byAccount.getOrPut(values[0]) { mutableListOf() } += values
                }
            }
            table to byAccount
        }

    private fun assertUserRows(rows: Map<String, Map<String, List<List<String>>>>) {
        for (table in listOf("favourites", "resume_positions")) {
            val byAccount = rows.getValue(table)
            assertEquals(accounts.toSet(), byAccount.keys)
            assertEquals(18, byAccount.values.sumOf { it.size })
            accounts.forEach { assertEquals(6, byAccount.getValue(it).size) }
        }
    }
}
