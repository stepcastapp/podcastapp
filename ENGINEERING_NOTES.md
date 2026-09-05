# Stepcast engineering notes

Hard-won lessons from building Stepcast, kept so nobody (human or agent)
re-learns them the expensive way. Every entry here cost at least one broken
build or one confused on-device session.

## Scheduling

- **Intervals lie; promises don't.** "Refresh every N hours" is rolling
  from the last refresh, so actual check times drift daily and users
  can't reason about them. The schedule engine (ScheduleEngine +
  ReleasePattern, both pure and zone-parameterized) expresses everything
  as "next check at TIME because REASON" — checkpoints, expected
  releases, pinned slots. Keep it pure: CI is the only compiler, and the
  JVM tests on these two files are the only fast feedback loop.
- **The hourly periodic tick stays.** WorkManager one-shots are the
  precision tool (planned wake-up at the earliest next promise) but they
  can be deferred/dropped; the hourly tick is the safety net that
  guarantees eventual convergence. Never remove one for the other.
- **Scheduling has its own journal channel.** "Updates behave strangely"
  is an overnight story, and refresh events sharing the playback channel
  would be evicted by the 5-second position ticks long before anyone
  shared the file — so `PlaybackJournal.logSchedule` writes a separate,
  much quieter pair of files (96 KB x2, weeks of history) that
  `snapshot()` emits ahead of the playback section. What it records per
  run: trigger + due/total + quiet-hours/checkpoint config, one line per
  feed that ACTUALLY gained episodes (never per-feed "nothing new" — a
  300-feed library would bury the signal), one per failure with its
  message, the notify verdict, and the planned next wake-up with the
  podcast and `ScheduleEngine.Reason` that won it. The notify verdict
  re-checks POST_NOTIFICATIONS itself so it can never claim "posted"
  for an alert the OS dropped.

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
- **The keyguard blocks activities that broadcasts sailed past — and
  `showWhenLocked` does NOT fix it for widget taps.** Moving widget taps
  onto the trampoline silently broke lock-screen widgets: the One UI
  lock-screen host demands an unlock before firing an ACTIVITY
  PendingIntent at all, regardless of the target's
  `android:showWhenLocked` (tested on-device; the attribute is kept
  anyway, it's harmless). Only BROADCAST taps fire while locked. So
  play/pause is a broadcast callback again, with the FGS problem solved
  differently: pause via the bound controller (never needs FGS), play by
  injecting `KEYCODE_MEDIA_PLAY` through
  `AudioManager.dispatchMediaKeyEvent` — the system media-key pipeline
  (what Bluetooth buttons use) carries the FGS exemption and revives a
  dead session via playback resumption. Plain PLAY, never PLAY_PAUSE, so
  a misrouted key is a no-op in an already-playing app instead of
  pausing it. SmartPlay starts have no media-key equivalent and stay on
  the trampoline (they may prompt for unlock from the lock screen).
- **The media key routes globally — never make it the primary path.**
  The system delivers a dispatched media key to the MOST RECENT media
  session, which is ours only if nothing else has taken audio focus
  since. Two field reports came from this: a widget tap resuming another
  app's paused media, and (subtler) widget taps doing nothing at all
  ~13min after a `suppress transient-focus-loss` — the key went to
  whatever grabbed focus, so our `onPlaybackResumption` never ran and
  the journal showed `dispatch PLAY` with no matching `resume`.
  The key turned out to be unnecessary even for a DEAD session:
  Media3 routes a controller's `play()` on an empty timeline into
  `MediaLibrarySession.Callback.onPlaybackResumption` (see
  `MediaSessionImpl.handleMediaControllerPlayRequest` — the branch needs
  only an empty timeline plus `COMMAND_SET_MEDIA_ITEM`/
  `COMMAND_CHANGE_MEDIA_ITEMS`, NOT the media-notification controller).
  So `resumeStepcastPlayback` always plays through our own bound
  controller, and the global key is a fallback fired only if the
  targeted play produced no playback (covering an FGS refusal, which
  the key's system pipeline is exempt from). A cold play holds the
  controller 2s, not 300ms — resumption reads the episode and queue off
  disk first, and dropping the last binder mid-rebuild can tear the
  service down before it reaches play. Journal: "play via controller
  (cold, expect resume)" should be followed by a `resume` line.
- **Theme colours are meaningless once the panel goes transparent.**
  `GlanceTheme.colors.onSurface`/`primary`/`surfaceVariant` are chosen
  against a surface, so at low widget opacity they are being contrasted
  with something the user cannot see — a light-theme phone over a dark
  wallpaper renders near-black text on near-black pixels (field report).
  Below `FLOATING_BELOW` (40%) the widgets switch to fixed white content
  plus a dark scrim behind text; the scrim is the part that actually
  guarantees legibility, because Glance `TextStyle` has no shadow or
  outline and plain white would just fail the other way on a pale
  wallpaper. Same trap for `LinearProgressIndicator.backgroundColor`:
  an opaque `surfaceVariant` TRACK spans the full width, so on a clear
  widget it read as a big white slab louder than the episode title.
- **Tint every drawable you draw in a widget.** `ic_notif_*` are
  hardcoded `#FFFFFFFF`; the SmartPlays row drew one with no
  `colorFilter` at all, which was invisible on a light panel.
- **Responsive shrinking.** `SizeMode.Responsive` breakpoints keep the
  play/pause button alive as the bar widget shrinks (text drops <200dp,
  art drops <110dp); `minResizeWidth=40dp` allows 1-cell. Launchers cache
  a widget's resize bounds — remove/re-add after changing them.
- **`SizeMode.Responsive` breakpoints are a promise you have to keep
  yourself.** `LocalSize.current` inside the composition snaps to one of
  your *declared* sizes, not the raw on-screen size — but Glance doesn't
  stop your content from demanding more room than that declared size
  actually has. The PLAYER widget's transport row assumed ~44dp buttons
  at its 170dp/230dp breakpoints; `CircleIconButton` (used for the
  non-play buttons) has no size parameter of its own and defaults to a
  fixed 48dp, so the row's real content width (188dp / 246dp) exceeded
  the tier's own declared width and got clipped by the widget's actual
  bounds whenever the real cell size landed close to a breakpoint. Fix:
  size every button explicitly via `modifier = GlanceModifier.size(...)`
  (don't trust a component's built-in default), and compute that size
  from the row's real content budget (`width - padding - spacers, /
  buttonCount`) so it *autoscales* to whatever's actually shown instead
  of assuming a constant.
- **Per-widget config:** `GlanceAppWidgetManager.getAppWidgetId(glanceId)`
  + an `opacity_<appWidgetId>` pref; configure activity declared with
  `reconfigurable|configuration_optional`. `stringResource` does NOT work
  in Glance composables.
- Widget prefs (`stepcast_widget`) deliberately stay on SharedPreferences —
  they're a synchronous cache read by Glance/resumption paths.
- **Widget-picker previews need `android:previewLayout` (or the picker
  shows just the app icon).** Neither attribute was ever set on any of
  the `appwidget-provider` XMLs, so every widget fell back to an
  icon-only tile — not a per-widget bug, a gap across all of them.
  `previewLayout` points at a *plain Android View XML layout* (not
  Glance/Compose — `LinearLayout`/`ImageView`/`TextView`, see
  `res/layout/widget_preview_*.xml`), sized to roughly the widget's own
  `minWidth`/`minHeight` so the aspect ratio in the picker matches the
  real thing. It's API 31+ only; older devices still fall back to the
  icon unless `android:previewImage` (a static drawable/PNG) is also
  set — skipped here since it needs real generated art, not just XML.

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
- **A media-item transition can ERASE the incoming episode's bookmark.**
  `seekToNextMediaItem()` (Done/skip) and auto-advance flip
  `currentMediaItem` to the next episode *immediately*, while its position
  is still 0 — the seek to its saved resume point happens later, async, in
  `onEpisodeStarted`. The player goes briefly not-playing across that
  transition, firing `onIsPlayingChanged(false)` → `persistPosition("pause")`,
  which captured `(newEpisodeId, 0)` and wrote it over a real saved
  position (`savePosition` stores unconditionally). Journal proof: an
  episode last saved at 79m of 91m was zeroed by one
  `pos pause ep=… pos=0` the instant Done advanced onto it a week later,
  then started from the intro skip (`raise … from=14 to=10000`). Normally
  invisible because the next queue item is usually fresh, where 0 is
  correct. Guarded by `clobbersIncomingEpisode()`: refuse a zero write
  whose episode isn't the one `onEpisodeStarted` has committed to
  (`activeEpisodeId`, set *after* the seek decision). Any new persist path
  must go through it.
- **Everything that must be true before the first sample goes in ONE
  lookup.** `onEpisodeStarted` runs after the transition has already made
  the new item current, so every suspending round trip before a correction
  lands is audible with the OUTGOING episode's settings. The resume seek
  was already hoisted for this; per-show SPEED was not, and beta-reported
  as "the first second of the new show plays at the last show's speed".
  `episodeStartSettings()` now returns row + intro skip + speed together
  and all three apply at the top. Anything else that shapes the first
  second belongs in that call, not in the bookkeeping below it.
- **A denied audio-focus request is indistinguishable from a focus loss.**
  Both surface as `PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS`, so a
  timed SmartPlay that collides with an alarm/call/navigation fills the
  queue and silently never plays. Transient loss is different again — that
  one shows as `playbackSuppressionReason`, not a playWhenReady flip. The
  service retries a handful of times over ~30s
  (`SMARTPLAY_RETRY_DELAYS_MS`); observed recoveries were ~10s out. The
  retry MUST check the last playWhenReady=false reason — `isPlaying`
  alone can't tell "the system took the audio" from "the user pressed
  pause", and retrying over a deliberate pause is worse than the bug.

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
- **`detectDragGestures` throws the touch slop away.** It reports deltas
  only AFTER slop is exceeded and never hands the slop distance back, so a
  dragged item starts ~a slop behind the finger and stays there for the
  whole gesture ("doesn't follow your finger", beta report). Its slop phase
  also doesn't consume, so a LazyColumn underneath can win the gesture and
  scroll instead. For drag-to-reorder use
  `awaitEachGesture { awaitFirstDown(requireUnconsumed = false);
  awaitVerticalTouchSlopOrCancellation(id) { change, over -> … } ;
  verticalDrag(id) { … } }` — it yields `overSlop` to apply, and consuming
  from the slop callback onward keeps the list from stealing the drag.
- **`positionChange()` returns `Offset.Zero` once the change is consumed.**
  So in a hand-rolled `verticalDrag`/`drag` loop, READ the delta and THEN
  consume — `change.consume(); applyDrag(change.positionChange().y)` feeds
  every delta in as 0 and the drag looks completely dead (shipped exactly
  that; reorder stopped working outright). `detectDragGestures` hands the
  delta in as a lambda PARAMETER computed before consumption, which is why
  the same ordering is harmless there and the trap only appears when you
  drop down to the raw pointer API. `positionChangeIgnoreConsumed()` is
  the escape hatch if the order genuinely can't be helped.
- **Turn `animateItem` placement OFF for every row during a drag, not just
  the dragged one.** The dragged row moves by `translationY` (instant); a
  displaced neighbour on a placement spring does not, and while the finger
  keeps moving each new swap restarts that spring before the previous one
  settled. The neighbour ends up permanently mid-flight, and because the
  dragged row is above it on zIndex the pair renders as a single squashed
  double-row with the lower title and its ✕ clipped. Stiffening the spring
  was tried first and was not enough — the mismatch is instant-vs-animated,
  not fast-vs-slow. Instant reflow during the drag is less decorative but
  always correct.
- **Auto-scroll during a drag must compensate the drag offset.**
  `translationY` and `LazyListState` offsets are different coordinate
  spaces: `scrollBy` moves every item's layout offset, and a row held at
  `translationY = dragOffset` knows nothing about it, so the row slides
  away from a stationary finger by exactly the distance scrolled (screen
  recording: two full rows adrift). Worse, no swaps fire either — the
  finger isn't moving, so `dragOffset` never changes and no threshold is
  crossed. `scrollBy` RETURNS what it consumed, which is precisely the
  correction: feed it back through the same drag-apply path (not just into
  `dragOffset`) so the row both stays pinned and swaps its way along.
- **Reorder thresholds must measure the row being PASSED,** not the row
  being dragged, because the swap compensates the offset by the passed
  row's height — mixing those two drifts as soon as list rows differ in
  height (one- vs two-line titles). Keep the threshold at 0.55 of that
  height, not 0.5: a swap then leaves the offset at -0.45h, clear of the
  reverse threshold, so a jittery finger can't oscillate across it.
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
- **Feeds churn episode identity.** Dedup is keyed on `(podcastId, guid)`
  with the enclosure URL as guid fallback — and feeds rotate BOTH
  (tracking prefixes, ad-insertion tokens, CMS migrations). Insert-IGNORE
  alone then re-creates a half-listened episode as a fresh row with
  `positionMs = 0`, which users report as "my episode started over".
  Refresh rekeys orphaned rows to the matching parsed item (same URL, or
  same title + pubDate) and sweeps progress-less shadow duplicates.
- **Feed durations lie; the player's doesn't.** `itunes:duration` comes in
  wrong units and placeholder values, and the near-end resume guard
  divides by it. Position saves overwrite the stored duration with the
  player's real one (`correctDuration`), and `resumeStartMs()` only
  trusts "near end" when the position is actually NEAR the claimed end —
  a position far past it means the duration is bogus, so resume anyway.
  Every start path (service SmartPlay, UI play, auto-advance raise) goes
  through the one helper so they can't disagree again.

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
