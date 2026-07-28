# Full app review — findings tracker

Cover-to-cover review (July 2026): four subsystem deep-reads plus a
cross-cutting pass. Items are checked off as fixes land on main. Line
references are as of commit `ab06455` and drift as fixes land.

## Wave 1 — data safety

- [x] Destructive migration fallback can silently wipe the library
      (`StepcastDatabase`): debug-only fallback; release crashes instead
      of wiping.
- [x] Bulk "Set category" defaults to removing all categories, and the
      text field clobbers a chip selection (`HomeScreen` bulk dialog).
- [x] Shadow-dedup sweep: runs after insert (can delete a row whose id
      was just reported new → phantom queue/notification entries) and
      deletes legit same-title/same-timestamp episodes (needs audioUrl or
      duration corroboration).
- [x] IGNORE-inserts' -1 return used as a real id (subscribe /
      importPodcastStub / addLocalFolder) → orphan rows under
      podcastId = -1.
- [x] `refresh()` writes back a whole Podcast row read before the network
      fetch (lost update for every user setting) and can overwrite a good
      title with "(untitled feed)". Same pattern in scanLocalFolder /
      repointFeed.
- [x] Truncated download recorded as complete (no bytes-vs-Content-Length
      check) — then teaches the DB a wrong duration.

## Wave 2 — playback races

- [ ] Outro-skip acts on stale duration/position across a suspension, can
      seek the NEXT episode to the previous one's end; no once-per-episode
      guard (scrubbing back re-yanks); DB query every tick.
- [ ] Ad-chapter skip can run against the previous episode's chapters
      during the async episode-start window.
- [ ] `maybeContinueCurrentShow` and `onPlaybackResumption` bypass
      `resumeStartMs()`; resumption can return a played episode.
- [ ] Station refill: DB rows written one-by-one before the timeline
      append; UI queueSync interleaves → duplicated episodes.
- [ ] queueSync snapshots the current index before suspending Room reads;
      can remove the currently playing item / mis-tail.
- [ ] `sleepAtEpisodeEnd` never cleared when the sleeping episode is last
      → a future queue silently pauses at first auto-advance.
- [ ] `serviceScope` never cancelled; onDestroy persist is async (lossy);
      onTaskRemoved skips super and persists nothing.
- [ ] Streaming-off enforced only on the tapped head episode; queue tails,
      SmartPlay starts, station refill, auto-continue, Auto, resumption
      all stream on metered. One `playableUri()` policy.
- [ ] Widget play glyph sticks wrong when a play never starts; no
      reconciliation; service death leaves pause glyph.
- [ ] CommandReceiver: unbounded controller build inside a ~10s goAsync
      budget.
- [ ] cacheWidgetArt: unbounded URL read, no timeout, torn-file window,
      PNG+quality mismatch.

## Wave 3 — metadata, backup, feeds

- [ ] Episode rows frozen at first insert: titles/notes/art/duration/
      chapters never updated from the feed; rekey adopts identity but not
      metadata.
- [ ] Backup v2: schedule rules/checkpoints/quiet hours/station mode/
      ad-jump missing; restore resets settings absent from old files;
      restore half-merges existing shows and triggers cap pruning; dead
      refreshHours fields still round-tripped; optString null-title.
- [ ] RSS dates: no ISO-8601 → pubDate 0 (invisible in inbox, first
      pruned, instantly "old"); one unknown HTML entity kills the whole
      feed; item title/guid take LAST occurrence not first.
- [ ] Empty chapter fetch caches "" and destroys the json: URL pointer.
- [ ] Auto-download slots starved by dead/dismissed enclosures
      (take-before-filter).
- [ ] SmartPlay rules sort AFTER a newest-500 LIMIT (oldest-first returns
      the opposite of the ask on big scopes).
- [ ] Manual refresh has no network constraint → offline refresh marks
      the whole library as failing.
- [ ] Schedule edits never replan pending work (up to 6h stale).
- [ ] Stats: bump-then-insert race drops deltas; unsubscribe leaks
      listen_stats row (rowid reuse inherits listening time) and local_art
      files; fixed-tick wall time under-reports; checkbox-played doesn't
      count as finished.
- [ ] pruneBeyondCap deletes half-listened + played rows (history loss),
      no tiebreaker.
