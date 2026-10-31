package app.starter.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `thumbnails are admitted under what the database leaves, skipping one too large`() {
        val big = file("big.jpg", 60)
        val small = file("small.jpg", 10)
        val set = admitThumbnails(coreBytes = 30, newestFirst = listOf(big, small), budget = 50)
        assertEquals(listOf(small), set.files)
        assertEquals(1, set.leftOutThumbnails)
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
                budget = 25,
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
    fun `the marker round-trips, and an over-quota attempt replaces it`() {
        val filesDir = folder.newFolder("files")
        assertNull(readAutoBackupMarker(filesDir))

        writeAutoBackupMarker(filesDir, Instant.ofEpochMilli(1_700_000_000_000), leftOutThumbnails = 3)
        assertEquals(
            AutoBackupMarker(Instant.ofEpochMilli(1_700_000_000_000), 3, overQuota = false),
            readAutoBackupMarker(filesDir),
        )

        writeOverQuotaMarker(filesDir)
        val marker = readAutoBackupMarker(filesDir)!!
        assertTrue(marker.overQuota)
        assertNull(marker.lastBackupAt)

        clearAutoBackupMarker(filesDir)
        assertNull(readAutoBackupMarker(filesDir))
        assertFalse(File(filesDir, "$AUTO_BACKUP_MARKER_FILE.part").exists())
    }

    @Test
    fun `an unreadable marker reads as none recorded, never as a backup`() {
        val filesDir = folder.newFolder("files")
        File(filesDir, AUTO_BACKUP_MARKER_FILE).writeText("garbage\n")
        assertNull(readAutoBackupMarker(filesDir))
    }
}
