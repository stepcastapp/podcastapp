# Stepcast on-device verification checklist

CI proves compile + unit tests; this list is the runtime half. Run it against
`stepcast-release.apk` (the R8-minified build) — that's the one that can break
in ways debug builds don't. Anything that fails: note what you saw and where,
a screen recording is ideal.

## Core loop (every release)
- [ ] Subscribe to a show from Discover search; artwork + episodes appear
- [ ] Stream an episode; pill, notification, and lock screen all track it
- [ ] Download an episode; progress notification shows; playback uses the file
- [ ] Kill the app mid-episode (swipe from recents) → relaunch: paused pill
      offers the interrupted episode; play resumes at the right position
- [ ] Queue three episodes; auto-advance through them marks each played
- [ ] Reorder the queue several times in one visit — drops must stick
- [ ] Remove from queue → UNDO restores the exact slot

## R8-sensitive (first release-build install, or after dependency bumps)
- [ ] BeyondPod .bpbak import completes with plausible counts
- [ ] Stepcast backup → restore round-trips (feeds, categories, SmartPlays)
- [ ] Podcasting 2.0 JSON chapters load (chapter title appears in player)
- [ ] Trending charts load in Discover
- [ ] Crash-report share row works (Settings → Troubleshooting, if present)

## System surfaces
- [ ] Media resumption: reboot phone → QS media area offers Stepcast; play works
- [ ] Android Auto (or DHU): browse tree shows Up Next / SmartPlays /
      categories; starting an episode from the head unit works
- [ ] All five widgets render; buttons work after process death; the
      play/pause glyph flips ON THE TAP (not seconds later)
- [ ] Bar widget shrunk to 1 cell keeps its play button; Clear opacity =
      bare glyphs on the wallpaper (bar, play button, full player widget)
- [ ] Widget long-press → settings: per-widget opacity override sticks
- [ ] Notification: back / play-pause / forward (+ Done if enabled) all work
      with the app killed first; flipping the Done toggle updates the live
      notification immediately
- [ ] SmartPlays widget row tap AND the START_SMART_PLAY broadcast both
      fill the queue and START PLAYING

## Features with timing/sensors
- [ ] Sleep timer: set 1 min → volume fades near the end, then pauses
- [ ] While the timer runs, shake firmly → "+10 minutes" toast
- [ ] Weekly auto-backup: Settings → Library → "Back up to that folder now" →
      stepcast-auto-backup.json appears in the chosen folder
- [ ] Queue-end setting on: finish the last queued episode → same show's next
      unplayed episode starts

## Downloads & categories (added with the v0.2 rounds)
- [ ] On mobile data with Wi-Fi-only ON: Downloads screen shows "Waiting for
      Wi-Fi" and "Use mobile data (N)"; tapping it downloads over data
      without changing the setting
- [ ] Failed download: Retry and Dismiss per row; Dismiss-all; a dismissed
      episode does NOT reappear after the next refresh
- [ ] After 3 natural failures an episode shows "auto-retry stopped" and
      stops being re-added by refreshes; manual retry still works
- [ ] Assign a podcast to two categories: appears under both in Library,
      both category pages list its episodes, deleting one category keeps
      the other membership
- [ ] Category with "every Nh from H:MM": refresh lands within the hour
      after the anchor slot
- [ ] Player speed chip saves to the current show (check the show's
      settings afterward)
- [ ] Full player open: bottom nav still visible; nav tap collapses the
      player; back and swipe-down on the handle dismiss it

## Accessibility spot-check
- [ ] TalkBack: bottom-nav tabs announce Library / Up Next
- [ ] TalkBack on a queue row: custom actions "Move earlier/later in queue"

## Scale (318-feed library)
- [ ] Refresh-all completes without OOM; UI stays responsive during it
- [ ] Library scroll is smooth; podcast screens with huge feeds page correctly
- [ ] Library search returns shows + episodes quickly

## New episodes / stations / transcripts / preview
- [ ] Library shows a "New episodes" card when unplayed recent episodes
      exist; the count matches the inbox screen; Clear all empties it and
      UNDO restores it; playing an episode drops it from the inbox
- [ ] Discover: tapping a subscribed show (even one subscribed under a
      different feed URL, e.g. The Daily) shows "Open in Library", not
      Subscribe; a preview episode streams without subscribing and the
      pill/full player render it
- [ ] SmartPlay with Station ON: play it, let episodes complete — the queue
      refills from its rules without opening the app (test with the screen
      off / app swiped away); playing any episode manually ends the station
- [ ] Episode with a transcript (e.g. a Podcasting 2.0 feed): Transcript
      icon appears in the player's utility row; cues follow playback and
      tapping one seeks there
- [ ] Long-press the Settings footer: diagnostics dialog shows counts,
      stalest feeds, widget state
- [ ] Doze / anchored refresh: set a category anchor a few minutes ahead,
      `adb shell dumpsys deviceidle force-idle`, wait past the slot, exit
      idle — the missed slot catches up on the next worker run

## Review wave 5 (device checks — none of these could be verified off-device)
- [ ] Backup now → restore on a fresh install: shows come back, and after
      their first refresh played flags, positions, favorites, History,
      Up Next order and bookmarks are back; stats totals match
- [ ] New phone via Google backup/device transfer: empty Library shows
      "Restore library from your previous phone"; one tap restores it
- [ ] Notifications with "only around checkpoints": an episode found by
      an off-checkpoint check is announced at the next checkpoint; tapping
      the alert opens New episodes
- [ ] A big download on mobile data, toggle airplane mode mid-way, then
      back: it resumes from where it stopped (Downloads % continues)
- [ ] Settings → "Allow control from other apps" OFF: a Tasker/adb
      broadcast does nothing (journal: "automation refused"); ON: works.
      Widgets, shortcuts, the notification Done button work either way
- [ ] Queue drag-to-reorder (Reorderable library): rows follow the finger,
      auto-scroll at the edges, drop order sticks, bottom-up mode too;
      TalkBack move earlier/later still works
- [ ] Sleep timer "end of episode": the NEXT episode never starts audibly;
      the finished one shows as played
- [ ] Library search finds an episode by a word only in its show notes
- [ ] Player: bookmark button adds a bookmark with a note; tapping it seeks
- [ ] Settings → Stats → "Your year in listening" shows this year's data
- [ ] Continue listening row appears with half-listened episodes; tap resumes
- [ ] Cast: with a Chromecast on the network, the Cast button appears in
      the player; casting moves the episode to the TV at the same spot;
      disconnecting brings it back to the phone where the TV left off
- [ ] Android Auto: search box finds shows/episodes; "play <show>" by voice
      starts its next unplayed episode; Assistant on the phone too
- [ ] Volume boost 6 dB is audibly louder, no clipping
- [ ] Sync (Nextcloud gPodder Sync or gpodder.net): Sync now succeeds,
      subscriptions appear on the other client, progress from AntennaPod
      shows up here after a sync
- [ ] Tablet / unfolded / landscape ≥600dp: navigation rail on the left
- [ ] Download storage limit: set 1 GB, downloads beyond it remove played
      downloads first, then fail with a reason; SD-card option on a phone
      with a card
