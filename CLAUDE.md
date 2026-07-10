# Stepcast — Claude Project Guide

Stepcast is an original Android podcast app (Kotlin, Compose/M3 + Material
You, Room, Media3 MediaSessionService; minSdk 26 / targetSdk 35). It is the
owner's daily driver and is being prepared for Play Store release. The
Gradle project lives at the repo root.

## Docs — read before working

- **ENGINEERING_NOTES.md** — REQUIRED reading before touching widgets, the
  media session, downloads, Compose gesture/scroll code, or the build.
  Every entry cost a broken build or a confused device session.
- **FEATURES.md** — the LIVING user-facing feature inventory. Update the
  matching section in the same commit as every shipped feature change.
- **AUTOMATION.md** — broadcast commands + Tasker/adb recipes.
- **TESTING.md** — on-device test checklist.
- **PLAY_READINESS.md** — Play signing/publishing runbook.

## Building — CI is the compiler

Agent environments typically cannot reach dl.google.com/Google Maven, so
the app does not compile locally. The loop:

1. Edit; run a python brace-balance check over every touched file; verify
   every new R.string/R.plurals reference exists in strings.xml.
2. Push. Every push to main or claude/** runs
   `.github/workflows/stepcast-build.yml` (assembleDebug + unit tests + R8
   assembleRelease) and updates the rolling `stepcast-latest` release.
3. Green/red without auth:
   `curl -sS https://github.com/stepcastapp/podcastapp/releases/tag/stepcast-latest | grep -oE "Current: [0-9a-f]{7}"`
4. Failure details via the GitHub MCP actions tools (`actions_list`,
   `get_job_logs`; grep `e: file` for Kotlin errors). A CI round trip is
   ~9 minutes — pre-check everything cheap.

`stepcast-release.apk` on the rolling release is the sideload install.

## Signing

- **stepcast-debug.keystore** (committed): convenience key so CI builds
  install update-over-update. Its internal alias/passwords are legacy
  `skipcast`/`skipcast123` — baked into the binary, do not rename. It must
  NEVER sign a Play upload.
- **Play upload key**: lives only in GitHub secrets (`PLAY_UPLOAD_*`);
  the dispatch-only `stepcast-play-release.yml` workflow builds the signed
  AAB. See PLAY_READINESS.md.

## Rules

- No code, assets, or names from any decompiled app may ever be added.
  Features are re-implemented from behavior only.
- Room schema changes need real migrations (see StepcastDatabase.kt for
  the v9→current chain).
- Update FEATURES.md in the same commit as any user-visible change.
