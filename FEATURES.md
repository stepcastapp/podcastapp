# Stepcast — Feature Reference

The complete, organized map of what Stepcast does, one section per area of
the app. **This is a living document**: every shipped feature change adds,
amends, or removes an entry here in the same commit (or at latest the same
session) — see the maintenance note at the bottom.

Companion docs: [README.md](README.md) (stack, building, status),
[AUTOMATION.md](AUTOMATION.md) (broadcast commands + Tasker/adb recipes),
[TESTING.md](TESTING.md) (device checklist),
[ENGINEERING_NOTES.md](ENGINEERING_NOTES.md) (hard-won implementation
lessons), [PLAY_READINESS.md](PLAY_READINESS.md) (Play Store runbook).

---

## Navigation & app shell

- **Two-tab bottom navigation**: Library and Up Next. Child screens
  (Downloads, History, SmartPlay editor → Up Next; everything else →
  Library) keep their owning tab highlighted, so the bar always shows
  where you are.
- **Full-screen player as an in-scaffold overlay** — the bottom nav stays
  visible and tappable underneath; back, swipe-down anywhere on the player
  (a pull-down past the top of its content), the grab handle, or a nav tap
  dismisses it.
- **Mini player pill** above the nav bar: artwork, title, progress ring,
  back/play/forward/Done transport; tap or swipe up to expand. Survives
  force-close: the interrupted episode returns as a paused pill.
- Screen transitions animate (fade + slight vertical slide); tab switches
  preserve each tab's scroll position; re-tapping the current tab returns
  to its root.
- **First run**: an empty Library offers **Find shows** (→ Discover) and
  **Import from another app** (→ Settings) buttons.

## Library

- **Grid of artwork tiles** (or a compact-list layout, toggleable in
  Settings) grouped by category, with collapsible sections (chevron
  collapses; tapping the header opens the merged category view).
- **Categories** are real multi-membership: a podcast can live in any
  number of categories (multi-select chips in podcast settings) and
  appears under each; uncategorized shows gather under "Other podcasts".
  Categories can be renamed, deleted, reordered, and have per-category
  refresh cadence (see Feeds & refresh).
- **New episodes card** at the top whenever the inbox is non-empty (see
  Inbox).
- **Needs attention** — an automatic error-tinted section at the very
  bottom listing feeds whose refresh has failed 3+ times in a row (they
  also stay in their normal categories). Failing feeds carry a badge on
  their tile/row everywhere.
- Optional **per-category refresh buttons** on section headers (Settings
  toggle), with an in-flight spinner.
- Header icons: Discover, library search, refresh-all, Settings.
- **Multi-select** (long-press tiles) for bulk category assignment.
- **Library search**: shows by title and episodes by title, with full
  episode-row actions on results.

## Discover & subscribing

- **Discover** (compass icon on the Library header): Apple top-charts
  until you search; search by name or paste an RSS URL.
- **Preview before subscribing**: tapping any result (or a pasted URL)
  opens a preview — artwork, author, expandable description, the 30
  latest episodes — and every episode **streams right there without
  subscribing** (a sentinel media item plays through the normal service,
  pill and full player included).
- **Subscribe is an explicit button**; after subscribing, a prompt offers
  category placement (existing chips or a new name).
- **Already-subscribed detection** by normalized URL (scheme/slash/case
  insensitive) with a title fallback, so directory URLs that differ from
  your original subscription still show **Open in Library** instead of
  Subscribe. Subscribing to an equivalent URL refreshes rather than
  duplicating.
- Share-target and `podcast://`/`feed://` scheme intents land in Discover
  with the URL prefilled.
- **Local-folder virtual podcasts**: point Stepcast at a folder of audio
  files (Settings → Add local folder); it becomes a subscription whose
  refresh rescans the folder. Episode duration AND embedded artwork are
  read from each file's metadata (existing libraries backfill on the next
  rescan); the folder itself takes the first embedded art found, and a
  folder glyph stands in wherever a local feed has no art at all.

## New-episodes inbox

- Everything published in the **last 14 days**, unplayed and uncleared,
  across all subscriptions, newest first — the "what's new since I last
  looked?" surface, opened from the Library's count card.
- Full episode rows: swipe actions, queue, download, play, details.
- **Remove from New** per episode; **Clear all** with undo. Playing or
  marking an episode played clears it naturally. Local-folder files are
  excluded.

## Playback & player

- **Full player**: blurred-gradient backdrop, large artwork with a
  progress border, tappable title (show notes), show-name link (jumps to
  the podcast), chapter row, scrubber with drag preview, transport
  (previous / seek-back / play-pause / seek-forward / next) with haptics.
- **Utility row**: playback speed (per-show, see below), intro/outro
  skips, sleep timer, transcript (when available), share-at-timestamp.
