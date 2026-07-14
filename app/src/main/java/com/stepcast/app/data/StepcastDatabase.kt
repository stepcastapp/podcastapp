package com.stepcast.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Podcast::class, Episode::class, QueueItem::class,
        SmartPlay::class, SmartPlayEntry::class, CategoryMeta::class,
        ListenStat::class, PodcastCategory::class
    ],
    version = 18,
    exportSchema = false
)
abstract class StepcastDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueDao(): QueueDao
    abstract fun smartPlayDao(): SmartPlayDao
    abstract fun categoryDao(): CategoryDao
    abstract fun podcastCategoryDao(): PodcastCategoryDao
    abstract fun listenStatDao(): ListenStatDao

    companion object {
        @Volatile
        private var instance: StepcastDatabase? = null

        // From here on, schema changes get REAL migrations — installs now
        // carry subscriptions/history worth keeping. The destructive
        // fallback only fires for pre-v9 databases with no migration path.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE episodes ADD COLUMN playedAtMs INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE podcasts ADD COLUMN episodeCap INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE podcasts ADD COLUMN sortOldestFirst INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE podcasts ADD COLUMN autoQueue INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE podcasts ADD COLUMN consecutiveFailures " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS listen_stats (" +
                        "podcastId INTEGER NOT NULL PRIMARY KEY, " +
                        "wallMs INTEGER NOT NULL, contentMs INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE smartplays ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE categories ADD COLUMN anchorMinutes INTEGER NOT NULL DEFAULT -1"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS podcast_categories (" +
                        "podcastId INTEGER NOT NULL, category TEXT NOT NULL, " +
                        "PRIMARY KEY(podcastId, category))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_podcast_categories_category " +
                        "ON podcast_categories(category)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO podcast_categories (podcastId, category) " +
                        "SELECT id, folder FROM podcasts " +
                        "WHERE folder IS NOT NULL AND folder != ''"
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE episodes ADD COLUMN downloadAttempts " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN transcriptUrl TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN transcriptType TEXT")
                db.execSQL(
                    "ALTER TABLE episodes ADD COLUMN inboxDismissed " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE smartplays ADD COLUMN continuous " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE podcasts ADD COLUMN adJumpSec " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Existing episodes default to eligible (1) so current subscriptions
        // keep auto-downloading; only future bulk imports mark their backlog 0.
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE episodes ADD COLUMN autoDownloadEligible " +
                        "INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        fun get(context: Context): StepcastDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StepcastDatabase::class.java,
                    "stepcast.db"
                )
                    .addMigrations(
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                        MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
                        MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18
                    )
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
