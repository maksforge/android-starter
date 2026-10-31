# Optional modules — what to delete, and what breaks when you do

The template ships an app that *records things*: a Room database, photos, backup export and restore,
and reminders. Most of that is machinery with a data-loss story attached, and an app that does not
need a piece of it should delete it **before the first upload**, while deleting is free.

**Why before, and not "later, if it turns out unused".** Nothing is shipped yet, so a table deleted
today comes back at version 1 if a feature ever needs it. Ship an unused `items` table and remove it
later, and that removal is a destructive migration against real installs — the exact failure ADR-0001
exists to prevent. The same asymmetry holds for permissions: one declared today is a Data safety answer
you will have to keep defending.

The first app built from this template stored settings rather than records and deleted everything
below except the preferences on day one. It took 69 files, and five things around the code broke that
nothing had said depended on the code. This page is that strip written down, so the next one is a
checklist rather than a discovery.

## How to decide

**The test is shape, not subject matter.**

- **A fixed set is plain DataStore keys**, however many members it has: four schedule options are
  settings, and seven per-day windows are fourteen settings.
- **A small list the user makes is JSON under one DataStore key**, read whole and written whole. Its
  migration story is the decoder's tolerance: unknown keys ignored, a default on every field added
  later, and one unreadable entry dropped rather than the whole list. The template's `AppBackupAgent`
  leaves the store out of backups; once Auto Backup takes over (*Backup export/restore* below) the
  list reaches the user's next phone with every other key, which is right for anything that is
  really a setting. The first app built from this template keeps its saved presets this way rather
  than bring Room back.
- **A table is for querying, relations or partial writes** — records found by a field, joined to
  other records, or too many to rewrite whole. Only then Room, and ADR-0001 comes with it.

## The modules

Each row lists what to delete together. Delete a module, then build, then run `./gradlew test lint` and
the scripts in the last column — they are what tells you the strip is finished rather than the compiler.
Lint fails on every resource the deleted code was the last to use (`UnusedResources` is an error,
because resource shrinking would strip it from the bundle), so remove those in every language.

### Room database and the schema gate

- `data/AppDatabase.kt`, `ItemDao.kt`, `ItemEntity.kt`, `ItemRepository.kt`, `Converters.kt`,
  `Migrations.kt`, `SchemaGate.kt`, `DatabasePreserve.kt`, `BackgroundSchemaGuard.kt` and, if the
  reminders stay, `ReminderSweep`'s call to it
- `ui/wipe/SchemaMismatchScreen.kt` and its `schema_mismatch_*` strings, both variants'
  `ui/wipe/SchemaMismatchContinue.kt` and its debug string, the wipe guard's call in `MainApplication`
- `app/schemas/`, `test/…/data/SchemaGateTest.kt`, `androidTest/…/data/ItemDaoTest.kt`,
  `MigrationTest.kt`
- Gradle: the `ksp` plugin and `ksp {}` block, the three Room dependencies, `room.testing`, and the
  `androidTest` schema-assets line in `androidComponents`
- **What adapts on its own**: `scripts/project.py` reads `HAS_DATABASE` off `data/AppDatabase.kt`, so
  `schema-gate.py` passes with *"no database, nothing to gate"*, `doze-capture.sh` stops pulling a
  file that is not there, and the `mismatch` suite leaves `edge-to-edge.py` and `screenshots.py`.
- **What does not**: `docs/DOD.md`'s standing schema gate — mark it *parked*, keep the five points
  verbatim, and say it comes back the day a feature adds a table. ADR-0001 gets a dated amendment,
  not a rewrite (`docs/adr/0000-template.md`).

### Photos (the media pipeline)

- `media/MediaFiles.kt`, `androidTest/…/media/MediaFilesTest.kt`, `res/xml/file_paths.xml` and the
  `FileProvider` in the manifest
- Gradle: `coil.compose`, both `exifinterface` lines
- The items editor's picker and its `item_pick_image` string, if the placeholder domain stays
- `scripts/aab-permissions.py`: nothing to remove — the photo path declares no permission — but
  `CAMERA` stays in FORBIDDEN, because a dependency that merges it changes the listing.
- The privacy policy's *Photos* and *camera* bullets, and `play-app-content.md`'s Data safety point 2.
- ADR-0002 gets its amendment.

### Backup export/restore, and the custom `BackupAgent`

- `data/backup/`, `ui/backup/`, `test/…/data/backup/`, `AppBackupAgent.kt` and its manifest
  attributes; `scripts/upgrade-diff.py`, which reads those backups
- Settings' *Backup* row (`onOpenBackup`, the `Backup` route, `settings_backup`) and the `backup_*`
  strings