- **Chapters**: Podlove Simple Chapters, Podcasting 2.0 JSON chapters, and
  synthetic chapters mined from timestamped tracklists in show notes;
  tick marks on the scrubber, current-chapter title, prev/next chapter,
  tappable chapter list, and **auto ad-chapter skip** (titles matching
  sponsor/ad/promo; each skipped once so you can scrub back on purpose).
- **Transcripts (Podcasting 2.0)**: WebVTT, SRT, Podcast Index JSON, and
  plain text. Follow-along highlight tracks playback; tapping a timed cue
  seeks there. Feeds that add transcripts later backfill on refresh.
- **Per-show playback speed**: the player's speed dialog saves to the
  current show (like the skips dialog); a global default speed lives in
  Settings and applies to shows without an override.
- **Intro/outro skips** per show: intro skip raises the start position on
  the next episode start; outro skip jumps to the end (completing the
  episode) when the tail is reached.
- **Ad jump** per show: a SECOND forward button sized to that show's
  mid-roll ad length (90 s here, 2 min there), separate from the global
  seek-forward. Configured in the skips dialog / podcast settings; appears
  as a "+90s" chip under the player transport and as an extra media-
  notification button, both only for shows that set one.
- **Trim silence** (ExoPlayer skipSilenceEnabled, Settings toggle).
- **Sleep timer**: minutes presets or end-of-episode, volume fade-out in
  the final stretch, **shake to add 10 minutes**, live countdown badge.
- **Resume logic**: positions persist continuously; a saved position in
  the last 15 s restarts from 0 instead of "instant-completing"; position
  restore is raised past the intro skip.
- **Done button** (pill, notification, queue strip): mark played + delete
  download + advance.
- Configurable seek-back/seek-forward increments (Settings).

## Up Next (queue) & SmartPlays

- **Queue screen**: drag reorder with haptics and edge auto-scroll,
  remove-with-undo, tap to play, long-press menu (details, go to podcast,
  move to front/end, remove episodes before/after — removals undoable).
- **Clear queue** button with undo.
- **Bottom-up mode** ("Next episode at the bottom"): the whole queue
  anchors to the bottom edge, the now-playing strip sits in-list, and the
  SmartPlay strip follows to the bottom.
- Queue summary line: episode count + remaining time.
- The queue mirrors into the player timeline, so notification/Bluetooth
  next-prev work everywhere; edits apply live.
