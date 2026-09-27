package com.stepcast.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real upgrades against the exported schema JSONs (app/schemas). Room
 * validates the migrated database against the target version's schema, so
 * a missing column/index or a wrong type fails here instead of crashing
 * the release build on launch (release has no destructive fallback).
 * Schemas exist from v22 on — earlier versions predate exportSchema.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StepcastDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate22To23KeepsDataAndMatchesSchema() {
        helper.createDatabase(DB, 22).apply {
            execSQL(
                "INSERT INTO podcasts (id, feedUrl, title, description, author, " +
                    "introSkipSec, outroSkipSec, adJumpSec, playbackSpeed, keepDownloads, " +
                    "maxAgeDays, episodeCap, sortOldestFirst, autoQueue, consecutiveFailures, " +
                    "subscribedAt, lastRefreshed, scheduleMode, scheduleParam, " +
                    "lastEpisodeFilter, episodeSortMode, subscribed) VALUES " +
                    "(1, 'https://example.com/feed', 'Show', '', '', 0, 0, 0, 0, 2, 0, 0, " +
                    "0, 0, 0, 0, 0, 0, 0, 0, 0, 1)"
            )
            execSQL(
                "INSERT INTO episodes (id, podcastId, guid, title, description, audioUrl, " +
                    "pubDateMs, durationMs, positionMs, played, playedAtMs, downloadStatus, " +
                    "downloadProgress, downloadAttempts, inboxDismissed, " +
                    "autoDownloadEligible, favorite) VALUES " +
                    "(7, 1, 'g1', 'Ep', '', 'https://example.com/1.mp3', 1000, 60000, " +
                    "12345, 0, 0, 0, 0, 0, 0, 1, 1)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(
            DB, 23, true, *StepcastDatabase.ALL_MIGRATIONS
        )
        db.query("SELECT positionMs, favorite FROM episodes WHERE id = 7").use {
            it.moveToFirst()
            assertEquals(12345L, it.getLong(0))
            assertEquals(1, it.getInt(1))
        }
        db.query("SELECT feedEtag FROM podcasts WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(true, it.isNull(0))
        }
    }

    @Test
    fun migrate23To24AddsSearchBookmarksAndRecapTables() {
        helper.createDatabase(DB, 23).apply {
            execSQL(
                "INSERT INTO podcasts (id, feedUrl, title, description, author, " +
                    "introSkipSec, outroSkipSec, adJumpSec, playbackSpeed, keepDownloads, " +
                    "maxAgeDays, episodeCap, sortOldestFirst, autoQueue, consecutiveFailures, " +
                    "subscribedAt, lastRefreshed, scheduleMode, scheduleParam, " +
                    "lastEpisodeFilter, episodeSortMode, subscribed, feedEtag) VALUES " +
                    "(1, 'https://example.com/feed', 'Show', '', '', 0, 0, 0, 0, 2, 0, 0, " +
                    "0, 0, 0, 0, 0, 0, 0, 0, 0, 1, '\"v1\"')"
            )
            execSQL(
                "INSERT INTO episodes (id, podcastId, guid, title, description, audioUrl, " +
                    "pubDateMs, durationMs, positionMs, played, playedAtMs, downloadStatus, " +
                    "downloadProgress, downloadAttempts, inboxDismissed, " +
                    "autoDownloadEligible, favorite) VALUES " +
                    "(7, 1, 'g1', 'Ep', 'talking about glaciers', 'https://example.com/1.mp3', " +
                    "1000, 60000, 0, 0, 0, 0, 0, 0, 0, 1, 0)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(
            DB, 24, true, *StepcastDatabase.ALL_MIGRATIONS
        )
        // validators cleared so every feed re-fetches through the new parser
        db.query("SELECT feedEtag FROM podcasts WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(true, it.isNull(0))
        }
        // the index starts empty; the background rebuild fills it
        db.execSQL("INSERT INTO episodes_fts(episodes_fts) VALUES('rebuild')")
        db.query("SELECT docid FROM episodes_fts WHERE episodes_fts MATCH 'glac*'").use {
            assertEquals(1, it.count)
        }
        // a position save must NOT touch the index (scoped triggers)…
        StepcastDatabase.createFtsTriggers(db)
        db.execSQL("UPDATE episodes SET positionMs = 5000 WHERE id = 7")
        // …while a show-notes change must
        db.execSQL("UPDATE episodes SET description = 'now about volcanoes' WHERE id = 7")
        db.query("SELECT docid FROM episodes_fts WHERE episodes_fts MATCH 'glac*'").use {
            assertEquals(0, it.count)
        }
        db.query("SELECT docid FROM episodes_fts WHERE episodes_fts MATCH 'volcano*'").use {
            assertEquals(1, it.count)
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