- If nothing else shares a file: `ui/common/Share.kt`, `ShareBook.kt` and `ShareBookTest.kt`,
  `androidTest/…/ui/common/ShareChosenCallbackTest.kt`, `MainActivity.onResume`'s
  `revokeUnpickedShare()`, `ManifestWiringTest`'s share case, the `ShareChosenReceiver` entry and the
  two `SEND` entries in the manifest's `<queries>`, and the privacy policy's sentences on sharing
- `kotlinx.serialization` **stays**: Navigation 3 serialises every `NavKey`.
- **Platform Auto Backup takes over**, with `android:dataExtractionRules` if anything must stay on
  the phone. ⚠ **Every DataStore key is then backed up, whether you meant it to be or not** —
  exclusions are per *file*, and the preferences store is one file — so every key lands on the user's
  **next phone** with no code of yours involved. Ask what each key means there: a setting is welcome,
  *live state* is not ("the service should be running", "a deadline is armed"). Judge live state **at
  the read**, against something the platform never backs up — `PackageInfo.firstInstallTime` is one.
  The first app built from this template met this in its restore test: a restored "running" flag
  was acted on by a boot receiver on a phone that had never granted the permission it assumed.
- ADR-0005 gets its amendment.

### Reminders and WorkManager

This one is a pure deletion: outside `work/`, only `MainApplication` and Settings' *Reminders*
section use it, and both are listed below. `startActivitySafely` lives in `ui/common/Intents.kt` and
the background schema check in `data/BackgroundSchemaGuard.kt` because other code needs them after
the reminders are gone.

- `work/ReminderSweep.kt`, `BootReceiver.kt`, `PackageReplacedReceiver.kt`, `ReceiverWork.kt`,
  `ExactAlarms.kt`, `ReminderChannels.kt`, its test and the `channel_reminders_*` strings;
  `NotificationPermission.kt` if nothing else posts; `Autostart.kt`, `BatteryExemption.kt` and the
  `com.miui.securitycenter` `<queries>` entry if nothing else runs in the background
- `res/drawable/ic_notification.xml` and `res/raw/keep.xml`, if nothing else posts
- `androidTest/…/ManifestWiringTest.kt`'s boot, update and boot-permission cases, or the whole file
  if the share case has gone with the backup
- `app/proguard-rules.pro`'s *Workers, across versions* note; `.gitignore`'s `/doze-*/` and
  `scripts/doze-capture.sh`, which captures an overnight sweep
