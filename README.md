# Android starter

A template for a **native Android app that stores everything on the device**: Kotlin, Jetpack
Compose, Material 3, Room, no backend. It is the scaffolding extracted from a shipped app — the
release pipeline, the quality gates, and the handful of screens needed to exercise them end to end.

The app it builds is deliberately trivial: a list of items, a detail screen, an editor with a photo,
settings, backup and restore. **That is not the point.** The point is everything around it, which is
the part that takes weeks to get right and looks like nothing on a screenshot.

## Start

```bash
gh repo create my-app --template maksforge/android-starter --private --clone
cd my-app
python3 bootstrap.py --name "My App" --namespace app.myapp --appid io.github.<you>.myapp
git config core.hooksPath .githooks     # Conventional Commits — release-please depends on it
./gradlew assembleDebug test
rm bootstrap.py
python3 scripts/repo-setup.py           # ruleset, merge strategy, Pages — none of it is inherited
```

`bootstrap.py` also writes **`.claude/settings.json`**: a Claude Code hook that stops Claude's `git push`
until the next release's Play notes are written (`scripts/notes-gate.py --pending`). The template ships
without it on purpose — the template never releases, so the hook would block every push to it — which
makes installing it a **once-per-repo step**, done by bootstrap. Commit the file, and launch Claude Code
inside the new repository rather than a parent folder, or the hook never loads.

`bootstrap.py` also replaces this README and the template's LICENSE — the latter with a notice that
reserves all rights, because "use it for anything" left on an app's public repository reads as exactly
that grant. Choose your licence before the first push you care about.

Then:

1. **[`docs/optional-modules.md`](docs/optional-modules.md)** — delete what the app does not need
   (Room, photos, backup, reminders) **before the first upload**, while deleting is free. It lists
   every file per module and the places around the code a strip reaches.
2. **`art/mark.py`** — replace the placeholder mark, and run `python3 art/make-launcher-icon.py`.
3. **`scripts/gen_scheme.py`** — change the four brand seeds and regenerate `theme/Color.kt`.
4. **`data/ItemEntity.kt` and `ui/items/`** — replace the placeholder domain with yours, and rewrite
   `scripts/edge-to-edge.py`'s SCENES against your screens in the same week.
5. **`scripts/aab-permissions.py`** — its allowlists describe the template; keep them true to your
   artifact, and run the four `aab-*.py` checks on a local `bundleRelease` before the first tag.

`docs/DOD.md`'s *Before the first upload* is the full list.

## What comes with it

**Release pipeline.** Conventional Commits → release-please → a versioned tag → an AAB signed with a
key that is refused when missing → an internal Play track. From there, two hand-triggered workflows
promote the same bytes: to closed testing (which a new Play account must pass before production), and
to production behind an approval gate and a staged rollout. `versionCode` is the commit count;
`versionName` is release-please's. Neither is ever hand-edited.

**Quality gates that run in CI**, each existing because of a specific way a release went wrong:

| Gate | Refuses a merge when |
| --- | --- |
| `scripts/schema-gate.py` | a schema bump arrives without its migration, its exported JSON, or its launch-gate assertion |
| `scripts/translation-gate.py` | a shipped language is incomplete, or a translation is stale against changed English |
| `scripts/notes-gate.py` | `versionName` moved and the Play release notes did not |
| `TranslationTest` | format arguments, plural categories (per CLDR), orphans, `translatable="false"` |
| Spotless / ktlint | formatting, before the expensive steps run |

**Artifact checks** that read the built bundle rather than the source: `aab-permissions.py` (what a
dependency merged into your manifest), `aab-locale.py`, `aab-version.py`, `aab-reflection.py`.

**Device drivers.** `edge-to-edge.py` walks every screen in four navigation configurations across an
emulator matrix and asserts nothing is drawn under the system bars. `screenshots.py` captures the
Play listing set off the shipped build, with the status bar emptied and the phone's own theme,
locale and Do Not Disturb handed back afterwards. `device-gate.py` reads what a vendor ROM will
actually let the app do; `doze-capture.sh` keeps the evidence of an overnight run.

**In the app itself:** a schema wipe guard that copies the database aside *before* Room can open it,
a kind-aware image pipeline that strips EXIF, backup export/restore with a manifest, a custom
`BackupAgent` that checkpoints the WAL, an in-app language switcher that works below Android 13, a
theme with a generated palette and a real light/dark override, and a generated open-source
attribution screen.

## Reading order

- **[`CLAUDE.md`](CLAUDE.md)** — the house rules. Short, and loaded every session by Claude Code.
- **[`docs/adr/`](docs/adr/)** — the decisions and *why*. Read before changing what they cover.
- **[`docs/DOD.md`](docs/DOD.md)** — the live checklist, and the standing checks that never close.
- **[`docs/PLAN.md`](docs/PLAN.md)** — the roadmap skeleton; each phase gets its own `phase-N.md`.
- **[`docs/optional-modules.md`](docs/optional-modules.md)** — what to delete, and what breaks when you do.
- **[`docs/RELEASING.md`](docs/RELEASING.md)** — commits, versions, and how a build reaches Play.

## Stack

| Choice | Note |
| --- | --- |
| Kotlin, Compose, Material 3 | Android only, `minSdk` 26, `compileSdk`/`targetSdk` 36 |
| **Navigation 3** (`androidx.navigation3`) | not Navigation Compose 2.x |
| Room via KSP | DAOs return `Flow`; schema evolution per ADR-0001 |
| Manual DI via `AppContainer`, **not Hilt** | constructor injection, readable in one file, mechanical to migrate later |
| WorkManager + optional exact alarms | two mechanisms on purpose — ADR-0003 |
| Photo Picker + `TakePicture`, **not CameraX** | far less code; the system camera is fine |
| Coil 3 | local files only; no network module |
| On-device storage only | no backend. Backup per ADR-0005 |

## Requirements

JDK 21, the Android SDK, Python 3 (for the toolchain), and a phone or emulator. No Android Studio
needed — everything here runs from the CLI.

## Licence

The template is yours to use. `app/src/main/assets/licences/` holds the texts the built app ships;
that obligation travels with anything you build from this.
