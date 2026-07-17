# Stepcast engineering notes

Hard-won lessons from building Stepcast, kept so nobody (human or agent)
re-learns them the expensive way. Every entry here cost at least one broken
build or one confused on-device session.

## Glance widgets

- **Sessions freeze state.** Glance keeps a widget's composition session
  alive between updates: `update()` RECOMPOSES it, it does **not** re-run
  `provideGlance`. Anything read before `provideContent` is frozen for the
  session's lifetime — our play/pause glyph never changed on taps until the
  session timed out. Fix: `PreferencesGlanceStateDefinition` + read
  `currentState<Preferences>()` **inside** the composition; the publisher
  (`updateAllStepcastWidgets`) copies the shared prefs into every placed
  widget's Glance state before poking it.
- **ActionCallbacks run on a background dispatcher.** Every
  `MediaController` method throws off the main thread, so widget buttons
  silently did nothing on every build type. Wrap the command flow in
  `withContext(Dispatchers.Main)`.
- **R8 strips reflective callbacks.** Glance instantiates `ActionCallback`s
  via `Class.forName().newInstance()`; the optimize profile removes the
  never-referenced no-arg constructors, killing every button — release
  builds only, debug fine. Keep rule lives in `proguard-rules.pro`.
- **Surface widget errors.** A dead button is undebuggable from a home
  screen; failed widget commands now raise a Toast with the exception.
