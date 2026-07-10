# Stepcast

An original, modern Android podcast app: Kotlin, Compose/M3 + Material You,
Room, and Media3. Inspired by the feature set of classic podcast apps
(BeyondPod lineage), implemented entirely from scratch.

**Branding:** the app is named **Stepcast**, with a rising-stairsteps mark
(launcher icon + the in-app `StepMark` section glyph). Package/`applicationId`
are `com.stepcast.app`. The committed keystore predates the rename: its
*internal* alias/passwords are still the legacy `skipcast`/`skipcast123`
values (see `app/build.gradle.kts`) — do not "fix" them, they're baked into
the binary keystore and changing the references breaks signing.

## Stack

- Kotlin + Jetpack Compose (Material 3, dark mode, own "Ink & Signal" theme
  — neon-cyan brand — with accent presets incl. Hot Pink, a custom HSV
  color-wheel accent, and opt-in Material You dynamic color)
- Room (schema v16, real migrations from v9) for podcasts/episodes/queue/
  SmartPlays/multi-category memberships/listen stats; Preferences DataStore
  for settings/stats prefs (migrated from SharedPreferences)
- Media3 `MediaLibrarySession` service — pill-matched media notification,
  playback resumption, Android Auto browse tree, Bluetooth routing
- WorkManager: feed refresh, downloads (foreground notifications), weekly
  auto-backup
- Glance home-screen widgets (player / bar / mini / 1x1 play button /
  SmartPlays launcher; live state, per-widget + global opacity down to
  fully transparent glyph-only)
- OkHttp + hand-rolled RSS 2.0/iTunes parser; iTunes Search API + Apple
  top-charts for discovery; Coil for artwork
- minSdk 26, targetSdk 35, AGP 8.7 / Kotlin 2.0 — Play-ready toolchain

## Building

**GitHub Actions is the canonical build** (`.github/workflows/stepcast-build.yml`):
every push touching `stepcast/**` on `main` or `claude/stepcast-**` /
`claude/skipcast-**` branches builds `assembleDebug` + `assembleRelease`
(R8-minified), runs the unit tests, uploads `stepcast-debug-apk` /
`stepcast-release-apk` artifacts, and updates the rolling
**`stepcast-latest` GitHub release** with both APKs — the release page is the
plain-link download for the phone (`stepcast-release.apk` is the one to
install). This exists because the Claude environment's network policy blocks
`dl.google.com` (Google Maven + Android SDK), so nothing can compile locally
there; CI is the compile loop and Claude sessions read the logs through the
GitHub MCP tools.

Locally:

- **Android Studio (easiest):** open `stepcast/` as a project, let it sync,
  run on device. `./gradlew assembleRelease` / `bundleRelease` for artifacts.
- The wrapper JAR is not committed; use Android Studio's bundled Gradle or
  `gradle wrapper --gradle-version 8.14.3` once to regenerate it.

Signing: all builds (debug and release) sign with the committed
`stepcast-debug.keystore` (legacy internal alias `skipcast`, passwords
`skipcast123`) so every CI build installs update-over-update — ephemeral
runners would otherwise mint a new debug key per build and Android would
reject each new APK as a signature conflict. This key is a convenience key,
NOT for Play submission — generate a private one for that.

## Feature map

**The organized, browsable feature inventory lives in
[FEATURES.md](FEATURES.md)** — a living document updated with every
shipped change. The prose below is the compressed development-history
view; when the two disagree, FEATURES.md wins.

Playback & queue: Up Next queue mirrored into the player timeline
(auto-advance, notification/Bluetooth next-prev), drag reorder with haptics
(atomic persists, live-state handlers, TalkBack move actions), bottom-anchored
next-at-bottom mode with an in-list now-playing strip (SmartPlay strip
follows to the bottom), reorderable SmartPlay strip (starting one over a
non-empty queue offers UNDO), clear-queue button + long-press triage
(move to front/end, remove episodes before/after this — removals
undoable), remove-with-undo,
force-close recovery (interrupted episode returns as a paused pill + queue
card), optional keep-playing-current-show when the queue ends, position
restore raised past per-podcast intro skips, outro skip, per-podcast +
default speed (the player's speed dialog saves to the current show, like
the skips dialog), trim silence, chapter support (PSC + Podcasting 2.0 JSON,
plus synthetic chapters mined from timestamped show-notes tracklists) with
auto ad-chapter skip and a tappable chapter list, Podcasting 2.0
transcripts (VTT / SRT / Podcast Index JSON / plain; follow-along
highlight with tap-to-seek in the player), sleep timer (minutes or
end-of-episode) with volume fade-out and shake-to-add-10-minutes,
share-episode-at-timestamp.

