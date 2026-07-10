package com.nsavage.stepcast.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/**
 * DataStore-backed persistence for the three preference singletons
 * (AppSettings, ThemePrefs, ListenStats). Each migrates its old
 * SharedPreferences file on first open, so nothing is lost.
 *
 * The in-memory Compose state in those singletons remains the source of
 * truth after init; the stores are pure persistence. Writes run on a
 * single-threaded scope so rapid toggles of the same key land in order.
 *
 * NOT migrated on purpose: the "stepcast_widget" prefs — that's a cache
 * written by the playback service and read synchronously by Glance
 * widgets and the resumption path, where SharedPreferences' synchronous
 * reads are exactly the right tool.
 */
val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "settings"))
    }
)

val Context.statsStore: DataStore<Preferences> by preferencesDataStore(
    name = "listen_stats",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "listen_stats"))
    }
)

@OptIn(ExperimentalCoroutinesApi::class)
val prefsWriteScope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
)