- Optional **keep playing when the queue ends** (continues the current
  show's next unplayed episode).
- **SmartPlays**: ordered rule lists (scope: all podcasts / a category /
  one show; per-rule count, sort, downloaded-only, include-played) that
  fill the queue and start playing. Per-rule live "N match now"
  diagnostics with plain-language explanations when a rule matches
  nothing. Reorderable strip; starting one over a non-empty queue offers
  **undo**.
- **Stations**: a SmartPlay flagged as a Station keeps refilling the queue
  from its rules whenever it runs low — service-side, so it works with
  the app process dead. Any manual play ends the station.
- Pinnable **launcher shortcuts** per SmartPlay, and a home-screen
  SmartPlays widget.

## Downloads & storage

- **Auto-download** the newest N unplayed per show (global default +
  per-show override), auto-delete played downloads, max-age pruning,
  per-show episode caps.
- **Downloads screen** (from the Up Next header, with a live count badge):
  Downloading (progress bars), Waiting (honest reasons — "Waiting for
  Wi-Fi" vs "Waiting for a download slot"), Failed (attempt counts).
- **Wi-Fi-only gate** with a one-shot **"Use mobile data (N)"** override
  that leaves the global setting alone.
- Per-row and bulk retry / dismiss / cancel; **auto-retry gives up after
  3 failed attempts** so dead enclosures stop reappearing; manual retry
  always allowed; orphaned in-flight downloads recover on app start.
- **Stream-when-not-downloaded** toggle: off = tapping an undownloaded
  episode downloads instead of streaming.
- **Storage dashboard** (Settings): downloaded footprint per show,
  one-tap per-show cleanup.

## Feed health & maintenance

- **Dead-feed detector**: consecutive refresh failures tracked per feed;
  3+ shows a badge everywhere, a warning line on the podcast screen, and
  membership in the Library's **Needs attention** section.
- **Replacement-feed repair**: from a failing show's screen, "Find a
  replacement feed…" opens a directory search prefilled with the show's
  title (editable). Results show author + feed host; the current dead URL
  is marked and disabled. Picking one **repoints the subscription** —
  validated by fetching the new feed first, episodes/history/downloads
  kept, new episodes merged by guid, failure count reset — with a guard
  against URLs another subscription already uses.
- **Last refreshed** relative timestamp on every podcast screen.
- **Hidden diagnostics**: long-press the Settings footer for counts,
  stalest feeds, widget state, active station, crash-file presence.
- **Crash capture** with a share-report row in Settings.

## Feeds & refresh

- Background refresh via WorkManager (hourly worker), **per-category
  cadence** ("every N hours") with an optional **reference time-of-day
  anchor** ("every 6h from 5:00" → 5:00, 11:00, 17:00, 23:00); missed
  slots catch up. Multi-category shows refresh when ANY membership is due.
- Refresh-all button (Library) and per-category/per-show refresh with
  new-episode counts in snackbars; per-show refresh also runs the
  download rules.
- **New-episode notifications** (toggle) from background refresh.
- Per-show: episode list cap, oldest-first (serials), auto-queue new
  episodes.
- Bounded refresh/download concurrency, sized for 300+ feed libraries;
  episode lists page for huge feeds.

## Episodes (rows & details)

- Episode rows everywhere share one component: artwork with played/
  progress border, played/downloading/downloaded/failed markers, date +
  duration, play-next/queued toggle, overflow menu (details, play next,
  add to queue, mark played/unplayed, download / retry / cancel / delete).
- **Swipe actions** on rows, configurable per direction (mark played /
  queue / download / done), with undo snackbars.
- **Episode details dialog**: metadata line, selectable show notes
  (bare-newline tracklists render line-per-entry), Play + Close.
- Multi-select on podcast screens for bulk queue/download.
- "Mark older than…" bulk played (per show and per category), mark-all,
  per-category retention bulk-apply.

## Widgets (Glance)

Five home-screen widgets, all with live playback state and a per-widget
opacity/background setting (including fully transparent):
- **Player** — artwork, title, full transport.
- **Bar** — slim strip; size-responsive (collapses to button-only when
  narrow).
- **Mini** — artwork tile with play/pause.
- **Play button** — 1x1 play/pause only.
- **SmartPlays** — one-tap SmartPlay starters.

## Notifications, lock screen, Auto

- Media-style notification matching the pill: back / play-pause / forward
  + optional **Done** button (Settings toggle, applies instantly; One UI
  pins the 4th button far left — Samsung's layout).
- Lock-screen media card and QS media carousel; **playback resumption**
  from the carousel after process death.
- **Android Auto**: browse tree with Up Next, SmartPlays, categories, and
  all podcasts.
- Download progress notifications on their own low-importance channel.

## Automation & integrations

Broadcast commands under `com.stepcast.app.command.*` (full table +
Tasker/adb recipes in [AUTOMATION.md](AUTOMATION.md)):
PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, SEEK_BACK, SEEK_FORWARD, DONE,
REFRESH, REFRESH_CATEGORY, START_SMART_PLAY (actually starts playback;
a Station stays active and keeps refilling). Plus share-target and
podcast-scheme URL handling into Discover.

## Data: backup, restore, import, export

- **Stepcast JSON backup/restore**: subscriptions, categories (multi-
  membership), SmartPlays, settings; restore merges and kicks a refresh.
- **Weekly auto-backup** to a chosen folder (SAF) with "back up now".
- **OPML import** and **nested OPML export** (a feed appears under every
  category it belongs to).
- **BeyondPod .bpbak import**: feeds, categories, per-category refresh
  schedules, SmartPlays, episode caps.

## Stats & history

- **Listening stats** (Settings): total time listened, time saved by
  speed/silence-trimming, episodes finished, per-show "Most listened",
  resettable.
- **History screen** (from Up Next): finished episodes, replayable.

## Appearance

- Material 3 + dynamic color, light/dark/system theme, accent + support
  color pickers (presets or custom color wheel with auto-pairing).
- Stepcast identity: extra-bold titles with a signal-colored period, the
  three-bar "step mark" on section headers and empty states, one Rounded
  icon family.
- **Translation-ready**: every user-visible string lives in resources
  (plurals included); ships English-only.

## Under the hood (summary)

Kotlin, Compose/M3, Room (schema v16, real migrations from v9), media3
MediaSessionService, WorkManager, Glance, Coil, OkHttp; minSdk 26 /
targetSdk 35. Built exclusively by GitHub Actions (`stepcast-latest`
rolling release); JVM-pure unit tests for parsers/schedule logic. Details
in [README.md](README.md) and [ENGINEERING_NOTES.md](ENGINEERING_NOTES.md).

---

## Maintaining this document

This file is the canonical user-facing feature inventory. When a feature
ships, changes behavior, or is removed, update the matching section in the
same commit. Keep entries short (what it does, where it lives, any
non-obvious behavior); implementation lessons go to ENGINEERING_NOTES.md,
not here.