Library & feeds: a New-episodes inbox (14-day cross-subscription triage
surface behind a count card on the Library; swipe/queue/download rows,
per-row Remove from New, undoable Clear all — playing or finishing an
episode clears it naturally), library-wide search (shows + episodes), categories
(collapsible via leading chevron, tap-to-open, reorderable, per-category
refresh cadence with an optional reference time-of-day anchor — "every 6h
from 5:00" — plus bulk retention/cleanup; a podcast can belong to ANY
number of categories via multi-select chips, appearing under each; optional
per-category refresh buttons and a compact-list library layout), per-feed
episode cap /
oldest-first / auto-queue-new, dead-feed detector badges, episode paging for
huge feeds, SmartPlays (rule-based playlists with per-rule match
diagnostics, plus a home-screen widget and pinnable launcher shortcuts;
a SmartPlay can be marked a **Station** — while it's the active station
the service refills the queue from its rules whenever it runs low, even
with the app process dead, and any manual play ends it),
swipe actions (configurable both directions) with undo, episode history
screen, local-folder virtual podcasts (with durations), OPML import +
nested export, Stepcast JSON backup/restore + weekly SAF auto-backup with
back-up-now, BeyondPod .bpbak import (feeds, categories, refresh schedules,
SmartPlays, episode caps), download management (dedicated Downloads
screen: Downloading/Waiting/Failed sections with progress bars and honest
wait reasons incl. "Waiting for Wi-Fi", one-shot "Use mobile data" override
that leaves the global Wi-Fi-only setting alone, per-row + bulk
retry/dismiss/cancel, auto-retry give-up after 3 failed attempts so dead
enclosures stop reappearing, force-stop orphan recovery).

Surfaces: two-tab bottom nav (Library / Up Next) that stays visible and
tappable under the full player (an in-scaffold overlay, not a modal sheet:
back or swipe-down dismisses, nav taps collapse it), full-screen player
with tap-title notes / show link / skips / share / hourglass sleep / speed
behind a 1x readout, mini-pill (tap or swipe up to expand), media
notification matching the pill (back/play/forward + optional Done — applies
instantly; One UI parks the 4th button far left, its layout not ours),
playback resumption from the QS/media carousel, Android Auto tree (Up Next
/ SmartPlays / categories / all podcasts), five Glance widgets (player,
bar with size-responsive button anchoring, mini, 1x1 play button,
SmartPlays launcher) with live state and per-widget opacity, Discover
(opened from the Library header) with Apple top charts + search +
share-target/podcast-scheme intents — a result tap (or pasted RSS URL)
opens a **preview screen** (artwork, expandable description, 30 latest
episodes, each streamable BEFORE subscribing via a sentinel-id media item;
Subscribe is an explicit button, already-subscribed shows offer Open in
Library — detected via normalized-URL match with a title fallback, since
directory URLs rarely equal the subscribed one — and the category prompt
follows subscribing), first-run Library shows Find shows / Import
buttons, child routes
highlight their owning bottom-nav tab (Downloads/History/SmartPlay editor
under Up Next, the rest under Library), pinnable SmartPlay
launcher shortcuts, external automation broadcasts
(`com.stepcast.app.command.*` incl. REFRESH_CATEGORY and a
START_SMART_PLAY that genuinely starts playback — full action list,
extras, and Tasker/adb recipes in [AUTOMATION.md](AUTOMATION.md)).

Care & feeding: listening stats (global + per-podcast "Most listened"),
storage dashboard with one-tap per-podcast cleanup, crash capture with a
share-report row, per-show relative last-refreshed line + failing-feed
badges with a Needs-attention auto-section at the bottom of the Library
and a replacement-feed repair flow (directory search prefilled with the
show's title, editable; picking a result repoints the subscription while
keeping episodes/history), a hidden diagnostics dialog (long-press the Settings footer:
counts, stalest feeds, widget state, active station), new-episode
notifications, Wi-Fi-only download gate,
bounded refresh/download concurrency for 300+ feed libraries.

## Status

**v0.2 (versionCode 2): v0.1 + the July 2026 review program (W1–W4b) + the
deferred-items program (DataStore migration, per-widget configuration,
strings.xml extraction) + the daily-driver feedback rounds (widget rebuild,
two-tab nav with the player overlay, multi-category, anchored schedules,
downloads overhaul) + the consistency program (translation-ready strings —
plurals resources, zero hardcoded UI literals; one Rounded icon family;
Snackbar in-app/Toast only where snackbars can't exist; folder→category
rename through the UI and repository API — the `Podcast.folder` DB column
deliberately keeps its name; Discover preview with play-before-subscribe)
+ the review-remainder program (New-episodes inbox, Stations, Podcasting
2.0 transcripts, last-refreshed visibility, hidden diagnostics, first-run
CTAs, queue clear/triage, SmartPlay-replace undo), all CI-green and in
daily use on the dev device.** Room is at schema v16 with real migrations
from v9.

First smoke test after an update: subscribe → play → queue → download →
kill app → resume from the media carousel; then a widget button tap, a
SmartPlay start from the widget, the Downloads screen on mobile data, and
an Android Auto browse check. Device checklist: [TESTING.md](TESTING.md).

The accumulated gotchas (Glance sessions, media3 threading and controller
races, One UI quirks, R8/aapt2 traps, the CI-blind build loop) live in
[ENGINEERING_NOTES.md](ENGINEERING_NOTES.md) — read it before touching
widgets, the media session, or the build.

Remaining open: Play readiness — private upload key, privacy policy,
Data Safety, closed-testing track ([PLAY_READINESS.md](PLAY_READINESS.md)).
