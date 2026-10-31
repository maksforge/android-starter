# The database stops being disposable at 1.0, and a launch gate stands in front of Room

## Context

While a data model is still moving, carrying a hand-written migration for every field added to a
shape that will change again next week is pure cost. Room's `fallbackToDestructiveMigration` exists
for exactly that phase: bump the version, lose the data, move on.

The moment the app is installed from a store, that becomes unacceptable. Somebody's records are on
their phone and there is no other copy. Worse, the failure is *silent* — Room empties the database
and the app opens looking perfectly healthy, on a blank slate.

There is a second, less obvious hazard. The OS can start the process with no UI and nobody looking:
a `BOOT_COMPLETED` broadcast, a WorkManager job, a backup agent. Any of those touching a repository
forces the database open — so a destructive fallback can fire in the background, on a phone in
someone's pocket.

## Decision

**Before 1.0**, the debug build keeps the destructive fallback, and it is never silent: the file is
copied aside into `filesDir/preserved/` first, and a consent screen stands in front of the wipe.

**From 1.0**, every schema version that reaches a device carries a tested forward migration, and the
fallback is gone from the release build entirely.

**A launch gate reads the schema version before Room exists.** SQLite writes `user_version` as a
big-endian 32-bit integer at byte 60 of the 100-byte file header, so the version is a four-byte read
that needs neither Room nor SQLite. `schemaGateDecision` turns that into one of three answers:

| | meaning |
| --- | --- |
| `Open` | this build can open the file — no mismatch, or one a registered migration can walk |
| `Consent` | a debug build with no migration to offer: show the screen, then wipe |
| `Refuse` | a release build that genuinely cannot read this file: a dead end offering the copy |

**The guard is structural, not procedural.** `MainApplication.container` sits behind a `lazy` that is
forced only after the gate passes. No Room object exists, so no collection of one can exist. Every
background entry point asks the same question first, through `schemaBlocksBackgroundWork()`.

## Alternatives

**Keep the container constructed and merely stop it from *collecting*.** Works today, and is one
eager `stateIn` away from silently breaking. A guard by absence-of-subscription is unwritten and
unenforceable, and it would be load-bearing for the only copy of data a user cannot retype.

**Ask "do the versions differ?" instead of asking the gate's question.** This is the shape the guard
took first, and it was wrong in a way that took a real release to find: it cannot tell *a version
this build must not open* from *a version this build has not opened yet*, and an ordinary upgrade is
the second one. Every user updating across a schema bump met the refusal screen, and the migration
that had been tested against real archives never ran.

**Trust the migration tests.** They open the database *directly* and so walk straight past the gate.
A completely green migration suite says nothing about whether a real upgrade is let through.

## Consequences

- A schema bump owes five things, listed in `docs/DOD.md`. Three are enforced mechanically by
  `scripts/schema-gate.py`; the other two are yours.
- The consent screen and the refusal screen are the same composable with a flag, and the flag decides
  whether the destructive button shows. Rendering one button that is sometimes destructive is how a
  release build ends up wiping someone's data. The button itself is `ui/wipe/SchemaMismatchContinue.kt`,
  one file per variant, so a release binary carries neither it nor its string; the flag is still
  needed because `-PreleaseShapedDebug` compiles the debug half without the destructive fallback.
- `preserved/` is never pruned. It is small and it is the last copy.
