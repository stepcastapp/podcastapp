package com.nsavage.stepcast

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.nsavage.stepcast.data.ItunesSearch
import com.nsavage.stepcast.data.PodcastRepository
import com.nsavage.stepcast.data.StepcastDatabase
import com.nsavage.stepcast.sync.RefreshWorker
import kotlinx.coroutines.launch

class StepcastApplication : Application(), coil.ImageLoaderFactory {

    // cap the artwork cache — with hundreds of podcasts the default (25%
    // of heap) crowds out refresh + download buffers
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this).maxSizePercent(0.15).build()
            }
            .crossfade(true)
            .build()


    lateinit var repository: PodcastRepository
        private set
    lateinit var search: ItunesSearch
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashCapture()
        com.nsavage.stepcast.data.AppSettings.init(this)
        com.nsavage.stepcast.data.ListenStats.init(this)
        com.nsavage.stepcast.ui.theme.ThemePrefs.init(this)
        val db = StepcastDatabase.get(this)
        repository = PodcastRepository(db, this)
        search = ItunesSearch()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { repository.repairLegacyNullStrings() }
            // force-stops can strand episodes in "downloading" with no
            // matching WorkManager job — flip those to FAILED so Retry works
            runCatching {
                com.nsavage.stepcast.download.DownloadWorker
                    .reconcileOrphans(this@StepcastApplication)
            }
        }
        RefreshWorker.schedulePeriodic(this)
        if (com.nsavage.stepcast.data.AppSettings.autoBackupFolder != null) {
            com.nsavage.stepcast.sync.AutoBackupWorker.schedule(this)
        }
        // launcher shortcuts + the SmartPlays widget track the SmartPlay list
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            repository.smartPlays.collect { plays ->
                SmartPlayShortcuts.publish(
                    this@StepcastApplication, plays.map { it.name }
                )
                runCatching {
                    com.nsavage.stepcast.widget.StepcastSmartPlaysWidget()
                        .updateAll(this@StepcastApplication)
                }
            }
        }
    }

    /**
     * Writes any uncaught exception's stack to files/last_crash.txt before
     * the process dies, so Settings can share it — a crash loop stops being
     * a black box.
     */
    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                java.io.File(filesDir, "last_crash.txt").writeText(
                    "Stepcast crash at ${java.util.Date()}\n" +
                        "thread: ${thread.name}\n\n" +
                        android.util.Log.getStackTraceString(throwable)
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
