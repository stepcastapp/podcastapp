package com.stepcast.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only diagnostics, shareable from the hidden diagnostics dialog in
 * Settings. Two INDEPENDENT channels, because the questions they answer
 * live on completely different timescales:
 *
 *  - [log] — playback: every write that can move or erase a position
 *    (position saves, played marks, rekeys/prunes, timeline swaps). "My
 *    episode started over" is unreproducible by definition; this turns it
 *    into "which writer zeroed it, and when". Position ticks land every
 *    few seconds while playing, so this channel churns fast — roughly a
 *    day or two of real use.
 *
 *  - [logSchedule] — refresh/scheduling: when feeds were checked, what
 *    came back, and when the next wake-up was promised. "Updates behave
 *    strangely" is usually an OVERNIGHT story, and if these lines shared
 *    the playback channel the tick stream would evict them long before
 *    anyone got around to sharing the file. A separate, much quieter file
 *    keeps weeks of it.
 */
object PlaybackJournal {

    private const val FILE = "position-journal.txt"
    private const val ROTATED = "position-journal.1.txt"
    private const val MAX_BYTES = 192L * 1024

    private const val SCHEDULE_FILE = "schedule-journal.txt"
    private const val SCHEDULE_ROTATED = "schedule-journal.1.txt"
    private const val SCHEDULE_MAX_BYTES = 96L * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var filesDir: File? = null

    fun init(context: Context) {
        filesDir = context.filesDir
    }

    /** Never throws, never blocks the caller; a lost line beats a crash. */
    fun log(tag: String, detail: String) =
        append(FILE, ROTATED, MAX_BYTES, tag, detail)

    /** Refresh/scheduling channel — see the class note on why it's separate. */
    fun logSchedule(tag: String, detail: String) =
        append(SCHEDULE_FILE, SCHEDULE_ROTATED, SCHEDULE_MAX_BYTES, tag, detail)

    private fun append(
        name: String,
        rotatedName: String,
        maxBytes: Long,
        tag: String,
        detail: String
    ) {
        val dir = filesDir ?: return
        val stampMs = System.currentTimeMillis()
        scope.launch {
            runCatching {
                synchronized(this@PlaybackJournal) {
                    val file = File(dir, name)
                    if (file.length() > maxBytes) {
                        file.renameTo(File(dir, rotatedName))
                    }
                    val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                        .format(Date(stampMs))
                    File(dir, name).appendText("$stamp $tag $detail\n")
                }
            }
        }
    }

    /**
     * Both channels, oldest first, each capped for a share intent. Schedule
     * goes FIRST and is never truncated away by playback volume — it is the
     * smaller of the two and usually the one being asked about.
     */
    fun snapshot(): String {
        val dir = filesDir ?: return ""
        return runCatching {
            synchronized(this) {
                val schedule = (read(dir, SCHEDULE_ROTATED) + read(dir, SCHEDULE_FILE))
                    .takeLast(120_000)
                val playback = (read(dir, ROTATED) + read(dir, FILE))
                    .takeLast(200_000)
                buildString {
                    append("===== refresh / schedule =====\n")
                    append(schedule.ifEmpty { "(nothing recorded yet)\n" })
                    append("\n===== playback =====\n")
                    append(playback.ifEmpty { "(nothing recorded yet)\n" })
                }
            }
        }.getOrDefault("")
    }

    private fun read(dir: File, name: String): String =
        File(dir, name).takeIf { it.exists() }?.readText().orEmpty()
}
