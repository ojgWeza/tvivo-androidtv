package com.dev.Tvivo.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dev.Tvivo.data.local.dao.CatalogSyncDao
import com.dev.Tvivo.data.local.dao.CategoryDao
import com.dev.Tvivo.data.local.dao.DriveDao
import com.dev.Tvivo.data.local.dao.FavouriteDao
import com.dev.Tvivo.data.local.dao.LiveDao
import com.dev.Tvivo.data.local.dao.ProfileDao
import com.dev.Tvivo.data.local.dao.ProfileAllowDao
import com.dev.Tvivo.data.local.dao.RecentlyPlayedDao
import com.dev.Tvivo.data.local.dao.ResumeDao
import com.dev.Tvivo.data.local.dao.SeriesDao
import com.dev.Tvivo.data.local.dao.ShelfVisitDao
import com.dev.Tvivo.data.local.dao.SyncMetaDao
import com.dev.Tvivo.data.local.dao.VisitDao
import com.dev.Tvivo.data.local.dao.VodDao
import com.dev.Tvivo.data.local.dao.WatchedDao
import com.dev.Tvivo.data.local.entities.CatalogSyncEntity
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.data.local.entities.DriveEpisodeEntity
import com.dev.Tvivo.data.local.entities.DriveIndexStateEntity
import com.dev.Tvivo.data.local.entities.DriveMovieEntity
import com.dev.Tvivo.data.local.entities.DriveSeasonEntity
import com.dev.Tvivo.data.local.entities.DriveShowEntity
import com.dev.Tvivo.data.local.entities.EpisodeEntity
import com.dev.Tvivo.data.local.entities.FavouriteEntity
import com.dev.Tvivo.data.local.entities.LiveStreamEntity
import com.dev.Tvivo.data.local.entities.MusicTrackEntity
import com.dev.Tvivo.data.local.entities.ProfileAllowEntity
import com.dev.Tvivo.data.local.entities.ProfileEntity
import com.dev.Tvivo.data.local.entities.RecentlyPlayedEntity
import com.dev.Tvivo.data.local.entities.ResumePositionEntity
import com.dev.Tvivo.data.local.entities.SeriesEntity
import com.dev.Tvivo.data.local.entities.ShelfVisitEntity
import com.dev.Tvivo.data.local.entities.SyncMetaEntity
import com.dev.Tvivo.data.local.entities.VisitEntity
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import com.dev.Tvivo.data.local.entities.WatchedEntity

