package com.stepcast.app.download

import android.content.Context
import com.stepcast.app.data.AppSettings
import java.io.File

/**
 * Where downloaded audio lives, and the optional size limit.
 *
 * Volumes come from getExternalFilesDirs: index 0 is the primary
 * (internal) storage, later ones are removable (SD card). New downloads go
 * to the chosen volume; existing files stay where they are (their absolute
 * paths are in the DB), so switching is safe. An ejected card silently
 * falls back to the primary volume.
 */
object DownloadStorage {

    /** Every "episodes" folder on every mounted volume (orphan sweeps, usage). */
    fun allDirs(context: Context): List<File> =
        context.getExternalFilesDirs(null).filterNotNull().map { File(it, "episodes") }

    /** True when a removable volume (SD card) is mounted. */
    fun hasRemovable(context: Context): Boolean =
        context.getExternalFilesDirs(null).filterNotNull().size > 1

    /** Folder for NEW downloads. */
    fun dir(context: Context): File {
        val volumes = context.getExternalFilesDirs(null).filterNotNull()
        val chosen = if (AppSettings.downloadsOnSdCard) volumes.getOrNull(1) else null
        val base = chosen?.takeIf { it.canWrite() || it.mkdirs() }
            ?: volumes.firstOrNull()
            ?: context.filesDir // no external storage at all: still work
        return File(base, "episodes").apply { mkdirs() }
    }

    /** Bytes held by downloaded audio across all volumes (partials included). */
    fun usedBytes(context: Context): Long =
        allDirs(context).sumOf { d -> d.listFiles().orEmpty().sumOf { it.length() } }

    /** The user's cap in bytes; 0 = unlimited. */
    fun capBytes(): Long = AppSettings.downloadCapGb.toLong() * 1024 * 1024 * 1024
}
