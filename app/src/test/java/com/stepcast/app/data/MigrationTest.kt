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

    private companion object {
        const val DB = "migration-test.db"
    }
}
