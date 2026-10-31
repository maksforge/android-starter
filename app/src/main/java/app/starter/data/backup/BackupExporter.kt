package app.starter.data.backup

import app.starter.data.APP_SCHEMA_VERSION
import app.starter.data.PRESERVED_TIMESTAMP_FORMAT
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Where an export lands before the share sheet picks it up, relative to `cacheDir`. */
const val EXPORTS_DIRECTORY = "exports"

/**
 * The filename an export gets. The scope is in it **for humans** — so two files in a downloads
 * folder can be told apart — and is never what a restore reads. The manifest inside is.
 */
fun exportFileName(
    scope: BackupScope,
    at: Instant,
): String = "backup-${scope.slug}-${PRESERVED_TIMESTAMP_FORMAT.format(at)}.zip"

/**
 * Writes a backup zip at a chosen [BackupScope] (ADR-0005).
 *
 * Takes paths rather than a `Context`, deliberately: the backup *agent* runs in a process where the
 * app's container does not exist, and it needs these same pieces. A class that took a `Context`
 * could not be shared with it.
 *
 * @param scratchDir somewhere disposable for the checkpointed database copy. Never the destination's
 *   own directory.
 * @param checkpoint how the database is made whole before it is zipped. Defaults to the real WAL
 *   checkpoint, and is a parameter so the archive's *layout* can be tested on the JVM, where
 *   `android.database.sqlite` does not exist. The default is the production wiring, so a caller
 *   cannot forget it by omission — the seam is for substituting the checkpoint, never for skipping.
 */
class BackupExporter(
    private val databaseFile: File,
    private val filesDir: File,
    private val scratchDir: File,
    private val appVersion: String = "",
    private val schemaVersion: Int = APP_SCHEMA_VERSION,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val checkpoint: (File, File) -> Unit = ::checkpointDatabaseTo,
) {
    /**
     * Build the archive at [target] and return it.
     *
     * **Written to a `.part` alongside and renamed on success**, so an export interrupted half way
     * leaves no file that looks shareable and is not. That is one line and it removes an entire
     * class of support question.
     *
     * Kotlin note: `withContext(io)` moves the zipping onto the IO dispatcher and suspends the
     * caller until it finishes — unlike an `async` function in JS, calling this starts nothing on
     * its own and there is no promise to await.
     */
    suspend fun exportTo(
        target: File,
        scope: BackupScope,
        now: Instant = Instant.now(),
    ): File =
        withContext(io) {
            target.parentFile?.mkdirs()
            scratchDir.mkdirs()
            val partial = File(target.path + ".part")
            val staged = File(scratchDir, "export-staged.db")

            try {
                checkpoint(databaseFile, staged)

                ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                    // A database is highly compressible and a JPEG is not. Best effort overall is
                    // still right: the JPEGs simply come out near their original size.
                    zip.setLevel(Deflater.BEST_COMPRESSION)

                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(
                        backupJson
                            .encodeToString(
                                BackupManifest(
                                    schemaVersion = schemaVersion,
                                    createdAt = now.toEpochMilli(),
                                    appVersion = appVersion,
                                ),
                            ).toByteArray(),
                    )
                    zip.closeEntry()

                    if (staged.isFile) {
                        zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
                        staged.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }

                    for (kind in scope.mediaKinds) {
                        for (file in mediaFilesIn(filesDir, kind).sortedBy(File::getName)) {
                            // The path inside the archive matches the path on disk relative to
                            // filesDir, which is what makes a restore a plain extraction. Media
                            // paths in the database are the same relative form, so nothing has to
                            // be rewritten on the way back in.
                            val entry = ZipEntry("${kind.directory}/${file.name}")
                            // A restore puts it back, so the Auto Backup budget's newest-first order survives.
                            entry.time = file.lastModified()
                            zip.putNextEntry(entry)
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }

                if (!partial.renameTo(target)) {
                    // A rename across the same filesystem does not fail in practice; if it somehow
                    // does, a copy still produces the file the caller was promised.
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                target
            } catch (e: Throwable) {
                partial.delete()
                throw e
            } finally {
                staged.delete()
            }
        }
}
