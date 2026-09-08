package com.dev.Tvivo.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 3,
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tvivo.db"
                )
                    // No schema history to preserve yet, and the whole DB is a cache that
                    // re-syncs from the panel. Revisit once resume positions matter.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
