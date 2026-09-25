package com.stepcast.app.sync

import android.content.Context
import com.stepcast.app.data.PlaybackJournal
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.data.StepcastBackup
import java.io.File

/**
 * A full Stepcast backup kept in files/cloud_backup/, which the backup rules
 * hand to Google's Auto Backup / device-to-device transfer. The database
 * itself stays out of the cloud (size quota), so without this a new phone
 * came back with settings but an EMPTY library. With it, an empty Library
 * offers a one-tap restore.
 */
object CloudLibrarySnapshot {

    private const val MAX_AGE_MS = 24L * 3_600_000

    fun file(context: Context) = File(File(context.filesDir, "cloud_backup"), "library.json")

    fun exists(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }

    /** Re-writes the snapshot when it's more than a day old. */
    suspend fun refreshIfStale(context: Context, repository: PodcastRepository) {
        val f = file(context)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) return
        // an empty library must NEVER overwrite a snapshot: that's exactly
        // the freshly-restored phone, before the user taps Restore
        if (repository.subscribedPodcastList().isEmpty()) return
        runCatching { StepcastBackup.exportToFile(repository, f) }
            .onSuccess { PlaybackJournal.logSchedule("snapshot", "cloud library snapshot written") }
            .onFailure { PlaybackJournal.logSchedule("snapshot", "failed: ${it.message}") }
    }

    suspend fun restore(context: Context, repository: PodcastRepository): StepcastBackup.Summary =
        StepcastBackup.importFromFile(context, repository, file(context))
}
