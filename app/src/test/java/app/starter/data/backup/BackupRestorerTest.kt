package app.starter.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupRestorerTest {
    @get:Rule val folder = TemporaryFolder()

    private fun archive(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private val manifest = backupJson.encodeToString(BackupManifest(schemaVersion = 1, createdAt = 1)).toByteArray()

    @Test
    fun `a restore swaps the database and media in whole, leaving no part files or sidecars`() =
        runBlocking {
            val filesDir = folder.newFolder("files")
            val databaseFile = File(folder.newFolder("databases"), "app.db").apply { writeText("old") }
            File(databaseFile.path + "-wal").writeText("old log")
            File(filesDir, "thumbnails").mkdirs()
            File(filesDir, "thumbnails/keep.jpg").writeText("kept")
            val zip =
                archive(
                    mapOf(
                        MANIFEST_ENTRY to manifest,
                        DATABASE_ENTRY to "new".toByteArray(),
                        "thumbnails/a.jpg" to "a".toByteArray(),
                    ),
                )

            val result =
                BackupRestorer(
                    databaseFile,
                    filesDir,
                    File(folder.root, "staging"),
                    supportedSchema = 1,
                    io = Dispatchers.Unconfined,
                ).restoreFrom { zip.inputStream() }

            assertEquals(RestoreResult.Restored(1), result)
            assertEquals("new", databaseFile.readText())
            assertFalse(File(databaseFile.path + "-wal").exists())
            assertEquals("a", File(filesDir, "thumbnails/a.jpg").readText())
            assertEquals("kept", File(filesDir, "thumbnails/keep.jpg").readText())
            val leftovers = (filesDir.walk() + databaseFile.parentFile!!.walk()).filter { it.name.endsWith(".part") }
            assertTrue(leftovers.toList().toString(), leftovers.none())
        }

    @Test
    fun `a newer archive is refused before anything live is touched`() =
        runBlocking {
            val databaseFile = File(folder.newFolder("databases"), "app.db").apply { writeText("old") }
            val newer = backupJson.encodeToString(BackupManifest(schemaVersion = 9, createdAt = 1)).toByteArray()
            val zip = archive(mapOf(MANIFEST_ENTRY to newer, DATABASE_ENTRY to "new".toByteArray()))

            val result =
                BackupRestorer(
                    databaseFile,
                    folder.newFolder("files"),
                    File(folder.root, "staging"),
                    supportedSchema = 1,
                ).restoreFrom { zip.inputStream() }

            assertEquals(RestoreResult.TooNew(9, 1), result)
            assertEquals("old", databaseFile.readText())
        }
}
