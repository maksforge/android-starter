package app.starter.data.backup

import java.io.File
import java.io.IOException
import java.time.Instant

/*
 * Auto Backup as functions over `File` (ADR-0005): the agent runs in a process bound to the base
 * `Application`, with no container to reach for.
 */

/**
 * Below the ~25 MB quota with room to spare, because Android rejects an over-quota dataset whole and
 * the database goes with it.
 */
const val AUTO_BACKUP_BUDGET_BYTES: Long = 20L * 1024 * 1024

/** Relative to `filesDir`, and never itself backed up. */
const val AUTO_BACKUP_MARKER_FILE = "auto-backup-marker.txt"

private const val MARKER_LAST_BACKUP_KEY = "lastBackupAtEpochMilli"
private const val MARKER_LEFT_OUT_KEY = "leftOutThumbnails"
private const val MARKER_OVER_QUOTA_KEY = "overQuota"

data class AutoBackupSet(
    val files: List<File>,
    val leftOutThumbnails: Int,
)

/**
 * The thumbnails that fit under what [budget] leaves after [coreBytes], in the order given. Skips
 * rather than stops, so one large file does not leave out the smaller ones after it.
 */
fun admitThumbnails(
    coreBytes: Long,
    newestFirst: List<File>,
    budget: Long = AUTO_BACKUP_BUDGET_BYTES,
): AutoBackupSet {
    var remaining = (budget - coreBytes).coerceAtLeast(0)
    val admitted = mutableListOf<File>()
    for (file in newestFirst) {
        val size = file.length()
        if (size <= remaining) {
            admitted += file
            remaining -= size
        }
    }
    return AutoBackupSet(files = admitted, leftOutThumbnails = newestFirst.size - admitted.size)
}

/**
 * What the agent hands over: the staged database always, then thumbnails newest first under the
 * budget. A device-to-device transfer has no quota, so there every thumbnail and every photo goes.
 */
fun autoBackupFileSet(
    stagedDatabase: File,
    thumbnails: List<File>,
    photos: List<File>,
    deviceToDeviceTransfer: Boolean,
    budget: Long = AUTO_BACKUP_BUDGET_BYTES,
): AutoBackupSet {
    val database = listOfNotNull(stagedDatabase.takeIf { it.isFile })
    if (deviceToDeviceTransfer) return AutoBackupSet(database + thumbnails + photos, leftOutThumbnails = 0)
    val newestFirst = thumbnails.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
    val admitted = admitThumbnails(database.sumOf { it.length() }, newestFirst, budget)
    return AutoBackupSet(database + admitted.files, admitted.leftOutThumbnails)
}

/** What the last automatic backup attempt left behind; [overQuota] means Android rejected it whole. */
data class AutoBackupMarker(
    val lastBackupAt: Instant?,
    val leftOutThumbnails: Int = 0,
    val overQuota: Boolean = false,
)

fun writeAutoBackupMarker(
    filesDir: File,
    at: Instant,
    leftOutThumbnails: Int,
) = writeMarker(filesDir, "$MARKER_LAST_BACKUP_KEY=${at.toEpochMilli()}\n$MARKER_LEFT_OUT_KEY=$leftOutThumbnails\n")

/** Written over the marker [writeAutoBackupMarker] left moments before, from the same attempt. */
fun writeOverQuotaMarker(filesDir: File) = writeMarker(filesDir, "$MARKER_OVER_QUOTA_KEY=true\n")

fun clearAutoBackupMarker(filesDir: File) {
    File(filesDir, AUTO_BACKUP_MARKER_FILE).delete()
}

/** Null when absent or unreadable, which renders as "none recorded", never as a working backup. */
fun readAutoBackupMarker(filesDir: File): AutoBackupMarker? {
    val marker = File(filesDir, AUTO_BACKUP_MARKER_FILE)
    if (!marker.isFile) return null
    return try {
        val values =
            marker
                .readLines()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1).trim()
                }.toMap()
        val overQuota = values[MARKER_OVER_QUOTA_KEY] == "true"
        val lastBackupAt = values[MARKER_LAST_BACKUP_KEY]?.toLongOrNull()?.let(Instant::ofEpochMilli)
        if (lastBackupAt == null && !overQuota) return null
        AutoBackupMarker(
            lastBackupAt = lastBackupAt,
            leftOutThumbnails = values[MARKER_LEFT_OUT_KEY]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            overQuota = overQuota,
        )
    } catch (e: IOException) {
        null
    }
}

/** Temp-then-rename, so a kill mid-write leaves the previous marker rather than half a line. */
private fun writeMarker(
    filesDir: File,
    text: String,
) {
    val marker = File(filesDir, AUTO_BACKUP_MARKER_FILE)
    val part = File(filesDir, "$AUTO_BACKUP_MARKER_FILE.part")
    try {
        filesDir.mkdirs()
        part.writeText(text)
        if (!part.renameTo(marker)) {
            part.copyTo(marker, overwrite = true)
            part.delete()
        }
    } catch (e: IOException) {
        part.delete()
    }
}
