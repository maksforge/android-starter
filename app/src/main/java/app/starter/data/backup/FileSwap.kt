package app.starter.data.backup

import app.starter.media.MediaKind
import java.io.File

/*
 * Swapping a live file for a new one so that a kill at any point leaves the old file or the new one
 * whole (ADR-0005). Shared by the in-app restore and the Auto Backup agent's restore.
 */

internal const val PART_SUFFIX = ".part"

private val DATABASE_SIDECAR_SUFFIXES = listOf("-wal", "-shm")

/** The media of [kind] under [filesDir], without the `.part` an interrupted restore left behind. */
fun mediaFilesIn(
    filesDir: File,
    kind: MediaKind,
): List<File> =
    File(filesDir, kind.directory)
        .listFiles()
        .orEmpty()
        .filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }

/** Removes the `.part` files a restore killed between copy and rename left beside the live ones. */
fun sweepPartFiles(
    databaseFile: File,
    filesDir: File,
) {
    val mediaParts =
        MediaKind.entries.flatMap { kind ->
            File(filesDir, kind.directory).listFiles().orEmpty().filter { it.name.endsWith(PART_SUFFIX) }
        }
    (mediaParts + File(databaseFile.path + PART_SUFFIX)).filter { it.isFile }.forEach { it.delete() }
}

/**
 * Puts [replacement] in place of [databaseFile]. The sidecars go after the slow copy and before the
 * rename: a `-wal` left beside a different `app.db` is a corrupt open on the next launch.
 */
fun installDatabase(
    replacement: File,
    databaseFile: File,
) {
    databaseFile.parentFile?.mkdirs()
    val part = replacement.copyTo(File(databaseFile.path + PART_SUFFIX), overwrite = true)
    for (suffix in DATABASE_SIDECAR_SUFFIXES) File(databaseFile.path + suffix).delete()
    part.moveOver(databaseFile)
}

/** Copies [source] over [target] by way of a `.part`, keeping its mtime: the Auto Backup budget orders by it. */
fun installFile(
    source: File,
    target: File,
) {
    val part = source.copyTo(File(target.path + PART_SUFFIX), overwrite = true)
    part.setLastModified(source.lastModified())
    part.moveOver(target)
}

/** A rename within one directory; a copy, not a rename, is what was slow. */
private fun File.moveOver(target: File) {
    if (!renameTo(target)) {
        copyTo(target, overwrite = true)
        target.setLastModified(lastModified())
        delete()
    }
}
