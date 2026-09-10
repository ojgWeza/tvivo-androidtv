package com.dev.Tvivo.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dev.Tvivo.data.local.dao.CatalogSyncDao
import com.dev.Tvivo.data.local.dao.CategoryDao
import com.dev.Tvivo.data.local.dao.FavouriteDao
import com.dev.Tvivo.data.local.dao.LiveDao
import com.dev.Tvivo.data.local.dao.ResumeDao
import com.dev.Tvivo.data.local.dao.SeriesDao
import com.dev.Tvivo.data.local.dao.SyncMetaDao
import com.dev.Tvivo.data.local.dao.VodDao
import com.dev.Tvivo.data.local.entities.CatalogSyncEntity
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.data.local.entities.EpisodeEntity
import com.dev.Tvivo.data.local.entities.FavouriteEntity
import com.dev.Tvivo.data.local.entities.LiveStreamEntity
import com.dev.Tvivo.data.local.entities.ResumePositionEntity
import com.dev.Tvivo.data.local.entities.SeriesEntity
import com.dev.Tvivo.data.local.entities.SyncMetaEntity
import com.dev.Tvivo.data.local.entities.VodStreamEntity

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
        FavouriteEntity::class
    ],
    version = 5,
    exportSchema = false
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

    companion object {
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tvivo.db"
                )
                    // **Resume positions and favourites are now the reason this cannot
                    // be destructive.** The catalog tables are a cache and would happily
                    // re-sync, but `resume_positions` and `favourites` are the only user
                    // data the app holds and the panel cannot give them back — they are
                    // what Continue watching and Favourites are built from. A dropped
                    // table here is a silently emptied folder the user curated.
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