- **Launcher PendingIntents grant no FGS allowlist.** Notification actions
  come from SystemUI (privileged sender -> temporary allowlist); widget
  taps come from the LAUNCHER, a normal app, so a play issued from a
  Glance callback/broadcast cannot promote the service to foreground on
  Android 12+ while the app is backgrounded. Media3 swallows the denial
  and PAUSES - the tap looks dead and the primed queue pops off whenever
  the app next reaches foreground. Playback-starting widget buttons must
  route through `PlaybackTrampolineActivity` (activity starts from
  widgets are always allowed; while it's resumed the promotion succeeds).
  Pause/seek/done can stay on the broadcast path.
- **Responsive shrinking.** `SizeMode.Responsive` breakpoints keep the
  play/pause button alive as the bar widget shrinks (text drops <200dp,
  art drops <110dp); `minResizeWidth=40dp` allows 1-cell. Launchers cache
  a widget's resize bounds — remove/re-add after changing them.
- **Per-widget config:** `GlanceAppWidgetManager.getAppWidgetId(glanceId)`
  + an `opacity_<appWidgetId>` pref; configure activity declared with
  `reconfigurable|configuration_optional`. `stringResource` does NOT work
  in Glance composables.
- Widget prefs (`stepcast_widget`) deliberately stay on SharedPreferences —
  they're a synchronous cache read by Glance/resumption paths.

## media3 / playback

- **Controller thread affinity:** all `MediaController` calls must happen on
  the controller's application looper (main).
- **A released controller kills in-flight work.** START_SMART_PLAY once
  sent `setMediaItems` (bare ids) + `play()` through a throwaway controller
  released 300ms later; the session's per-episode id resolution was still
  running, so the queue filled but playback never started. Compound
  operations belong **service-side behind a custom `SessionCommand`**
  (`ACTION_START_SMARTPLAY`, same pattern as `ACTION_DONE_DELETE`): the
  receiver sends one command, the service resolves/queues/plays with its
  own player and nothing races.
- **Button preferences apply at controller connect.** The system media
  notification snapshots them per connection, so a settings change was
  inert until process death. We re-apply via
  `session.setMediaButtonPreferences(mediaNotificationController, …)` on
  every episode start AND on demand through `ACTION_REFRESH_NOTIF_BUTTONS`
  (the Settings toggle sends it so the shade updates instantly).
- **One UI pins the extra button far LEFT.** Samsung's system media
  controls honor the back/forward slots but ignore
  `SLOT_FORWARD_SECONDARY` (media3 1.8): the 4th button always renders
  far-left. Not fixable app-side; the Done button is a toggle instead.
- **onAddMediaItems** resolves bare mediaIds to playable URIs for
  controllers that only know episode ids (Android Auto, resumption).

## WorkManager / downloads

- **Never call `setForeground` per progress tick.** Each call dispatches a
  start command to WorkManager's shared `SystemForegroundService`; with
  parallel downloads finishing (stopping that service), a late command
  lands during teardown, `startForeground` never runs, and the OS kills
  the app (`ForegroundServiceDidNotStartInTimeException`). Promote ONCE at
  download start, then update progress via `NotificationManager.notify()`
  on the same id (WorkManager's completion cleanup still removes it).
- **`ExistingWorkPolicy.REPLACE`, not KEEP, for retries** — KEEP silently
  drops the retry against a stale/stuck work record (post force-stop).
- **Reconcile orphans at app start:** force-stops strand episodes in
  RUNNING with no live work; flip them to FAILED so Retry works.
- **Failure give-up:** `episodes.downloadAttempts` counts terminal
  failures; auto-download rules skip episodes at ≥3 so dead enclosures
  stop reappearing on every refresh. Success resets the count; manual
  retry always allowed; Dismiss pins the count past the cutoff.
- **One-shot metered override:** `DownloadWorker.start(allowMetered=true)`
  drops the UNMETERED constraint for that enqueue only — the Downloads
  screen's "Use mobile data (N)" button; the global setting is untouched.
- Download activity queries need generous LIMITs (2000): the old LIMIT 100
  hid everything past 100 during mass imports, failed rows first.

## Compose

- **Stale captures are the #1 bug class.** `pointerInput(key)` blocks and
  `rememberSwipeToDismissBoxState(confirmValueChange)` hold
  first-composition captures forever; anything they read must go through
  `rememberUpdatedState`. (Queue drags reverting seconds after drop, dead
  swipe handlers, pill callbacks.)
- **Extension functions can't be called fully-qualified** — `verticalScroll`,
  `detectVerticalDragGestures` each broke a CI build. Import them.
- `var x by remember { … }` needs BOTH `getValue`/`setValue` imports.
- **Swipe-to-dismiss over a scroll container needs nested scroll, not a
  drag detector.** `pointerInput { detectVerticalDragGestures … }` on (or
  around) a `verticalScroll` column loses the fight for vertical drags. A
  `NestedScrollConnection.onPostScroll` sees the delta the scroller could
  NOT consume — positive leftover `available.y` with `consumed.y == 0`
  means a pull-down at the top, i.e. a dismiss gesture. Accumulate past a
  threshold (72dp), reset on upward/consumed events and in `onPostFling`
  so short pulls don't add up across gestures. (Full player swipe-down.)
- **ModalBottomSheet is its own window** — it always covers the bottom nav.
  To keep nav visible under the full player, it became an in-scaffold
  overlay (AnimatedVisibility slide) with `BackHandler` + its own grab
  handle; nav taps collapse it; the mini pill hides while it's open.
- LazyColumn/LazyVerticalGrid keys must be unique: with multi-category, a
  podcast appears in several sections — namespace keys per section
  (`"c/$category/$id"`, `"u/$id"`).
- AlertDialogs that can hold hundreds of rows need a `LazyColumn` with
  `heightIn(max=…)`, not `verticalScroll` (downloads dialog, pre-screen).
- **When editing a named-argument call, check what comes AFTER your match.**
  Appending a `dismissButton =` "before confirmButton" to a dialog whose
  ORIGINAL parameter list already ended with one produced a duplicate named
  argument — and the parse break surfaced as a wall of misleading
  "@Composable invocations can only happen…" errors far from the real line.
  Read to the closing paren of the original call before inserting slots.
- **i18n mechanics:** `stringResource`/`pluralStringResource` are
  @Composable — fine inside *inline* stdlib lambdas (`buildList`,
  `buildString`, `let`) called from composables, but NOT inside
  `remember { }` (its lambda is `@DisallowComposableCalls` — hoist to a
  `val` above) and not in coroutine bodies (`snackbar.showSnackbar` blocks:
  hoist static strings to `val`s at composable level, or use
  `context.getString`/`resources.getQuantityString` for runtime-count
  messages). Deliberate leftover: `SmartPlayEntry.SORT_LABELS` is a
  data-layer map and still carries English labels.
- **Preview playback sentinel:** a non-library episode plays through the
  normal service via a MediaItem with mediaId `"-1"`
  (`PlayerConnection.PREVIEW_MEDIA_ID`). It must stay NUMERIC — the pill
  only renders when `mediaId.toLongOrNull()` parses — and every service/DB
  consumer already null-safes the missing row (`episode(-1)` → null,
  queue/position/chapters no-op). A non-numeric id would silently hide the
  pill instead.

- **Stations refill the PLAYER timeline service-side**, not just the queue
  table. The UI's queueSync (queue table → timeline tail) only runs while
  the app process is alive; a station playing from the lock screen would
  stop at timeline end if the service only appended DB rows. The service
  appends MediaItems directly and the UI sync no-ops on the matching tail.
  Refill triggers on episode start when ≤1 queued; filter against current +
  queued + timeline-tail ids or refills duplicate.
- **Feeds leak HTML entities into bare XML** (`&nbsp;` outside CDATA —
  Genix Podcast was the reproducer): XmlPullParser aborts with
  "unresolved: &nbsp;". Fix is `defineEntityReplacementText` for the
  common HTML set right after `setInput`; don't try to pre-sanitize the
  stream. **Two traps**: (1) `Xml.newPullParser()` enables
  FEATURE_PROCESS_DOCDECL, and KXmlParser throws IllegalStateException
  from `defineEntityReplacementText` in that mode — set the feature to
  false before `setInput` or nothing registers. (2) The first version
  wrapped registration in `runCatching`, which swallowed exactly that
  exception and shipped a no-op "fix" — never runCatching the setup a
  feature depends on; let it fail loudly.
- **Inbox is a dismissed-flag, not a table**: `episodes.inboxDismissed` +
  a windowed query (unplayed, 14 days, non-local). Playing/marking played
  clears entries for free; Clear-all undo is just flipping the flag back
  for the captured id list.

## Room / data

- **Schema history:** v9→10 `episodes.playedAtMs`; v10→11 per-feed
  cap/sort/auto-queue/failures + `listen_stats`; v11→12
  `smartplays.sortOrder`; v12→13 `categories.anchorMinutes`; v13→14
  `podcast_categories` junction (multi-category); v14→15
  `episodes.downloadAttempts`. All real migrations; destructive fallback
  only pre-v9.
- **Multi-category:** memberships live in the `podcast_categories`
  junction; `Podcast.folder` stays as a *synced* legacy value (first
  membership) so unconverted readers degrade gracefully. All the real
  readers (library grouping, category pages, episode queries, SmartPlay
  scopes, Auto tree, refresh cadence, retention bulk-apply, backup, OPML)
  go through the junction. `UPDATE OR REPLACE` merges renames when a
  podcast is already in both categories.
- **Paged lists lie about totals.** The podcast header showed "100
  episodes" because the list was paged; totals need their own COUNT query
  (`EpisodeDao.observeCounts`).
- Room DAO projections (`EpisodeCounts`) are plain data classes matching
  the SELECT aliases.
- **DataStore migration trap:** the all-keys convenience is
  `androidx.datastore.preferences.SharedPreferencesMigration` (a
  *function*); `androidx.datastore.migrations.SharedPreferencesMigration`
  is a generic class needing a migrate lambda and broke the build.
  Settings init does a deliberate `runBlocking { store.data.first() }`
  once at startup; writes go through a single-threaded scope for ordering.
- org.json `optString()` returns the literal `"null"` for JSON null —
  never use it for nullable fields (poisoned restored folders once;
  `stringOrNull` helper since).

## Resources / build / CI

- **Resource names must not be Java keywords.** `<string name="new">`
  makes aapt2 fail resource compilation before anything builds (the i18n
  extraction script generated it; diagnosed blind from a stuck release
  tag).
- aapt2 requires apostrophes escaped in string values even when the XML is
  well-formed; single `%` format specifiers are fine unpositioned.
- lintVitalRelease rejects `<exclude>` alongside `<include>` in backup
  rules — include-only, and DataStore files need
  `<include domain="file" path="datastore/" />`.
- **The CI-blind loop** (no local compile — dl.google.com is blocked):
  python brace-balance check over every touched file before pushing;
  green/red from `curl` of the `stepcast-latest` release page ("Current:
  <sha>"); failure details via GitHub MCP `get_job_logs`, grepping
  `e: file` for Kotlin errors. Concurrency cancels superseded runs.
- **Bulk-edit scripts must prove their replacements matched** — a silent
  no-op string replace shipped a half-wired feature once (commit showed
  "2 files changed" instead of 3, which was the only tell). Grep the
  result or count matches; don't trust "script ran".
- Java-keyword/aapt2/extension-function/import failures are all *cheap* to
  pre-check and *expensive* (10-minute CI round trip) to discover.

## Platform odds & ends

- Manifest receivers only get **explicit** broadcasts on modern Android —
  every Tasker/adb recipe needs the package/class, and the SmartPlay
  widget/shortcuts route through explicit `Intent(context, CommandReceiver)`.
- BroadcastReceivers must hand real work to WorkManager (REFRESH_CATEGORY)
  or the media session (START_SMARTPLAY) — nothing slow inside the
  broadcast window.
- Category schedules: anchored slots (`RefreshSchedule`, pure + unit
  tested): "every 6h from 5:00" = 5:00/11:00/17:00/23:00 local; a podcast
  in several categories is due when ANY fires; the hourly worker means
  anchored refreshes land within the hour after the slot.
- English pluralization: `ui/plural()` ("1 episode" / "3 episodes") —
  count labels go through it.
- 124 static UI strings extracted to `strings.xml` (Compose screens only;
  Glance excluded because `stringResource` is unavailable there).
