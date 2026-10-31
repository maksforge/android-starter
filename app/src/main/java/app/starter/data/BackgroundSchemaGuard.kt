package app.starter.data

import android.content.Context

/**
 * **The wipe guard, asked from a background entry point** — the one shape every worker, receiver and
 * service needs before it touches anything (ADR-0001).
 *
 * The hazard is specific: the OS can start this process to run a worker or deliver a broadcast with
 * no UI and no user present, and anything that forces the container over a schema this build cannot
 * open destroys the database in the background on a phone nobody is looking at. So the question is
 * asked out of four bytes of the file header rather than by opening anything, and the answer is
 * `true` exactly when the right move is to do nothing.
 *
 * **It asks the launch gate's question, not "do the versions differ".** A debug build still blocks
 * on any mismatch — it has no migrations registered, so opening means wiping — and a release build
 * still blocks on a file no migration covers, where opening would throw in a process with nobody to
 * show it to. What it does *not* block is the ordinary upgrade: a mismatch a registered migration
 * can walk is one the next database open performs anyway, so a worker that skipped it would be
 * sitting out a migration happening around it. Getting this distinction wrong is what leaves the
 * first reminder after an update unposted, and the chain stopped until someone opens the app.
 *
 * In `data/` rather than `work/` so it survives removing the reminders: any later background entry
 * point needs it, and it belongs to the database, not to WorkManager.
 */
internal fun Context.schemaBlocksBackgroundWork(): Boolean =
    schemaGateDecision(
        readUserVersion(getDatabasePath(APP_DATABASE_FILE)),
        APP_SCHEMA_VERSION,
    ) != SchemaGate.Open