@Database(
    entities = [
        CategoryEntity::class,
        VodStreamEntity::class,
        LiveStreamEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        SyncMetaEntity::class,
        CatalogSyncEntity::class,
        ResumePositionEntity::class,
        FavouriteEntity::class,
        ProfileEntity::class,
        ProfileAllowEntity::class,
        WatchedEntity::class,
        VisitEntity::class,
        ShelfVisitEntity::class,
        RecentlyPlayedEntity::class,
        DriveMovieEntity::class, DriveShowEntity::class, DriveSeasonEntity::class,
        DriveEpisodeEntity::class, MusicTrackEntity::class, DriveIndexStateEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun vodDao(): VodDao
    abstract fun liveDao(): LiveDao
    abstract fun seriesDao(): SeriesDao
    abstract fun syncMetaDao(): SyncMetaDao
    abstract fun catalogSyncDao(): CatalogSyncDao
    abstract fun resumeDao(): ResumeDao
    abstract fun favouriteDao(): FavouriteDao
    abstract fun profileDao(): ProfileDao
    abstract fun profileAllowDao(): ProfileAllowDao
    abstract fun watchedDao(): WatchedDao
    abstract fun visitDao(): VisitDao
    abstract fun shelfVisitDao(): ShelfVisitDao
    abstract fun recentlyPlayedDao(): RecentlyPlayedDao
    abstract fun driveDao(): DriveDao

    companion object {
        const val DATABASE_NAME = "tvivo.db"

        /** Nullable detail metadata from the existing info responses. User data is untouched. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE series_episodes ADD COLUMN plot TEXT")
                db.execSQL("ALTER TABLE vod_streams ADD COLUMN genre TEXT")
                db.execSQL("ALTER TABLE vod_streams ADD COLUMN `cast` TEXT")
                db.execSQL("ALTER TABLE vod_streams ADD COLUMN year TEXT")
                db.execSQL("ALTER TABLE vod_streams ADD COLUMN durationMinutes INTEGER")
                db.execSQL("ALTER TABLE vod_streams ADD COLUMN trailer TEXT")
                db.execSQL("ALTER TABLE series ADD COLUMN genre TEXT")
                db.execSQL("ALTER TABLE series ADD COLUMN `cast` TEXT")
                db.execSQL("ALTER TABLE series ADD COLUMN year TEXT")
            }
        }

        /** New source-local cache only; every v7 table and user row is left intact. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val statements = listOf(
                    "CREATE TABLE IF NOT EXISTS `drive_movies` (`accountId` TEXT NOT NULL, `id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `title` TEXT NOT NULL, `nameDisplay` TEXT NOT NULL, `nameNormalized` TEXT NOT NULL, `directUrl` TEXT NOT NULL, `posterUrl` TEXT, `year` INTEGER, `plot` TEXT, `sortOrder` INTEGER NOT NULL, `generation` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `id`, `generation`))",
                    "CREATE INDEX IF NOT EXISTS `index_drive_movies_accountId_categoryId_sortOrder` ON `drive_movies` (`accountId`, `categoryId`, `sortOrder`)",
                    "CREATE INDEX IF NOT EXISTS `index_drive_movies_accountId_nameNormalized` ON `drive_movies` (`accountId`, `nameNormalized`)",
                    "CREATE INDEX IF NOT EXISTS `index_drive_movies_accountId_generation` ON `drive_movies` (`accountId`, `generation`)",
                    "CREATE TABLE IF NOT EXISTS `drive_shows` (`accountId` TEXT NOT NULL, `id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `title` TEXT NOT NULL, `nameDisplay` TEXT NOT NULL, `nameNormalized` TEXT NOT NULL, `posterUrl` TEXT, `year` INTEGER, `plot` TEXT, `sortOrder` INTEGER NOT NULL, `generation` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `id`, `generation`))",
                    "CREATE INDEX IF NOT EXISTS `index_drive_shows_accountId_categoryId_sortOrder` ON `drive_shows` (`accountId`, `categoryId`, `sortOrder`)",
                    "CREATE INDEX IF NOT EXISTS `index_drive_shows_accountId_nameNormalized` ON `drive_shows` (`accountId`, `nameNormalized`)",
                    "CREATE INDEX IF NOT EXISTS `index_drive_shows_accountId_generation` ON `drive_shows` (`accountId`, `generation`)",
                    "CREATE TABLE IF NOT EXISTS `drive_seasons` (`accountId` TEXT NOT NULL, `showId` TEXT NOT NULL, `seasonNumber` INTEGER NOT NULL, `title` TEXT, `sortOrder` INTEGER NOT NULL, `generation` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `showId`, `seasonNumber`, `generation`), FOREIGN KEY(`accountId`, `showId`, `generation`) REFERENCES `drive_shows`(`accountId`, `id`, `generation`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                    "CREATE INDEX IF NOT EXISTS `index_drive_seasons_accountId_showId_sortOrder` ON `drive_seasons` (`accountId`, `showId`, `sortOrder`)",
                    "CREATE INDEX IF NOT EXISTS `index_drive_seasons_accountId_showId_generation` ON `drive_seasons` (`accountId`, `showId`, `generation`)",
                    "CREATE TABLE IF NOT EXISTS `drive_episodes` (`accountId` TEXT NOT NULL, `id` TEXT NOT NULL, `showId` TEXT NOT NULL, `seasonNumber` INTEGER NOT NULL, `episodeNumber` INTEGER NOT NULL, `title` TEXT NOT NULL, `nameDisplay` TEXT NOT NULL, `nameNormalized` TEXT NOT NULL, `directUrl` TEXT NOT NULL, `posterUrl` TEXT, `plot` TEXT, `sortOrder` INTEGER NOT NULL, `generation` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `id`, `generation`), FOREIGN KEY(`accountId`, `showId`, `seasonNumber`, `generation`) REFERENCES `drive_seasons`(`accountId`, `showId`, `seasonNumber`, `generation`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                    "CREATE INDEX IF NOT EXISTS `index_drive_episodes_accountId_showId_seasonNumber_sortOrder` ON `drive_episodes` (`accountId`, `showId`, `seasonNumber`, `sortOrder`)",
                    "CREATE INDEX IF NOT EXISTS `index_drive_episodes_accountId_generation` ON `drive_episodes` (`accountId`, `generation`)",
                    "CREATE INDEX IF NOT EXISTS `index_drive_episodes_accountId_showId_seasonNumber_generation` ON `drive_episodes` (`accountId`, `showId`, `seasonNumber`, `generation`)",
                    "CREATE TABLE IF NOT EXISTS `music_tracks` (`accountId` TEXT NOT NULL, `id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `title` TEXT NOT NULL, `nameDisplay` TEXT NOT NULL, `nameNormalized` TEXT NOT NULL, `directUrl` TEXT NOT NULL, `artist` TEXT, `album` TEXT, `artworkUrl` TEXT, `year` INTEGER, `durationSeconds` INTEGER, `sortOrder` INTEGER NOT NULL, `generation` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `id`, `generation`))",
                    "CREATE INDEX IF NOT EXISTS `index_music_tracks_accountId_categoryId_sortOrder` ON `music_tracks` (`accountId`, `categoryId`, `sortOrder`)",
                    "CREATE INDEX IF NOT EXISTS `index_music_tracks_accountId_nameNormalized` ON `music_tracks` (`accountId`, `nameNormalized`)",
                    "CREATE INDEX IF NOT EXISTS `index_music_tracks_accountId_generation` ON `music_tracks` (`accountId`, `generation`)",
                    "CREATE TABLE IF NOT EXISTS `drive_index_state` (`accountId` TEXT NOT NULL, `kind` TEXT NOT NULL, `folderId` TEXT, `fileId` TEXT, `version` TEXT, `md5Checksum` TEXT, `modifiedAtMillis` INTEGER, `sizeBytes` INTEGER, `generation` INTEGER NOT NULL, `lastSuccessfulSyncAt` INTEGER, `bootstrapState` TEXT NOT NULL, PRIMARY KEY(`accountId`, `kind`))",
                    "CREATE INDEX IF NOT EXISTS `index_drive_index_state_accountId_bootstrapState` ON `drive_index_state` (`accountId`, `bootstrapState`)"
                )
                statements.forEach(db::execSQL)
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        /** v4 adds `vod_streams.plot`. The catalog re-syncs anyway, so back-filling is
         *  unnecessary — but the two user-data tables must survive, which is the whole
         *  point of doing this as a migration rather than a drop. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vod_streams ADD COLUMN plot TEXT")
            }
        }

        /**
         * v5 adds `rating` to `vod_streams` and `series`.
         *
         * `REAL` and nullable, because the normalised value is a 0–10 Double and *unrated*
         * has to be distinguishable from *rated zero* — a `NOT NULL DEFAULT 0` would make
         * every existing row look like a film rated 0.0.
         *
         * **Existing rows stay null until the next catalog sync.** Nothing back-fills them:
         * the rating only arrives with the list response, so it lands when the catalog is
         * next fetched. Ratings therefore appear after a `Refresh all`, not on upgrade —
         * which is the same bargain v4 made for `plot`, and is why the badge is written to
         * render nothing at all rather than a placeholder when the value is null.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vod_streams ADD COLUMN rating REAL")
                db.execSQL("ALTER TABLE series ADD COLUMN rating REAL")
            }
        }

        /** Move the two user-data tables to profile-aware keys without losing a row. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS profile (
                        id TEXT NOT NULL, accountKey TEXT NOT NULL, name TEXT NOT NULL,
                        kind TEXT NOT NULL, createdAt INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO profile (id, accountKey, name, kind, createdAt)
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
                        SELECT accountId FROM favourites
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS resume_positions_new (
                        accountId TEXT NOT NULL, contentType TEXT NOT NULL,
                        itemId TEXT NOT NULL, positionMs INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                        profileId TEXT NOT NULL,
                        PRIMARY KEY(accountId, profileId, contentType, itemId),
                        FOREIGN KEY(accountId, profileId) REFERENCES profile(accountKey, id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO resume_positions_new
                        (accountId, contentType, itemId, positionMs, durationMs, updatedAt, profileId)
                    SELECT accountId, contentType, itemId, positionMs, durationMs, updatedAt, accountId
                    FROM resume_positions
                """.trimIndent())
                db.execSQL("DROP TABLE resume_positions")
                db.execSQL("ALTER TABLE resume_positions_new RENAME TO resume_positions")
                db.execSQL("CREATE INDEX index_resume_positions_accountId_profileId ON resume_positions(accountId, profileId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS favourites_new (
                        accountId TEXT NOT NULL, contentType TEXT NOT NULL,
                        itemId TEXT NOT NULL, addedAt INTEGER NOT NULL,
                        profileId TEXT NOT NULL,
                        PRIMARY KEY(accountId, profileId, contentType, itemId),
                        FOREIGN KEY(accountId, profileId) REFERENCES profile(accountKey, id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO favourites_new (accountId, contentType, itemId, addedAt, profileId)
                    SELECT accountId, contentType, itemId, addedAt, accountId FROM favourites
                """.trimIndent())
                db.execSQL("DROP TABLE favourites")
                db.execSQL("ALTER TABLE favourites_new RENAME TO favourites")
                db.execSQL("CREATE INDEX index_favourites_accountId_profileId ON favourites(accountId, profileId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS profile_allow (
                        profileId TEXT NOT NULL, accountKey TEXT NOT NULL,
                        contentType TEXT NOT NULL, allowKey TEXT NOT NULL,
                        itemId TEXT NOT NULL, categoryId TEXT,
                        PRIMARY KEY(accountKey, profileId, contentType, allowKey),
                        FOREIGN KEY(accountKey, profileId) REFERENCES profile(accountKey, id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS watched (
                        profileId TEXT NOT NULL, accountKey TEXT NOT NULL,
                        contentType TEXT NOT NULL, itemId TEXT NOT NULL,
                        watchedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, profileId, contentType, itemId),
                        FOREIGN KEY(accountKey, profileId) REFERENCES profile(accountKey, id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS visits (
                        profileId TEXT NOT NULL, accountKey TEXT NOT NULL,
                        contentType TEXT NOT NULL, categoryId TEXT NOT NULL,
                        count INTEGER NOT NULL, lastVisitedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, profileId, contentType, categoryId),
                        FOREIGN KEY(accountKey, profileId) REFERENCES profile(accountKey, id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS shelf_visits (
                        profileId TEXT NOT NULL, accountKey TEXT NOT NULL,
                        contentType TEXT NOT NULL, categoryId TEXT NOT NULL,
                        lastVisitedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, profileId, contentType, categoryId),
                        FOREIGN KEY(accountKey, profileId) REFERENCES profile(accountKey, id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS recently_played (
                        profileId TEXT NOT NULL, accountKey TEXT NOT NULL,
                        contentType TEXT NOT NULL, itemId TEXT NOT NULL,
                        playedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, profileId, contentType, itemId),
                        FOREIGN KEY(accountKey, profileId) REFERENCES profile(accountKey, id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    // **Resume positions and favourites are now the reason this cannot
                    // be destructive.** The catalog tables are a cache and would happily
                    // re-sync, but `resume_positions` and `favourites` are the only user
                    // data the app holds and the panel cannot give them back — they are
                    // what Continue watching and Favourites are built from. A dropped
                    // table here is a silently emptied folder the user curated.
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                    .also { instance = it }
            }
    }
}