- [ ] searchByTitle: LIKE wildcards unescaped.
- [ ] Release inference: linear median on minutes-of-day breaks
      midnight-releasing shows (circular median).
- [ ] Transactions: setCategories / renameCategory / deleteCategory /
      unsubscribe / insertEpisodesReturningIds / reorder helpers commit
      row-by-row (torn states + N invalidations).
- [ ] markPlayedOlderThan clears OTHER shows' played episodes from Up
      Next (global removePlayed).
- [ ] autoQueue appends new episodes newest-first even for
      sortOldestFirst shows.
- [ ] Speed clamps disagree (per-show 0..4 vs global 0.5..3).
- [ ] deleteCategory leaves empty SmartPlays in the strip/widget/shortcuts.
- [ ] Inbox floor ignores subscription date (14 days of backlog lands in
      "New" on subscribe).

## Wave 4 — UX pass

- [ ] rememberSaveable: nothing survives rotation (search query/tab,
      multi-select, dialogs mid-edit, open full player).
- [ ] sharedFeedUrl never cleared: hijacks Search for the process
      lifetime; re-sharing the same URL no-ops.
- [ ] Search: IME Search key does nothing; duplicate/blank feedUrl keys
      can crash directory lists; trending failure indistinguishable from
      empty; FixFeedDialog keyboard/button slots.
- [ ] EpisodeRow menu says "Play next" while removing from queue;
      no go-to-podcast outside the queue; queue rows lack mark-played.
- [ ] Unsubscribe and "Mark all played": no confirm, no undo.
- [ ] DownloadsScreen: metered check frozen at first composition; 0%
      active downloads filed under Waiting.
- [ ] Speed: player list lacks 0.8×; per-show persist silently dropped
      while podcast still loading; ×/x glyph drift.
- [ ] Sleep timer dialog shows no armed state; Cancel in confirm slot.
- [ ] Done on last queued episode leaves finished episode in pill/
      notification/widgets.
- [ ] Android Auto: SmartPlays browse as folders instead of playing;
      browse ignores oldest-first and includes played.
- [ ] Widgets: SmartPlays list read outside composition (stale until
      launcher recycles); opacity keys never cleaned on delete (recycled
      ids inherit "invisible"); dead StartSmartPlayAction + stale comment;
      PLAYER widget clips Done when narrow.
- [ ] Shortcuts keyed on SmartPlay NAME (rename breaks pinned shortcuts);
      unknown SmartPlay reports SUCCESS to automation.
- [ ] Automation parity: PLAY/TOGGLE lack the FGS workaround widgets got;
      AUTOMATION.md/KDoc drift.
- [ ] Schedule screen: timeline effect not keyed on quiet times;
      checkpoint rows bypass quiet hours; bare clock times with no
      relative time; hand-typed H:MM fields.
- [ ] Shared date/duration formatters ("120 min" → "2h"); shared
      MediaItem builder; notification ad-jump missing pre-13; Done button
      on widget ignores the Settings toggle.
- [ ] Empty states: PodcastScreen (esp. Downloaded filter), preview
      episode list; show-more label lies under filters.
- [ ] Navigation: duplicate podcast destinations (launchSingleTop);
      preview category prompt outside-tap clears the back stack.
- [ ] Feedback: toasts vs snackbars vs never-clearing inline text vs
      silence; Settings inline results pinned forever.
- [ ] Auto-backup: first run ~7 days late, deletes old backup before
      writing new, no last-success surface.
- [ ] Misc: User-Agent 0.1; hardcoded English (PodcastScreen selection/
      retention labels, explainSmartPlayEntry, swipe labels, "1 shows"
      plurals); notification permission with empty callback; repository
      flows rebuilt per recomposition; diagnostics disk check on main
      thread.

## Noted, deliberately deferred

- Media3 swallowing FGS denial into a pause (upstream behavior; mitigated
  by media-key path).
- Local-folder rescan cost at checkpoints (accepted; revisit if battery
  complaints).
- Played manual downloads deleted by auto-manage (debatable semantics).
- SmartPlay download-first completion hook (play-when-ready).
- M3 TimePicker swap for H:MM fields (kept text fields + validation for
  now).
- exportSchema + migration tests (needs schema JSON generation in CI).