- Manifest: `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, the receivers, and the
  androidx.startup `tools:node="remove"` for WorkManager's initializer (the provider itself stays —
  emoji2 registers there)
- `MainApplication`'s `Configuration.Provider` and `ensureSweepEnqueued` call; Gradle's
  `work.runtime` and `work.testing`
- ⚠ **WorkManager merges three permissions your source never declares** — `WAKE_LOCK`,
  `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE` — and `RECEIVE_BOOT_COMPLETED` for good measure. If you
  keep a boot receiver but drop WorkManager, **declare `RECEIVE_BOOT_COMPLETED` yourself first**, or the
  receiver stops being called with nothing to say so. `ACCESS_NETWORK_STATE` on an app with no
  `INTERNET` is a line a Play reviewer can ask about, and **the tip merges it too**: removing
  WorkManager removes the question only if the tip goes as well.
- `scripts/aab-permissions.py`'s EXPECTED rows for all of the above, in the same commit — except
  `ACCESS_NETWORK_STATE` while the tip stays, which then belongs to billing alone: keep the row, and
  its line in `play-app-content.md`, and name billing as the only owner.
- `scripts/device-gate.py`'s reminder checks, if nothing schedules: `ALARM_TAG` and the alarm reads
  (`app_alarms`, `armed`, `disarmed`), the channel pair (`channel_importance`, `set_channel`), the
  `--reboot` arm (`wait_for_unlock`, `reboot_and_wait`), and the exact-alarm line. Keep the autostart
  half while anything else runs in the background.
- **Settings' *Reminders* section, and the ask and banner behind it** — the `SwitchRow`, the
  `rememberNotificationPermissionAsk` call, the resumed re-read of `notificationsAllowed`, the
  `WarningBanner`, and every `settings_reminders*` string, plus
  `AppPreferences.remindersEnabled`, `Keys.REMINDERS_ENABLED` and its place in `Keys.SETTINGS`, and
  the reminder lines in `AppPreferencesTest`. That whole block is `PLAN.md` rule 4 worked through
  for one ask; it is the shape to copy for *your* first ask, so read it before deleting it.
- "reminders" in `settings_restore_hint` and `settings_restore_confirm_body`, in every language, or
  *Restore defaults* promises to reset something that is gone.
- ADR-0003 gets its amendment.

### The tip (Play Billing)

Every line it adds is deleted outright, along with the imports that leave unused; the two **edits**
are marked.

- `tip/`, `test/…/tip/`; `ui/support/SupportViewModel.kt` and `TipSection.kt`, and in
  `SupportScreen.kt` the `TipSection()` call and the KDoc paragraph that names it
- The one-time ask: `ui/items/TipAskCard.kt`; `ItemsScreen`'s `onOpenSupport` parameter and its
  `showTipAsk` item; the `onOpenSupport` the `Items` entry passes in `Navigation.kt`; in
  `ItemsViewModel`, `showTipAsk`, the `preferences`, `tipJar` and `installedAt` parameters and their
  arguments in `Factory`, the `withTipAsk` line, the `init` block and `answerTipAsk`
- `AppPreferences.tipAsked` / `markTipAsked`, `Keys.TIP_ASKED` and its place in `Keys.KEPT`, and the
  tip lines in `AppPreferencesTest`
- `AppContainer.tipJar` and `installedAt` (`applicationScope` keeps its `@Suppress("unused")` for
  this day); `settleTipsOnEveryForeground` and its call in `MainApplication`
- The `items_tip_ask_*` and `support_tip*` strings, in every language
- Gradle: `billing.ktx`, `androidx.lifecycle.process` and licensee's `allowUrl` for Google's terms in
  `app/build.gradle.kts`; the `billing` version and the `billing-ktx` and
  `androidx-lifecycle-process` aliases in `libs.versions.toml`
- Manifest: the `INTERNET` `tools:node="remove"` and its comment — harmless once billing is gone, but
  it then removes nothing and reads as a reason that no longer exists
- `scripts/aab-permissions.py`: the `BILLING` row and its comment, and the datatransport comment above
  `ACCESS_NETWORK_STATE`. **Edit** that row's note back to WorkManager alone, or delete the row if
  WorkManager has already gone.
- `play-app-content.md`: the `BILLING` row and the `datatransport` paragraph under *What the artifact
  carries*; the tip paragraph under *Content rating*, which leaves the all-No answer; Data safety
  point 5 and its *Would change* bullet; and *The tip*. **Edit** the `ACCESS_NETWORK_STATE` row back
  to WorkManager alone. Keep *A payment-link tip*: an app that drops the billing tip is the one most
  likely to try a link next.
- The privacy policy's billing-library sentence under *The app collects nothing*, its billing bullet
  and its tip paragraph
- `CLAUDE.md`'s Play Billing row in Stack and `tip/` line in Layout; `CONTEXT.md`'s **Tip** row
- `RELEASING.md`'s *Selling the tip*; the tip section of `DOD.md`
- ADR-0007 gets its amendment.

### The placeholder domain

- `ui/items/`, the items routes in `Navigation.kt` / `NavigationKeys.kt`, and the `tab_items`,
  `item_*`, `items_*`, `action_save`, `action_edit` and `action_delete` strings
- ⚠ **The tip's one ask lives on the items screen** and counts items as its measure of use. Move
  `TipAskCard.kt` and its `items_tip_ask_*` strings to your home screen and give `tipAskDue` your own
  measure, or the ask goes with them
- `debug/…/data/SampleData.kt`, `debug/…/debug/SeedReceiver.kt`, the debug manifest's receiver, and
  the seed rows in `DebugSettings.kt` with their `debug_seed` and `debug_wipe` strings — or keep the
  seeding seam and point it at your own data
- **`scripts/edge-to-edge.py`'s SCENES**, `TAB_BAR`, `HOME_TAB` and `SEED_WALK`: they walk the
  template's screens. Rewrite them against yours the same week — until then the nightly job boots ten
  emulators to walk screens that do not exist and reports a red everyone learns to ignore.

## Around the code

Five places the strip reaches that no compiler will point at:

1. **`scripts/aab-permissions.py`** — EXPECTED must be exactly what the artifact carries. Run it
   against a local `bundleRelease` (or `bundleDebug`); a stale row fails the release upload, after the
   tag, which is where the first app built from this template found it.
2. **CI's instrumented job** fails on zero tests. If the strip deletes every `androidTest`, write one
   real device test — the thing the app must never get wrong is a good first one — or delete the job.
3. **`CLAUDE.md`** — the stack table, the house rules that name deleted code, and the layout block.
4. **`docs/privacy-policy.md` and `docs/play-app-content.md`**, together, in the same commit.
5. **The ADRs** — each module above names the one that gets a dated amendment. Never delete an ADR:
   the reasoning is what the next feature that wants the module back will need.
