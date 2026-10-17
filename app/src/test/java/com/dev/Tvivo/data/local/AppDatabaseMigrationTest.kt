package com.dev.Tvivo.data.local

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v3-v5 hand-built fixtures also guard their v5 shape against Room's exported schema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {
    private lateinit var context: Context
    private val name = "migration-5-6-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun v5_user_rows_survive_and_schema_matches_a_fresh_v7_database() = assertMigrates(5)

    @Test
    fun v3_to_v7_chain_preserves_user_rows() = assertMigrates(3)

    @Test
    fun v4_to_v7_chain_preserves_user_rows() = assertMigrates(4)

    @Test
    fun hand_built_v5_fixture_matches_exported_v5_shape() {
        createOldDatabase(5)
        val path = "${AppDatabase::class.java.canonicalName}/5.json"
        val schema = javaClass.classLoader!!.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("Missing Room schema resource: $path")
        val entities = JSONObject(schema).getJSONObject("database").getJSONArray("entities")
        val expected = buildMap {
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                val primaryKey = entity.getJSONObject("primaryKey").getJSONArray("columnNames")
                val pkOrder = (0 until primaryKey.length()).associate { primaryKey.getString(it) to it + 1 }
                val shape = mutableSetOf<String>()
                val fields = entity.getJSONArray("fields")
                for (j in 0 until fields.length()) {
                    val field = fields.getJSONObject(j)
                    val column = field.getString("columnName")
                    shape += "column:$column:${field.getString("affinity")}:${field.getBoolean("notNull")}:${pkOrder[column] ?: 0}"
                }
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    val index = indices.getJSONObject(j)
                    val columns = index.getJSONArray("columnNames")
                    shape += "index:${index.getString("name")}:${index.getBoolean("unique")}:" +
                        (0 until columns.length()).joinToString(",") { columns.getString(it) }
                }
                val foreignKeys = entity.getJSONArray("foreignKeys")
                for (j in 0 until foreignKeys.length()) {
                    val fk = foreignKeys.getJSONObject(j)
                    val from = fk.getJSONArray("columnNames")
                    val to = fk.getJSONArray("referenceColumnNames")
                    shape += "fk:${fk.getString("referenceTable")}:${fk.getString("onDelete")}:${fk.getString("onUpdate")}:" +
                        (0 until from.length()).joinToString(",") { from.getString(it) } + ":" +
                        (0 until to.length()).joinToString(",") { to.getString(it) }
                }
                put(table, shape)
            }
        }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            val actual = mutableMapOf<String, Set<String>>()
            // android_metadata is added to every Android database; room_master_table is Room's own.
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT IN ('android_metadata','room_master_table')",
                null
            ).use { tables ->
                while (tables.moveToNext()) {
                    val table = tables.getString(0)
                    val shape = mutableSetOf<String>()
                    db.rawQuery("PRAGMA table_info(`$table`)", null).use { columns ->
                        while (columns.moveToNext()) {
                            shape += "column:${columns.getString(1)}:${columns.getString(2)}:${columns.getInt(3) != 0}:${columns.getInt(5)}"
                        }
                    }
                    db.rawQuery("PRAGMA index_list(`$table`)", null).use { indices ->
                        while (indices.moveToNext()) {
                            if (indices.getString(1).startsWith("sqlite_autoindex_")) continue
                            val indexName = indices.getString(1)
                            val indexColumns = mutableListOf<String>()
                            db.rawQuery("PRAGMA index_info(`$indexName`)", null).use { columns ->
                                while (columns.moveToNext()) indexColumns += columns.getString(2)
                            }
                            shape += "index:$indexName:${indices.getInt(2) != 0}:${indexColumns.joinToString(",")}"
                        }
                    }
                    db.rawQuery("PRAGMA foreign_key_list(`$table`)", null).use { fks ->
                        val groups = mutableMapOf<Int, MutableList<List<String>>>()
                        while (fks.moveToNext()) {
                            groups.getOrPut(fks.getInt(0)) { mutableListOf() } += listOf(
                                fks.getString(1), fks.getString(2), fks.getString(3),
                                fks.getString(4), fks.getString(5), fks.getString(6)
                            )
                        }
                        groups.values.forEach { rows ->
                            val ordered = rows.sortedBy { it[0].toInt() }
                            val first = ordered.first()
                            shape += "fk:${first[1]}:${first[5]}:${first[4]}:" +
                                ordered.joinToString(",") { it[2] } + ":" + ordered.joinToString(",") { it[3] }
                        }
                    }
                    actual[table] = shape
                }
            }
            assertEquals(expected, actual)
        }
    }

    private fun assertMigrates(version: Int) = runTest {
        createOldDatabase(version)
        val before = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            oldUserRows { sql -> db.rawQuery(sql, null) }
        }
        listOf("favourites", "resume_positions").forEach { table ->
            assertEquals(30, before["$table:count"])
            assertEquals(
                listOf("acct-a", "acct-b", "acct-c", "", "odd ' / ☺ :?").associateWith { 6 },
                before["$table:accounts"]
            )
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()
        val fresh = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening through Room runs its own migration schema validation first.
            val upgradedSql = migrated.openHelper.writableDatabase
            val freshSql = fresh.openHelper.writableDatabase

            assertEquals(schemaShape(freshSql), schemaShape(upgradedSql))
            upgradedSql.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals(0, cursor.count)
            }
            assertEquals("ok", scalarText(upgradedSql, "PRAGMA integrity_check"))

            val after = oldUserRows { sql -> upgradedSql.query(sql) }
            assertEquals(before, after)
            listOf("acct-a", "acct-b", "acct-c", "", "odd ' / ☺ :?", "catalog-only").forEach { account ->
                assertEquals("Adult", migrated.profileDao().get(account, account)?.kind)
            }
            listOf("favourites", "resume_positions").forEach { table ->
                upgradedSql.query("SELECT accountId, profileId FROM $table").use { cursor ->
                    while (cursor.moveToNext()) assertEquals(cursor.getString(0), cursor.getString(1))
                }
            }
        } finally {
            migrated.close()
            fresh.close()
        }
    }

    /** v3 base SQL, then the exact plot/rating ALTERs from MIGRATION_3_4 and MIGRATION_4_5. */
    private fun createOldDatabase(version: Int) {
        require(version in 3..5)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            val definitions = listOf(
                """CREATE TABLE categories (accountId TEXT NOT NULL, type TEXT NOT NULL,
                    categoryId TEXT NOT NULL, name TEXT NOT NULL, ordering INTEGER NOT NULL,
                    PRIMARY KEY(accountId,type,categoryId))""",
                """CREATE TABLE vod_streams (accountId TEXT NOT NULL, streamId INTEGER NOT NULL,
                    categoryId TEXT, name TEXT NOT NULL, nameDisplay TEXT NOT NULL,
                    nameNormalized TEXT NOT NULL, streamIcon TEXT, containerExtension TEXT,
                    added INTEGER, num INTEGER, generation INTEGER NOT NULL,
                    PRIMARY KEY(accountId,streamId))""",
                """CREATE TABLE live_streams (accountId TEXT NOT NULL, streamId INTEGER NOT NULL,
                    categoryId TEXT, name TEXT NOT NULL, nameDisplay TEXT NOT NULL,
                    nameNormalized TEXT NOT NULL, streamIcon TEXT, ext TEXT, added INTEGER,
                    num INTEGER, generation INTEGER NOT NULL, PRIMARY KEY(accountId,streamId))""",
                """CREATE TABLE series (accountId TEXT NOT NULL, seriesId INTEGER NOT NULL,
                    categoryId TEXT, name TEXT NOT NULL, nameDisplay TEXT NOT NULL,
                    nameNormalized TEXT NOT NULL, streamIcon TEXT, plot TEXT,
                    added INTEGER, num INTEGER, generation INTEGER NOT NULL,
                    PRIMARY KEY(accountId,seriesId))""",
                """CREATE TABLE series_episodes (accountId TEXT NOT NULL, seriesId INTEGER NOT NULL,
                    episodeId TEXT NOT NULL, seasonNumber INTEGER NOT NULL, episodeNum INTEGER NOT NULL,
                    title TEXT NOT NULL, containerExtension TEXT, durationSecs INTEGER, added INTEGER,
                    PRIMARY KEY(accountId,seriesId,episodeId))""",
                """CREATE TABLE sync_meta (accountId TEXT NOT NULL, contentType TEXT NOT NULL,
                    categoryId TEXT NOT NULL, lastSyncedAt INTEGER NOT NULL, generation INTEGER NOT NULL,
                    PRIMARY KEY(accountId,contentType,categoryId))""",
                """CREATE TABLE catalog_sync (accountId TEXT NOT NULL, contentType TEXT NOT NULL,
                    state TEXT NOT NULL, done INTEGER NOT NULL, total INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL, PRIMARY KEY(accountId,contentType))""",
                """CREATE TABLE resume_positions (accountId TEXT NOT NULL, contentType TEXT NOT NULL,
                    itemId TEXT NOT NULL, positionMs INTEGER NOT NULL, durationMs INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL, PRIMARY KEY(accountId,contentType,itemId))""",
                """CREATE TABLE favourites (accountId TEXT NOT NULL, contentType TEXT NOT NULL,
                    itemId TEXT NOT NULL, addedAt INTEGER NOT NULL,
                    PRIMARY KEY(accountId,contentType,itemId))""",
                "CREATE INDEX index_vod_streams_accountId_categoryId_nameNormalized ON vod_streams(accountId,categoryId,nameNormalized)",
                "CREATE INDEX index_vod_streams_accountId_nameNormalized ON vod_streams(accountId,nameNormalized)",
                "CREATE INDEX index_vod_streams_accountId_added ON vod_streams(accountId,added)",
                "CREATE INDEX index_live_streams_accountId_categoryId_nameNormalized ON live_streams(accountId,categoryId,nameNormalized)",
                "CREATE INDEX index_live_streams_accountId_nameNormalized ON live_streams(accountId,nameNormalized)",
                "CREATE INDEX index_series_accountId_categoryId_nameNormalized ON series(accountId,categoryId,nameNormalized)",
                "CREATE INDEX index_series_accountId_nameNormalized ON series(accountId,nameNormalized)",
                "CREATE INDEX index_series_accountId_added ON series(accountId,added)",
                "CREATE INDEX index_series_episodes_accountId_seriesId_seasonNumber_episodeNum ON series_episodes(accountId,seriesId,seasonNumber,episodeNum)"
            )
            definitions.forEach { db.execSQL(it) }
            if (version >= 4) db.execSQL("ALTER TABLE vod_streams ADD COLUMN plot TEXT")
            if (version >= 5) {
                db.execSQL("ALTER TABLE vod_streams ADD COLUMN rating REAL")
                db.execSQL("ALTER TABLE series ADD COLUMN rating REAL")
            }
            db.execSQL("INSERT INTO categories VALUES ('catalog-only','vod','10','Drama',0)")
            listOf("acct-a", "acct-b", "acct-c", "", "odd ' / ☺ :?").forEachIndexed { accountIndex, account ->
                listOf("vod", "live", "series").forEach { type ->
                    repeat(2) { itemIndex ->
                        val item = "item-$accountIndex-$type-$itemIndex"
                        val stamp = (accountIndex * 100 + itemIndex + 1).toLong()
                        db.execSQL("INSERT INTO favourites VALUES (?,?,?,?)", arrayOf(account, type, item, stamp))
                        db.execSQL("INSERT INTO resume_positions VALUES (?,?,?,?,?,?)",
                            arrayOf(account, type, item, 120_000L + stamp, 900_000L + stamp, stamp))
                    }
                }
            }
            db.version = version
        }
    }

    /** Counts and every pre-v6 value, both globally and per account, independent of row order. */
    private fun oldUserRows(query: (String) -> Cursor): Map<String, Any> = buildMap {
        val columns = mapOf(
            "favourites" to "accountId, contentType, itemId, addedAt",
            "resume_positions" to "accountId, contentType, itemId, positionMs, durationMs, updatedAt"
        )
        columns.forEach { (table, projection) ->
            val rows = mutableListOf<List<String>>()
            query("SELECT $projection FROM $table").use { cursor ->
                while (cursor.moveToNext()) rows += (0 until cursor.columnCount).map { cursor.getString(it) }
            }
            put("$table:rows", rows.sortedWith(compareBy { it.joinToString("\u0000") }))
            put("$table:count", rows.size)
            put("$table:accounts", rows.groupingBy { it[0] }.eachCount())
        }
    }

    @Test
    fun interrupted_resume_swap_rolls_back_entire_v5_transaction() {
        createOldDatabase(5)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            val before = oldUserRows { sql -> db.rawQuery(sql, null) }
            val failure = runCatching {
                db.beginTransaction()
                try {
                    // Copy of MIGRATION_5_6 through the resume table swap. Room runs
                    // onUpgrade in the same SQLite transaction boundary.
                    db.execSQL("""CREATE TABLE IF NOT EXISTS profile (
                        id TEXT NOT NULL, accountKey TEXT NOT NULL, name TEXT NOT NULL,
                        kind TEXT NOT NULL, createdAt INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, id))""")
                    db.execSQL("""INSERT INTO profile (id, accountKey, name, kind, createdAt)
                        SELECT accountId, accountId, 'Adult', 'Adult',
                            CAST(strftime('%s','now') AS INTEGER) * 1000
                        FROM (
                            SELECT accountId FROM categories UNION
                            SELECT accountId FROM vod_streams UNION
                            SELECT accountId FROM live_streams UNION
                            SELECT accountId FROM series UNION
                            SELECT accountId FROM series_episodes UNION
                            SELECT accountId FROM sync_meta UNION
                            SELECT accountId FROM catalog_sync UNION
                            SELECT accountId FROM resume_positions UNION
                            SELECT accountId FROM favourites)""")
                    db.execSQL("""CREATE TABLE IF NOT EXISTS resume_positions_new (
                        accountId TEXT NOT NULL, contentType TEXT NOT NULL,
                        itemId TEXT NOT NULL, positionMs INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                        profileId TEXT NOT NULL,
                        PRIMARY KEY(accountId, profileId, contentType, itemId),
                        FOREIGN KEY(accountId, profileId) REFERENCES profile(accountKey, id)
                            ON UPDATE NO ACTION ON DELETE CASCADE)""")
                    db.execSQL("""INSERT INTO resume_positions_new
                        (accountId, contentType, itemId, positionMs, durationMs, updatedAt, profileId)
                        SELECT accountId, contentType, itemId, positionMs, durationMs, updatedAt, accountId
                        FROM resume_positions""")
                    db.execSQL("DROP TABLE resume_positions")
                    db.execSQL("ALTER TABLE resume_positions_new RENAME TO resume_positions")
                    throw IllegalStateException("simulated interruption after resume swap")
                } finally {
                    db.endTransaction()
                }
            }.exceptionOrNull()
            assertEquals("simulated interruption after resume swap", failure?.message)
            assertEquals(5, db.version)
            assertEquals(before, oldUserRows { sql -> db.rawQuery(sql, null) })
            db.rawQuery("SELECT name FROM sqlite_master WHERE name IN ('profile','resume_positions_new')", null).use {
                assertEquals(0, it.count)
            }
        }
    }

    private fun schemaShape(db: SupportSQLiteDatabase): Map<String, List<String>> {
        val tables = mutableListOf<String>()
        db.query("""SELECT name FROM sqlite_master WHERE type='table'
            AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name""").use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        return tables.associateWith { table ->
            val shape = mutableListOf<String>()
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                while (cursor.moveToNext()) {
                    shape += "column:${cursor.getString(1)}:${cursor.getString(2)}:${cursor.getInt(3)}:${cursor.getString(4)}:${cursor.getInt(5)}"
                }
            }
            db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
                while (cursor.moveToNext()) {
                    shape += "fk:${cursor.getString(2)}:${cursor.getString(3)}:${cursor.getString(4)}:${cursor.getString(5)}:${cursor.getString(6)}"
                }
            }
            db.query("PRAGMA index_list(`$table`)").use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    val columns = mutableListOf<String>()
                    db.query("PRAGMA index_info(`$name`)").use { index ->
                        while (index.moveToNext()) columns += index.getString(2)
                    }
                    shape += "index:${if (name.startsWith("sqlite_autoindex_")) "auto" else name}:${cursor.getInt(2)}:${columns.joinToString(",")}"
                }
            }
            shape.sorted()
        }
    }

    private fun scalarText(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor -> cursor.moveToFirst(); cursor.getString(0) }
}
