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
 * Append-only journal of every write that can move or erase an episode's
 * playback position (position saves, played marks, row rekeys/prunes,
 * timeline swaps). A "my episode started over" report is unreproducible by
 * definition — the journal turns it into "which writer zeroed it, and when",
 * shareable from the hidden diagnostics dialog in Settings.
 *
 * Two files, ~192 KB each: the live journal rolls to .1 when full, so the
 * window covers roughly the last day of normal listening.
 */
object PlaybackJournal {

    private const val FILE = "position-journal.txt"
    private const val ROTATED = "position-journal.1.txt"
    private const val MAX_BYTES = 192L * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var filesDir: File? = null

    fun init(context: Context) {
        filesDir = context.filesDir
    }

    /** Never throws, never blocks the caller; a lost line beats a crash. */
    fun log(tag: String, detail: String) {
        val dir = filesDir ?: return
        val stampMs = System.currentTimeMillis()
        scope.launch {
            runCatching {
                synchronized(this@PlaybackJournal) {
                    val file = File(dir, FILE)
                    if (file.length() > MAX_BYTES) {
                        file.renameTo(File(dir, ROTATED))
                    }
                    val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                        .format(Date(stampMs))
                    File(dir, FILE).appendText("$stamp $tag $detail\n")
                }
            }
        }
    }

    /** Rotated + live journal, oldest first, capped for a share intent. */
    fun snapshot(): String {
        val dir = filesDir ?: return ""
        return runCatching {
            synchronized(this) {
                val rotated = File(dir, ROTATED)
                    .takeIf { it.exists() }?.readText().orEmpty()
                val live = File(dir, FILE)
                    .takeIf { it.exists() }?.readText().orEmpty()
                (rotated + live).takeLast(200_000)
            }
        }.getOrDefault("")
    }
}
