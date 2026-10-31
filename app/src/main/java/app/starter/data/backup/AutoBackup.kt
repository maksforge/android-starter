package app.starter.data.backup

import java.io.File
import java.io.IOException
import java.time.Instant

/*
 * Auto Backup as functions over `File` (ADR-0005): the agent runs in a process bound to the base
 * `Application`, with no container to reach for.
 */

/**
 * Kept clear of the transport's quota, because Android rejects an over-quota dataset whole and the
 * database goes with it. Covers what the per-file cost below does not: the backup's own manifest and
 * metadata, and a database that grows between the measuring pass and the real one.
 */
const val AUTO_BACKUP_QUOTA_MARGIN_BYTES: Long = 2L * 1024 * 1024

/**
 * What a file costs in the backup's tar stream beyond its own bytes: a 512-byte header, padding to
 * the next 512, and room for a long-path extended header.
 */
const val AUTO_BACKUP_PER_FILE_OVERHEAD_BYTES: Long = 2L * 1024

/** The budget under [quotaBytes], the quota the transport reports it will enforce. */
fun autoBackupBudget(quotaBytes: Long): Long = (quotaBytes - AUTO_BACKUP_QUOTA_MARGIN_BYTES).coerceAtLeast(0)

private fun File.backupCost(): Long = length() + AUTO_BACKUP_PER_FILE_OVERHEAD_BYTES

/** Relative to `filesDir`, and never itself backed up. */
const val AUTO_BACKUP_MARKER_FILE = "auto-backup-marker.txt"

private const val MARKER_LAST_BACKUP_KEY = "lastBackupAtEpochMilli"
private const val MARKER_PREVIOUS_BACKUP_KEY = "previousBackupAtEpochMilli"
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
    budget: Long,
): AutoBackupSet {
    var remaining = (budget - coreBytes).coerceAtLeast(0)
    val admitted = mutableListOf<File>()
    for (file in newestFirst) {
        val size = file.backupCost()
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
    budget: Long,
): AutoBackupSet {
    val database = listOfNotNull(stagedDatabase.takeIf { it.isFile })
    if (deviceToDeviceTransfer) return AutoBackupSet(database + thumbnails + photos, leftOutThumbnails = 0)
    val newestFirst = thumbnails.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
    val admitted = admitThumbnails(database.sumOf { it.backupCost() }, newestFirst, budget)
    return AutoBackupSet(database + admitted.files, admitted.leftOutThumbnails)
}

/**
 * What the last automatic backup attempt left behind. Android reports a quota rejection through
 * `onQuotaExceeded` and no other transport failure at all, so [lastBackupAt] is the last attempt that
 * was not refused, not a confirmed upload. When [overQuota], it is the one before the refusal, or
 * null if none is recorded.
 */
data class AutoBackupMarker(
    val lastBackupAt: Instant?,
    val leftOutThumbnails: Int = 0,
    val overQuota: Boolean = false,
)

/** Carries the previous [AutoBackupMarker.lastBackupAt] along, for [writeOverQuotaMarker] to fall back to. */
fun writeAutoBackupMarker(
    filesDir: File,
    at: Instant,
    leftOutThumbnails: Int,
) {
    val previous = readMarkerValues(filesDir)?.get(MARKER_LAST_BACKUP_KEY)
    writeMarker(
        filesDir,
        buildString {
            append("$MARKER_LAST_BACKUP_KEY=${at.toEpochMilli()}\n")
            append("$MARKER_LEFT_OUT_KEY=$leftOutThumbnails\n")
            if (previous != null) append("$MARKER_PREVIOUS_BACKUP_KEY=$previous\n")
        },
    )
}

/**
 * Written over the marker [writeAutoBackupMarker] left moments before, from the same attempt's
 * measuring pass. The refused set never reached the account, so the time shown becomes the one
 * before it.
 */
fun writeOverQuotaMarker(filesDir: File) {
    val values = readMarkerValues(filesDir).orEmpty()
    val lastGood =
        if (values[MARKER_OVER_QUOTA_KEY] ==
            "true"
        ) {
            values[MARKER_LAST_BACKUP_KEY]
        } else {
            values[MARKER_PREVIOUS_BACKUP_KEY]
        }
    writeMarker(
        filesDir,
        buildString {
            append("$MARKER_OVER_QUOTA_KEY=true\n")
            if (lastGood != null) append("$MARKER_LAST_BACKUP_KEY=$lastGood\n")
        },
    )
}

fun clearAutoBackupMarker(filesDir: File) {
    File(filesDir, AUTO_BACKUP_MARKER_FILE).delete()
}

/** Null when absent or unreadable, which renders as "none recorded", never as a working backup. */
fun readAutoBackupMarker(filesDir: File): AutoBackupMarker? {
    val values = readMarkerValues(filesDir) ?: return null
    val overQuota = values[MARKER_OVER_QUOTA_KEY] == "true"
    val lastBackupAt = values[MARKER_LAST_BACKUP_KEY]?.toLongOrNull()?.let(Instant::ofEpochMilli)
    if (lastBackupAt == null && !overQuota) return null
    return AutoBackupMarker(
        lastBackupAt = lastBackupAt,
        leftOutThumbnails = if (overQuota) 0 else values[MARKER_LEFT_OUT_KEY]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        overQuota = overQuota,
    )
}

private fun readMarkerValues(filesDir: File): Map<String, String>? {
    val marker = File(filesDir, AUTO_BACKUP_MARKER_FILE)
    if (!marker.isFile) return null
    return try {
        marker
            .readLines()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1).trim()
            }.toMap()
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
