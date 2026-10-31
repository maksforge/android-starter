package app.starter.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class AutoBackupTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file(
        name: String,
        bytes: Int,
        modified: Long = 0,
    ): File =
        folder.newFile(name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(modified)
        }

    private val cost = AUTO_BACKUP_PER_FILE_OVERHEAD_BYTES + 10

    @Test
    fun `thumbnails are admitted under what the database leaves, skipping one too large`() {
        val big = file("big.jpg", 60)
        val small = file("small.jpg", 10)
        val set = admitThumbnails(coreBytes = cost, newestFirst = listOf(big, small), budget = 2 * cost)
        assertEquals(listOf(small), set.files)
        assertEquals(1, set.leftOutThumbnails)
    }

    @Test
    fun `each file is charged its tar overhead, not only its bytes`() {
        val database = file("app.db", 10)
        val thumbnails = listOf(file("a.jpg", 10), file("b.jpg", 10))
        val set =
            autoBackupFileSet(
                database,
                thumbnails,
                photos = emptyList(),
                deviceToDeviceTransfer = false,
                budget = 2 * cost,
            )
        assertEquals(2, set.files.size)
        assertEquals(1, set.leftOutThumbnails)
    }

    @Test
    fun `the budget is the transport's quota less the margin`() {
        assertEquals(25L * 1024 * 1024 - AUTO_BACKUP_QUOTA_MARGIN_BYTES, autoBackupBudget(25L * 1024 * 1024))
        assertEquals(0, autoBackupBudget(1))
    }

    @Test
    fun `the newest thumbnails win the budget`() {
        val database = file("app.db", 10)
        val old = file("old.jpg", 10, modified = 1_000)
        val new = file("new.jpg", 10, modified = 2_000)
        val set =
            autoBackupFileSet(
                database,
                listOf(old, new),
                photos = emptyList(),
                deviceToDeviceTransfer = false,
                budget = 2 * cost,
            )
        assertEquals(listOf(database, new), set.files)
        assertEquals(1, set.leftOutThumbnails)
    }

    @Test
    fun `a device-to-device transfer has no quota, so every thumbnail and photo goes`() {
        val database = file("app.db", 10)
        val thumbnail = file("t.jpg", 100)
        val photo = file("p.jpg", 100)
        val set =
            autoBackupFileSet(database, listOf(thumbnail), listOf(photo), deviceToDeviceTransfer = true, budget = 1)
        assertEquals(listOf(database, thumbnail, photo), set.files)
        assertEquals(0, set.leftOutThumbnails)
    }

    @Test
    fun `the marker round-trips, and an over-quota attempt keeps the last one that went through`() {
        val filesDir = folder.newFolder("files")
        assertNull(readAutoBackupMarker(filesDir))

        val accepted = Instant.ofEpochMilli(1_700_000_000_000)
        writeAutoBackupMarker(filesDir, accepted, leftOutThumbnails = 3)
        assertEquals(AutoBackupMarker(accepted, 3, overQuota = false), readAutoBackupMarker(filesDir))

        writeAutoBackupMarker(filesDir, Instant.ofEpochMilli(1_800_000_000_000), leftOutThumbnails = 0)
        writeOverQuotaMarker(filesDir)
        assertEquals(AutoBackupMarker(accepted, 0, overQuota = true), readAutoBackupMarker(filesDir))

        writeAutoBackupMarker(filesDir, Instant.ofEpochMilli(1_900_000_000_000), leftOutThumbnails = 0)
        writeOverQuotaMarker(filesDir)
        assertEquals(AutoBackupMarker(accepted, 0, overQuota = true), readAutoBackupMarker(filesDir))

        clearAutoBackupMarker(filesDir)
        assertNull(readAutoBackupMarker(filesDir))
        assertFalse(File(filesDir, "$AUTO_BACKUP_MARKER_FILE.part").exists())
    }

    @Test
    fun `an over-quota attempt with nothing earlier records no time`() {
        val filesDir = folder.newFolder("files")
        writeAutoBackupMarker(filesDir, Instant.ofEpochMilli(1_700_000_000_000), leftOutThumbnails = 0)
        writeOverQuotaMarker(filesDir)
        assertEquals(AutoBackupMarker(null, 0, overQuota = true), readAutoBackupMarker(filesDir))
    }

    @Test
    fun `an unreadable marker reads as none recorded, never as a backup`() {
        val filesDir = folder.newFolder("files")
        File(filesDir, AUTO_BACKUP_MARKER_FILE).writeText("garbage\n")
        assertNull(readAutoBackupMarker(filesDir))
    }
}
