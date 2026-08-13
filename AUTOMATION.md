# Stepcast automation & intents

Stepcast's external surface for Tasker, Bixby Routines, MacroDroid, adb,
and anything else that can send an Android broadcast or intent.

## Broadcast commands

Send an **explicit broadcast** to the command receiver:

- **Package:** `com.stepcast.app`
- **Receiver:** `com.stepcast.app.playback.CommandReceiver` (exported)
- **Action prefix:** `com.stepcast.app.command.`

| Action | Effect | Extras |
|---|---|---|
| `…command.PLAY` | Start/resume playback | — |
| `…command.PAUSE` | Pause | — |
| `…command.TOGGLE` | Play if paused, pause if playing | — |
| `…command.NEXT` | Skip to the next queue item | — |
| `…command.PREVIOUS` | Back to the previous item | — |
| `…command.SEEK_BACK` | Seek back (configured increment) | — |
| `…command.SEEK_FORWARD` | Seek forward (configured increment) | — |
| `…command.DONE` | Mark played + delete download + advance | — |
| `…command.REFRESH` | Refresh all feeds now (silent) | — |
| `…command.REFRESH_CATEGORY` | Refresh one category's feeds now | `category` (string): the category name, case-insensitive. `name` also accepted. |
| `…command.START_SMART_PLAY` | Fill the queue from a SmartPlay's rules and start playing. If the SmartPlay is marked as a Station, it becomes the active station and keeps refilling the queue. | `smartplay` (string): the SmartPlay's name, case-insensitive. `name` also accepted. |

`PLAY` and `TOGGLE` start playback by dispatching a system media key
(`KEYCODE_MEDIA_PLAY`) rather than commanding the service directly — that
pipeline carries the foreground-service exemption, so playback genuinely
starts from a background broadcast on Android 12+ (and revives the last
session via playback resumption after process death). `START_SMART_PLAY`
fills the queue through the bound controller and then verifies playback
actually landed, falling back to the same system media-key pipeline if
the service didn't reach foreground in time — a plain broadcast carries
neither the foreground-service-start allowlist nor the background-
activity-start allowlist a widget tap or launcher shortcut gets, so
commanding the service (or launching a trampoline Activity) directly from
the receiver used to fill the queue and then get killed before playback
actually began. It returns an error for a SmartPlay name that doesn't
match anything: the queue and playback are left untouched.

### adb examples

```sh
adb shell am broadcast -a com.stepcast.app.command.TOGGLE \
    -n com.stepcast.app/.playback.CommandReceiver

adb shell am broadcast -a com.stepcast.app.command.START_SMART_PLAY \
    -n com.stepcast.app/.playback.CommandReceiver \
    --es smartplay "News"

adb shell am broadcast -a com.stepcast.app.command.REFRESH_CATEGORY \
    -n com.stepcast.app/.playback.CommandReceiver \
    --es category "News"
```

### Tasker / Bixby Routines

Use a "Send Intent / Broadcast" action with:

- Action: `com.stepcast.app.command.START_SMART_PLAY`
- Package: `com.stepcast.app`
- Class: `com.stepcast.app.playback.CommandReceiver`
- Target: Broadcast Receiver
- Extra: `smartplay: News` (only for START_SMART_PLAY)

Setting the package/class (an explicit broadcast) is required on modern
Android — implicit broadcasts to manifest receivers are not delivered.

## Feed URL intents

`MainActivity` accepts podcast links from other apps; each lands in
Discover with the URL prefilled:

- **Share (ACTION_SEND, text/plain):** share any text containing an
  http(s) feed URL to Stepcast — the first URL in the text is used.
- **Classic podcast schemes (ACTION_VIEW):** `pcast://…`, `podcast://…`,
  `itpc://…`, `feed://…` (all normalized to https), plus `pcast:` and
  `feed:` prefixed URLs.

## Home-screen surfaces

Not intents you send, but automation-adjacent:

- **SmartPlays widget** — lists your SmartPlays; one tap fills the queue
  and starts playback without opening the app.
- **Launcher shortcuts** — long-press the app icon for the first four
  SmartPlays; a tap starts playback without opening the app. (Internally
  these route `com.stepcast.app.shortcut.SMARTPLAY` through the invisible
  PlaybackTrampolineActivity — an implementation detail, not a stable
  external API; automate via the broadcast above instead.)
- **Player / bar / mini / transport / play-button widgets** — transport
  controls. Play taps ride the same system media-key pipeline as
  `PLAY`/`TOGGLE` above (not the notification's broadcast path); the
  other transport buttons drive a bound media controller, and SmartPlay
  taps go through the invisible PlaybackTrampolineActivity.
