# Stepcast — Play Store readiness

Status, July 2026: **the pipeline is wired.** The repo carries an
upload-key signing config (env-driven, never committed), a manual CI lane
that produces the Play AAB, and the privacy policy page. What remains is
the one-time key/secret setup below (steps you must do — the key can't be
generated for you) and the Play Console work itself.

## What's already in place (no action needed)

- **`app/build.gradle.kts`** — a `play` signing config materializes only
  when `STEPCAST_UPLOAD_KEYSTORE` (+ password env vars) is present; every
  ordinary build falls back to the committed shared debug key, so the
  daily-driver rolling release is unaffected. `versionCode`/`versionName`
  are overridable per-build via `-PstepcastVersionCode` /
  `-PstepcastVersionName`.
- **`.github/workflows/stepcast-play-release.yml`** — dispatch-only lane:
  checks secrets, decodes the keystore, runs unit tests, builds
  `bundleRelease` + `assembleRelease` signed with the upload key, uploads
  both as **private workflow artifacts** (14-day retention; deliberately
  NOT on the public rolling release), deletes the keystore from the runner.
- **`docs/stepcast-privacy.md`** — the privacy policy, ready for GitHub
  Pages.
- **`.gitignore`** — `stepcast-upload.keystore`, `*.jks`, `*.aab` can
  never be committed by accident.

## One-time setup (user actions, in order)

### 1. Generate the upload key — on your own machine, never in the repo

Termux (Android — `pkg install openjdk-17` provides keytool):

```bash
keytool -genkeypair -v -keystore stepcast-upload.keystore \
  -alias stepcast-upload -keyalg RSA -keysize 4096 -validity 10000
```

Windows (Corretto 21's keytool is on the dev machine):

```powershell
& "C:\Program Files\Amazon Corretto\jdk21.0.11_10\bin\keytool.exe" `
  -genkeypair -v -keystore stepcast-upload.keystore `
  -alias stepcast-upload -keyalg RSA -keysize 4096 -validity 10000
```

Pick a strong store password (reuse it for the key password when prompted
— one less secret to manage). Store the keystore file AND the password in
your password manager. This is the **upload key**; with Play App Signing
(step 4) Google holds the actual app signing key, so a lost upload key is
recoverable through Play support — but treat it as precious anyway.

### 2. Add the four GitHub secrets

Repo → Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
|---|---|
| `PLAY_UPLOAD_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `PLAY_UPLOAD_STORE_PASSWORD` | the store password |
| `PLAY_UPLOAD_KEY_ALIAS` | `stepcast-upload` |
| `PLAY_UPLOAD_KEY_PASSWORD` | the key password (same as store if you reused it) |

To get the base64 — **encode with no line wrapping** and copy the whole
string (a truncated paste is the usual cause of the CI signing step
failing with a `KeytoolException`/`EOFException`).

Termux (Android):

```bash
# -w0 = single unwrapped line; copies to the Android clipboard
base64 -w0 stepcast-upload.keystore | termux-clipboard-set
# or write it to a file to paste from: base64 -w0 stepcast-upload.keystore > ks.b64
```

Verify the keystore is valid before you upload the secret (should list the
`stepcast-upload` entry after you enter the store password):

```bash
keytool -list -keystore stepcast-upload.keystore
```

Windows (copies straight to clipboard):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("stepcast-upload.keystore")) | Set-Clipboard
```

### 3. Enable GitHub Pages for the privacy policy

Repo → Settings → Pages → Source: **Deploy from a branch** → Branch:
`main`, folder `/docs`. The policy then lives at:

`https://stepcastapp.github.io/podcastapp/stepcast-privacy`

(Contact email in the policy is currently stepcastapp@gmail.com — it will
be public; swap it in `docs/stepcast-privacy.md` if you'd rather use an
alias.)

### 4. Play Console

1. Create the app (podcast player → app id `com.stepcast.app`).
2. Accept **Play App Signing** (default for new apps) — Google generates
   the app signing key; your keystore from step 1 is the upload key.
3. Paste the privacy policy URL from step 3.
4. Data Safety form: collects/shares data → **No** (see "Data Safety
   answers" below).
5. **Foreground service declaration** (App content → Foreground service
   permissions). Required because targetSdk ≥ 34 and the manifest declares
   two FGS types. Answer per type, and attach a short screen recording
   (a phone recording uploaded as an unlisted YouTube link is fine):
   - `mediaPlayback` → "Media playback": podcast audio keeps playing
     with the screen off / app backgrounded. Video: start an episode,
     lock the phone, show the lock-screen media card while it plays.
   - `dataSync` → "Network transfer / downloads": user-requested and
     rule-driven episode downloads run as a visible, cancellable progress
     notification (WorkManager's foreground service). Video: tap Download
     on an episode, pull down the shade to show the progress notification.
6. Content rating questionnaire: podcast player; the UGC questions —
   plays user-selected third-party feeds, no user-to-user content, no
   moderation needed → normally passes for players. Target audience 13+
   or 18+ (never "designed for children").

## Cutting a Play build

Actions → **Stepcast Play Release** → Run workflow → enter only
`versionName` (e.g. `0.3.0`). **versionCode is derived automatically** from
the commit count (`git rev-list --count HEAD`), so it's always unique and
increasing — no manual tracking. It's printed as a run notice and shown in
the artifact name. Play only requires versionCode to increase, not be
contiguous, so occasional jumps are fine. Download the
`stepcast-play-aab-*` artifact and upload the `.aab` in Play Console. The
matching `stepcast-play-apk-*` artifact is signed with the same key for an
on-device sanity install first.

**Sideload → Play migration on your own phone:** the Play build is signed
with a different key than the daily-driver build, so Android refuses the
update path. Before switching: Settings → Backup now, uninstall, install
the Play (or upload-key) build, restore. Since backup format v3 the file
carries subscriptions, SmartPlays, settings AND listening state: played
flags, positions, favorites, History dates, Up Next order, saved one-off
episodes and listening stats. The state is staged and applied as each
feed's first refresh inserts its episodes (a few minutes on a big
library). Only downloaded audio has to re-download. **Make the backup
with a build that has format v3** — an older backup file carries no
listening state.

Automation: fresh installs start with "Allow control from other apps"
OFF (Settings → Feeds & downloads). The restore brings the setting back,
but re-check it if Tasker stops working.

## Data Safety answers (reference)

- Does your app collect or share any of the required user data types? **No**
- Encrypted in transit? N/A (nothing collected)
- Deletion request mechanism? N/A
- The iTunes API calls and feed fetches are ordinary web requests; Play's
  definition of collection (transmitted off device AND used beyond the
  request) is not met.

## Listing content still to produce

- Screenshots (phone; ideally widgets + Android Auto too)
- 512px icon + feature graphic (1024×500)
- Short + full description

## Technical notes

- targetSdk 36 (Android 16) satisfies the 2026 Play target requirement.
- `usesCleartextTraffic="true"` is deliberate (plain-HTTP feeds exist) —
  justify in review notes if asked.
- The pre-launch report on the internal/closed track exercises ~10
  devices; fix what it flags before production.

## Rollout path

1. Internal testing track (just you) with the upload-key AAB
2. Closed testing — Play requires 12+ testers over 14 days for NEW
   personal dev accounts before production; check whether your account
   predates the rule
3. Production
