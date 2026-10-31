package app.starter.data.backup

import app.starter.data.APP_SCHEMA_VERSION
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * One export or restore at a time, across the process. A screen's busy flag cannot be the guard: a
 * screen left and opened again gets a new ViewModel while the old one's work may still be running,
 * and a restore swapping files under a running export ships an archive that is half of each.
 */
val backupLock = Mutex()

/** What a restore did, or why it did not. */
sealed interface RestoreResult {
    data class Restored(
        val schemaVersion: Int,
    ) : RestoreResult

    /** The file is not one of ours, or is damaged. */
    data object NotABackup : RestoreResult

    /**
     * The archive was written by a **newer** build of the app.
     *
     * Refused rather than attempted: a migration only runs forwards, so there is no path from a
     * newer schema to this one, and opening it would leave Room to destroy the file.
     */
    data class TooNew(
        val schemaVersion: Int,
        val supported: Int,
    ) : RestoreResult
}

/**
 * Replaces the live data with the contents of an archive (ADR-0005).
 *
 * **The order here is the whole design, and getting it wrong loses everything.**
 *
 * 1. Read the manifest *first*, out of the archive, and refuse before touching anything live.
 * 2. Extract into a **staging directory**, so a truncated or corrupt archive cannot half-replace
 *    the real data.
 * 3. Only once the extraction has completed, swap staging into place.
 *
 * The tempting shortcut — extract straight over the live files, since that is where they are going
 * anyway — turns every interrupted restore into total data loss. A user restoring a backup is
 * already having a bad day.
 *
 * ⚠️ **The database sidecars must go.** Replacing `app.db` and leaving `app.db-wal` behind leaves
 * SQLite with a log belonging to a different database, and the result is a corrupt open on next
 * launch. The restore deletes both sidecars once the new database is beside the live one, then
 * renames it over `app.db` ([installDatabase]).
 */
class BackupRestorer(
    private val databaseFile: File,
    private val filesDir: File,
    private val stagingDir: File,
    private val supportedSchema: Int = APP_SCHEMA_VERSION,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun restoreFrom(source: () -> InputStream): RestoreResult =
        withContext(io) {
            val manifest = readManifest(source) ?: return@withContext RestoreResult.NotABackup
            if (manifest.schemaVersion > supportedSchema) {
                return@withContext RestoreResult.TooNew(manifest.schemaVersion, supportedSchema)
            }

            sweepPartFiles(databaseFile, filesDir)
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            try {
                extractTo(source, stagingDir)

                // Everything is on disk and readable before anything live is touched.
                val stagedDatabase = File(stagingDir, DATABASE_ENTRY)
                if (stagedDatabase.isFile) installDatabase(stagedDatabase, databaseFile)

                for (directory in stagingDir.listFiles().orEmpty()) {
                    if (!directory.isDirectory || directory.name == "database") continue
                    val target = File(filesDir, directory.name)
                    target.mkdirs()
                    for (file in directory.listFiles().orEmpty()) {
                        // Merge rather than replace: media filenames are UUIDs, so a name collision
                        // means the same file, and keeping files the archive does not mention costs
                        // disk where deleting them costs someone their photos.
                        installFile(file, File(target, file.name))
                    }
                }
                RestoreResult.Restored(manifest.schemaVersion)
            } finally {
                stagingDir.deleteRecursively()
            }
        }

    private fun readManifest(source: () -> InputStream): BackupManifest? =
        runCatching {
            ZipInputStream(source().buffered()).use { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { it.name == MANIFEST_ENTRY }
                    ?.let { backupJson.decodeFromString<BackupManifest>(String(zip.readBytes())) }
            }
        }.getOrNull()

    private fun extractTo(
        source: () -> InputStream,
        into: File,
    ) {
        ZipInputStream(source().buffered()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.isDirectory) return@forEach
                val target = File(into, entry.name)
                // **Zip-slip guard.** An entry named `../../databases/other.db` would otherwise
                // write outside the staging directory. Never trust a path out of an archive, even
                // one the app wrote — the file arrives from the user's storage, not from the app.
                if (!target.canonicalPath.startsWith(into.canonicalPath + File.separator)) {
                    return@forEach
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
                if (entry.time >= 0) target.setLastModified(entry.time)
            }
        }
    }
}
