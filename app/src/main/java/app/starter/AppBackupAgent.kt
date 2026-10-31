package app.starter

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.Build
import android.os.ParcelFileDescriptor
import app.starter.data.APP_DATABASE_FILE
import app.starter.data.backup.autoBackupBudget
import app.starter.data.backup.autoBackupFileSet
import app.starter.data.backup.checkpointDatabaseTo
import app.starter.data.backup.clearAutoBackupMarker
import app.starter.data.backup.installDatabase
import app.starter.data.backup.mediaFilesIn
import app.starter.data.backup.writeAutoBackupMarker
import app.starter.data.backup.writeOverQuotaMarker
import app.starter.media.MediaKind
import java.io.File
import java.time.Instant

/** Where the checkpointed copy is staged, relative to `filesDir`. */
private const val STAGING_PATH = "auto-backup-staged.db"

/**
 * Android Auto Backup, taking control of its own file set (ADR-0005).
 *
 * **Why an agent rather than `backup_rules.xml`.** Static include/exclude XML cannot checkpoint the
 * write-ahead log, and without that the platform is eligible to copy the live database and its
 * `-wal` sidecar *mid-write*: a backup that appears to work and restores corrupt. There is no way to
 * express "flush first" in the XML, so the XML has to go.
 *
 * Registered with `android:fullBackupOnly="true"`. Declaring an agent without it puts the app on the
 * key/value path instead, where [onBackup] below — a deliberate no-op — is what actually runs, and
 * the app silently backs up nothing.
 *
 * **This class holds no state and reaches for nothing.** When the system starts the process for a
 * backup it binds the base `android.app.Application`, **not** [MainApplication]: `app.container` is
 * not there to be had, and a cast to [MainApplication] would be a `ClassCastException` in exactly
 * the conditions nobody tests under. What an agent may use is [android.content.Context] paths —
 * `filesDir`, `getDatabasePath` — which a plain `Application` provides.
 *
 * It also runs **without `MainApplication.onCreate`**, so the wipe guard has not run and no database
 * is open. That is why the checkpoint below opens its own connection rather than asking Room.
 */
class AppBackupAgent : BackupAgent() {
    /**
     * Back up the checkpointed database and as many thumbnails as fit, newest first.
     *
     * Auto Backup has a per-app quota and drops the **whole** backup when it is exceeded, so the
     * thumbnails are admitted under a budget taken from the quota the transport reports rather than
     * all of them: a pile of old thumbnails must not take the database down with it. Full-size photos
     * go only on a device-to-device transfer, which has no quota. The in-app export is where
     * "everything" lives.
     *
     * The marker records the attempt for the Backup screen; [onQuotaExceeded] overwrites it when the
     * set was rejected anyway. A device-to-device transfer never reaches the Google account, so it
     * leaves the marker alone.
     */
    override fun onFullBackup(data: FullBackupDataOutput) {
        val staged = File(filesDir, STAGING_PATH)
        try {
            val databaseFile = getDatabasePath(APP_DATABASE_FILE)
            if (databaseFile.isFile) checkpointDatabaseTo(databaseFile, staged)
            val deviceToDeviceTransfer = data.isDeviceToDeviceTransfer()
            val set =
                autoBackupFileSet(
                    stagedDatabase = staged,
                    thumbnails = mediaFilesIn(filesDir, MediaKind.Thumbnail),
                    photos = mediaFilesIn(filesDir, MediaKind.Photo),
                    deviceToDeviceTransfer = deviceToDeviceTransfer,
                    budget = autoBackupBudget(data.quota),
                )
            set.files.forEach { fullBackupFile(it, data) }
            if (!deviceToDeviceTransfer) writeAutoBackupMarker(filesDir, Instant.now(), set.leftOutThumbnails)
        } finally {
            // It exists for the duration of the `fullBackupFile` call above, which consumes the
            // bytes synchronously, so nothing is waiting on it afterwards.
            staged.delete()
        }
    }

    override fun onQuotaExceeded(
        backupDataBytes: Long,
        quotaBytes: Long,
    ) {
        writeOverQuotaMarker(filesDir)
    }

    /**
     * The staged copy arrives back under its own name and has to become the live database.
     *
     * Done here rather than by pointing the backup at the real path, because a restore that wrote
     * straight over `databases/app.db` would be doing so in a process where Room may already have
     * opened it.
     */
    override fun onRestoreFinished() {
        clearAutoBackupMarker(filesDir)
        val staged = File(filesDir, STAGING_PATH)
        if (!staged.isFile) return
        installDatabase(staged, getDatabasePath(APP_DATABASE_FILE))
        staged.delete()
    }

    private fun FullBackupDataOutput.isDeviceToDeviceTransfer(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && (transportFlags and FLAG_DEVICE_TO_DEVICE_TRANSFER) != 0

    /** Key/value backup. Never called while `fullBackupOnly="true"`, and a no-op if it ever is. */
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?,
    ) = Unit
}
