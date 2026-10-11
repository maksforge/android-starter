# <app name>

<One paragraph: what this app does, for whom, and the one constraint that shapes everything —
"free, ad-free, no server, all data on the device", or whatever replaces it.>

- **Vocabulary:** [`CONTEXT.md`](CONTEXT.md) — use these terms in code and UI.
- **Decisions and why:** [`docs/adr/`](docs/adr/) — read before changing anything they cover.
- **What's still open:** [`docs/DOD.md`](docs/DOD.md) — the live checklist. **Read this one first.**
- **The arc, in phases:** [`docs/PLAN.md`](docs/PLAN.md) — sequence only; a phase being built gets
  its own `docs/phase-N.md`. Read the phase you're in, not the file.
- **How to work here:** invoke the `lean-loop` skill at the start of every session and keep it
  active. This repo is written before it is built — plans, ADRs and phase documents are long on
  purpose — so the waste is re-reading what is already in the transcript and testing code whose
  shape has not settled.
- **Commits & releasing:** [`docs/RELEASING.md`](docs/RELEASING.md). Commit subjects **must** be
  [Conventional Commits](https://www.conventionalcommits.org) (`feat:`, `fix:`, `feat!:`, `docs:`, …)
  — a `commit-msg` hook rejects anything else, and release-please derives the version and
  `CHANGELOG.md` from them. `versionName` and `versionCode` are automated; never hand-edit either.

Don't restate ADR reasoning here; link to it. This file is loaded every session and must stay short.

## Working with the person who owns this repo

<Their background, and what to explain rather than assume. For example: "Fluent in
JavaScript/TypeScript, new to Kotlin. Comment code where a Kotlin/Compose idiom has no direct JS
analogue — `Flow` vs promises/observables, `suspend` vs `async`, `remember`/`derivedStateOf` vs
`useMemo`, data class `copy()` vs object spread, sealed classes vs discriminated unions. Explain the
*why*, don't restate the line. Prefer explicit and readable over clever.

The Android **platform** is the other half of the gap and has no JS analogue at all: the manifest as
a declaration the OS reads before any code runs, permission models that differ per API level, process
death, and vendor ROMs overriding documented behaviour. Say when a constraint comes from the platform
rather than from this codebase — that is the difference between "we chose this" and "Android will not
let us do otherwise".">

## Stack

| Choice | Note |
| --- | --- |
| Kotlin, Jetpack Compose, Material 3 | Android only, `minSdk` 26 |
| **Navigation 3** (`androidx.navigation3`) | Replaces Navigation Compose 2.x — don't reintroduce the old one |
| Room via KSP | DAOs return `Flow`; schema evolution per ADR-0001 |
| Manual DI via `AppContainer`, **not Hilt** | Constructor injection is clearer at this size and easy to migrate later |
| WorkManager + exact alarms | Two mechanisms on purpose — ADR-0003 |
| Photo Picker + `TakePicture` intent, **not CameraX** | Far less code; the system camera is fine here |
| Coil 3 images | Compose-native, local files only |
| Play Billing (`tip/`), for the tip only | Unlocks nothing; adds `BILLING`, and its merged `INTERNET` is removed — ADR-0007 |
| On-device storage only | No backend. Backup per ADR-0005 |

## House rules

- **An update never loses a user's data.** Any change to `APP_SCHEMA_VERSION` ships with its
  `MIGRATION_x_y` *registered in* `APP_MIGRATIONS`, the exported `app/schemas/*/N.json` committed, a
  `SchemaGateTest` assertion that the launch gate lets the upgrade through, and a migration test that
  proves the rows survive. `scripts/schema-gate.py` enforces the mechanical half in CI.
  **Every migration test opens the database directly and so walks past the launch gate** — that is
  how an app ships a refusal screen to every existing install with a green test run behind it.
  Verify an upgrade on the phone, not only in a test.
- **A branch merges with every shipped language complete.** Adding an English string does *not*
  redden your build — completeness is a merge gate (`scripts/translation-gate.py`), not a test, so
  copy is translated **once** rather than against draft wording and again after review. Everything
  else about a translation — format arguments, plural categories per CLDR, orphans,
  `translatable="false"` — is `TranslationTest` and holds continuously. Translate from **English
  only**, per [`docs/translator-brief.md`](docs/translator-brief.md).
- **Media paths in the DB are relative** and split by kind — `thumbnails/<uuid>.jpg`,
  `photos/<uuid>.jpg`, `documents/<uuid>.jpg` — resolved against `filesDir` at read time. Absolute
  paths change across installs and break restored backups; the split makes the backup export scopes a
  list of directories.
- **All image writes go through `media/MediaFiles.kt`** — it downsamples and re-encodes per kind,
  bakes in EXIF rotation, strips metadata (GPS included), and writes the file before the row.
  Bypassing it puts full-resolution bitmaps in memory and blows up any image grid.
- **Missing media renders as a placeholder, never a crash.** A restore may legitimately lack files.
- **DAOs return `Flow`**, screens collect it. Don't hand-roll refresh calls.
- **Enums with `TypeConverter`s, not loose strings**, for closed vocabularies. Store enums by
  **name, never ordinal**, so adding a value can't rewrite history.
- **A new preference follows *Adding a preference* in `data/AppPreferences.kt`.** A list the user
  makes is JSON under one key, not a table — [`docs/optional-modules.md`](docs/optional-modules.md),
  *How to decide*.
- One `ViewModel` per screen, UI state as a single immutable data class.
- **Developer-only surface lives in `app/src/debug/`, never in `main/` behind `BuildConfig.DEBUG`.**
  With `isMinifyEnabled = false` a statically-false branch is still compiled into the release AAB —
  a hide, not a strip — and its strings are still inside the translation gate. Each seam is one
  file per variant with a no-op in `src/release/`: `ui/settings/DebugSettings.kt` and
  `ui/wipe/SchemaMismatchContinue.kt`.
- **Colours come from `MaterialTheme`, never literals.** The palette is generated from four seeds by
  `scripts/gen_scheme.py`; edit the seeds and regenerate, never a single role.
- **Spacing comes from `theme/Spacing.kt`.** Six steps on a 4dp grid; no screen invents a seventh.

## Commands

```bash
./gradlew assembleDebug          # build
./gradlew installDebug           # build + install on the connected phone
./gradlew test                   # JVM unit tests
./gradlew connectedAndroidTest   # instrumented Room/DAO tests — needs a device
./gradlew lint
./gradlew spotlessApply          # format (the CI gate is spotlessCheck)
./gradlew assembleDebug -PreleaseShapedDebug   # debug id, R8 on — find minification bugs early

python3 scripts/project.py                      # what the toolchain thinks this app is called
python3 scripts/translation-gate.py --report    # what translations does this branch still owe?
python3 scripts/translation-gate.py origin/main # the gate itself — would this merge?
python3 scripts/schema-gate.py origin/main
python3 scripts/notes-gate.py
adb devices                                     # confirm the phone is attached
```

## Layout

```
app/src/main/java/<namespace>/
  MainActivity.kt, MainApplication.kt, AppContainer.kt, Navigation.kt, NavigationKeys.kt
               the app shell and Nav3 wiring stay at the package root, not under ui/ — they describe
               how the app hangs together rather than any one screen
  AppBackupAgent.kt   Android Auto Backup, taking control of its own file set (ADR-0005)
  data/        Room entities, DAOs, database, converters, migrations, the schema gate, preferences
  data/backup/ export, restore, the manifest, the WAL checkpoint
  media/       MediaFiles.kt — the single path for persisting images, kind-aware
  theme/       generated palette, type scale, spacing, the night-mode window half
  tip/         the tip through Play Billing, held by AppContainer so a purchase finishes after its
               screen is gone, and the rule for its one ask on the home screen (ADR-0007)
  ui/          Compose screens + ViewModels, one package per area
  work/        WorkManager, receivers, notification channels, permission asks

app/src/debug/    the developer-only build: the sample-data seeder, Settings' debug section and its
                  strings, the schema-mismatch screen's continue button, and SeedReceiver for the
                  capture driver
app/src/release/  only the no-op half of each seam, so main/ can call it unconditionally
scripts/          the Python toolchain. project.py is the one place the app's identity lives
art/             mark.py is the identity; both generators derive from it
```

## Versions

Pinned in `gradle/libs.versions.toml`: **AGP 9.0.1, Kotlin 2.3.20, Gradle 9.1.0, Compose BOM
2026.03.01**. `compileSdk`/`targetSdk` 36, `minSdk` 26, JDK 21 toolchain.

`compileSdk` stays at **36**: it is the combination AGP 9.0.1 validates against. Bump deliberately,
with a build to prove it.

## Environment notes

<Fill this in for the machine and phone you actually develop on — it is the section that saves the
most time, and none of it is discoverable from the code. What belongs here:>

- Whether there is an Android Studio, or everything is CLI against a physical phone over USB.
- **More than one device attached: name one.** Gradle and adb both reach every attached device;
  set `ANDROID_SERIAL` (both honour it) or pass `adb -s <serial>`. List each phone here with its
  serial, API level, and whether it is a test phone to drive at will or someone's phone in use.
- **A screenshot of a sleeping phone is its lock screen.** `adb shell svc power stayon usb` keeps it
  awake on USB, and `adb shell wm dismiss-keyguard` gets past a swipe lock.
- **On Xiaomi/HyperOS, MIUI optimisation decides whether adb installs work.** Off, they are
  tap-free; the toggle is at the bottom of Developer options, revealed by tapping "Reset to default
  values" a few times, and an OS update can turn it back on, so check
  `adb shell getprop persist.sys.miui_optimization` reads `false`. On, installs fail
  `INSTALL_FAILED_USER_RESTRICTED`: a split-APK install (`connectedAndroidTest`) prompts on the phone
  and a missed prompt fails the run, and the first install of a brand-new `applicationId` is refused
  outright with no dialog. "Install via USB" lifts that but wants a Mi account and a SIM. With
  neither, install both APKs plain and run the instrumentation directly:
  ```bash
  adb install -r -t app/build/outputs/apk/debug/app-debug.apk
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb shell am instrument -w <applicationId>.debug.test/androidx.test.runner.AndroidJUnitRunner
  ```
  and for a brand-new `applicationId`, `adb push` the APK to `/sdcard/Download/` and tap it in the
  file manager.
- **Xiaomi kills background work aggressively.** Scheduled notifications need battery-optimisation
  exemption and **autostart**, and autostart is the load-bearing one: without it the ROM does not
  start the process for a broadcast at all. It has no appop, it is a Settings toggle, and the grant
  **lapses on its own** — re-read it before any run that depends on it. `scripts/device-gate.py`
  reads and sets it.
- **Two device readings look identical to a pass when they are nothing at all.** A database pulled
  without its `-wal` sidecar is **stale** — the app's most recent writes sit in the WAL, so a deleted
  row still reads as present. Pull both and `PRAGMA wal_checkpoint(TRUNCATE)` on the host. And
  `cmd jobscheduler run -f -n androidx.work.systemjobscheduler <pkg> <id>` answers *"Could not find
  job N"* on stderr and **exits 0**: `am force-stop` cancels the app's jobs and WorkManager
  re-enqueues under a new id at the next launch, so read the id from `dumpsys jobscheduler` and
  confirm `WM-WorkerWrapper: Starting work for …` in logcat before believing any sweep result.
