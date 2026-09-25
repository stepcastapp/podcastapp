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
        ListenStat::class, PodcastCategory::class,
        EpisodeFts::class, Bookmark::class, ListenDaily::class
    ],
    version = 24,
    exportSchema = true
)
abstract class StepcastDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueDao(): QueueDao
    abstract fun smartPlayDao(): SmartPlayDao
    abstract fun categoryDao(): CategoryDao
    abstract fun podcastCategoryDao(): PodcastCategoryDao
    abstract fun listenStatDao(): ListenStatDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun listenDailyDao(): ListenDailyDao

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

        // Schedule paradigm rework: per-show rules replace per-category
        // rolling intervals. Default 0 = Automatic. Legacy category cadence
        // maps to the nearest rule: an anchored cadence becomes a pinned
        // daily-at rule (anchor preserved); a 1-2h rolling cadence becomes
        // hourly (applied second, so it wins for multi-category shows);
        // everything else becomes Automatic — the honest replacement for a
        // frameless "every N hours".
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE podcasts ADD COLUMN scheduleMode " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE podcasts ADD COLUMN scheduleParam " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE podcasts SET scheduleMode = 2, scheduleParam = (" +
                        "SELECT c.anchorMinutes FROM categories c " +
                        "JOIN podcast_categories pc ON pc.category = c.name " +
                        "WHERE pc.podcastId = podcasts.id " +
                        "AND c.refreshHours > 0 AND c.anchorMinutes >= 0 " +
                        "LIMIT 1) " +
                        "WHERE id IN (SELECT pc.podcastId FROM podcast_categories pc " +
                        "JOIN categories c ON pc.category = c.name " +
                        "WHERE c.refreshHours > 0 AND c.anchorMinutes >= 0)"
                )
                db.execSQL(
                    "UPDATE podcasts SET scheduleMode = 1, scheduleParam = 0 " +
                        "WHERE id IN (SELECT pc.podcastId FROM podcast_categories pc " +
                        "JOIN categories c ON pc.category = c.name " +
                        "WHERE c.refreshHours BETWEEN 1 AND 2 " +
                        "AND c.anchorMinutes < 0)"
                )
            }
        }

        // Per-feed episode-list memory (filter chip, sort mode) plus
        // favoriting and the raw local-folder filename (kept separately
        // from the derived, extension-stripped title).
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE podcasts ADD COLUMN lastEpisodeFilter " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE podcasts ADD COLUMN episodeSortMode " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE episodes ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE episodes ADD COLUMN sourceFileName TEXT")
            }
        }

        // One-off saved episodes: a show can now exist WITHOUT being
        // subscribed, purely to hold an episode saved from Discover.
        // Everything that already exists is a real subscription.
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE podcasts ADD COLUMN subscribed " +
                        "INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE categories ADD COLUMN keepDownloads " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE categories ADD COLUMN maxAgeDays " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Review wave 5: indexes for the library-wide inbox/download queries,
        // and per-feed HTTP validators for conditional (304) refreshes.
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN feedEtag TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN feedLastModified TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_episodes_pubDateMs ON episodes (pubDateMs)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_episodes_downloadStatus " +
                        "ON episodes (downloadStatus)"
                )
            }
        }

        // Review wave 5 features: full-text search, Podcasting 2.0 extras,
        // bookmarks, per-day listening (yearly recap). SQL copied from the
        // exported 24.json so Room's validation matches exactly. The FTS
        // index is filled in the background afterwards (rebuildSearchIndex),
        // not here — on a 100k-episode library that would stall the first
        // launch after the update.
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN fundingUrl TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN fundingLabel TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN season INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN episodeNumber INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN episodeType TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN persons TEXT")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `episodes_fts` USING FTS4(" +
                        "`title` TEXT NOT NULL, `description` TEXT NOT NULL, content=`episodes`)"
                )
                createFtsTriggers(db)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY " +
                        "AUTOINCREMENT NOT NULL, `episodeId` INTEGER NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, `note` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmarks_episodeId` " +
                        "ON `bookmarks` (`episodeId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listen_daily` (`day` INTEGER NOT NULL, " +
                        "`podcastId` INTEGER NOT NULL, `wallMs` INTEGER NOT NULL, " +
                        "`contentMs` INTEGER NOT NULL, PRIMARY KEY(`day`, `podcastId`))"
                )
                // the parser now reads more (content:encoded, seasons, P2.0
                // tags): force one full fetch per feed so existing rows
                // pick it up instead of 304-ing forever
                db.execSQL("UPDATE podcasts SET feedEtag = NULL, feedLastModified = NULL")
            }
        }

        /**
         * FTS sync triggers scoped to the INDEXED columns. Room's generated
         * ones fire on every UPDATE of episodes — and a position save hits
         * the playing episode every few seconds, which re-tokenized its
         * whole show notes each time. Same trigger names, so Room still
         * finds what it expects; recreated on every open (see [Callback]).
         */
        internal fun createFtsTriggers(db: SupportSQLiteDatabase) {
            val prefix = "room_fts_content_sync_episodes_fts_"
            db.execSQL("DROP TRIGGER IF EXISTS ${prefix}BEFORE_UPDATE")
            db.execSQL("DROP TRIGGER IF EXISTS ${prefix}AFTER_UPDATE")
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS ${prefix}BEFORE_UPDATE BEFORE UPDATE OF " +
                    "`title`, `description` ON `episodes` BEGIN DELETE FROM `episodes_fts` " +
                    "WHERE `docid`=OLD.`rowid`; END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS ${prefix}BEFORE_DELETE BEFORE DELETE ON " +
                    "`episodes` BEGIN DELETE FROM `episodes_fts` WHERE `docid`=OLD.`rowid`; END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS ${prefix}AFTER_UPDATE AFTER UPDATE OF " +
                    "`title`, `description` ON `episodes` BEGIN INSERT INTO `episodes_fts`" +
                    "(`docid`, `title`, `description`) VALUES (NEW.`rowid`, NEW.`title`, " +
                    "NEW.`description`); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS ${prefix}AFTER_INSERT AFTER INSERT ON " +
                    "`episodes` BEGIN INSERT INTO `episodes_fts`(`docid`, `title`, " +
                    "`description`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`description`); END"
            )
        }

        /** Swaps Room's unscoped FTS triggers for the scoped ones on every open. */
        private object Callback : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                createFtsTriggers(db)
            }
        }

        /** Every real migration, oldest first — shared with the migration tests. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
            MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
            MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
            MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
            MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24
        )

        fun get(context: Context): StepcastDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StepcastDatabase::class.java,
                    "stepcast.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .addCallback(Callback)
                    // Destructive fallback ONLY in debug builds. In release,
                    // a missing migration or schema-hash mismatch must crash
                    // (fixable with an update) — never silently delete the
                    // user's subscriptions, positions, and download records.
                    .apply {
                        if (com.stepcast.app.BuildConfig.DEBUG) {
                            fallbackToDestructiveMigration()
                        }
                    }
                    .build().also { instance = it }
            }
    }
}
