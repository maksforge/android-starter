package app.starter.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
    fun `a restore replaces the database and merges media, leaving no part files or sidecars`() =
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

    private fun restorer(
        databaseFile: File,
        filesDir: File,
    ) = BackupRestorer(
        databaseFile,
        filesDir,
        File(folder.root, "staging"),
        supportedSchema = 1,
        io = Dispatchers.Unconfined,
    )

    @Test
    fun `a database restore that fails mid-copy leaves the live database and its log alone`() {
        val filesDir = folder.newFolder("files")
        val databaseFile = File(folder.newFolder("databases"), "app.db").apply { writeText("old") }
        File(databaseFile.path + "-wal").writeText("old log")
        File(databaseFile.path + ".part").mkdirs()
        File(databaseFile.path + ".part/blocker").writeText("")
        val zip = archive(mapOf(MANIFEST_ENTRY to manifest, DATABASE_ENTRY to "new".toByteArray()))

        assertThrows(IOException::class.java) {
            runBlocking { restorer(databaseFile, filesDir).restoreFrom { zip.inputStream() } }
        }

        assertEquals("old", databaseFile.readText())
        assertEquals("old log", File(databaseFile.path + "-wal").readText())
    }

    @Test
    fun `a media restore that fails mid-copy leaves the live file alone`() {
        val filesDir = folder.newFolder("files")
        val databaseFile = File(folder.newFolder("databases"), "app.db")
        val live = File(filesDir, "thumbnails/a.jpg").apply { parentFile!!.mkdirs() }.apply { writeText("old") }
        File(live.path + ".part").mkdirs()
        File(live.path + ".part/blocker").writeText("")
        val zip = archive(mapOf(MANIFEST_ENTRY to manifest, "thumbnails/a.jpg" to "new".toByteArray()))

        assertThrows(IOException::class.java) {
            runBlocking { restorer(databaseFile, filesDir).restoreFrom { zip.inputStream() } }
        }

        assertEquals("old", live.readText())
    }

    @Test
    fun `a restore sweeps the part files an interrupted one left behind`() =
        runBlocking {
            val filesDir = folder.newFolder("files")
            val databaseFile = File(folder.newFolder("databases"), "app.db").apply { writeText("old") }
            val staleDatabase = File(databaseFile.path + ".part").apply { writeText("half") }
            val staleThumbnail =
                File(
                    filesDir,
                    "thumbnails/b.jpg.part",
                ).apply { parentFile!!.mkdirs() }.apply { writeText("half") }
            val zip = archive(mapOf(MANIFEST_ENTRY to manifest))

            restorer(databaseFile, filesDir).restoreFrom { zip.inputStream() }

            assertFalse(staleDatabase.exists())
            assertFalse(staleThumbnail.exists())
        }

    @Test
    fun `an export skips part files, and a restore keeps each file's modification time`() =
        runBlocking {
            val source = folder.newFolder("source")
            File(source, "thumbnails").mkdirs()
            File(source, "thumbnails/a.jpg").apply { writeText("a") }.setLastModified(1_600_000_000_000)
            File(source, "thumbnails/b.jpg.part").writeText("half")
            val exported =
                BackupExporter(
                    databaseFile = File(source, "app.db"),
                    filesDir = source,
                    scratchDir = folder.newFolder("scratch"),
                    schemaVersion = 1,
                    io = Dispatchers.Unconfined,
                    checkpoint = { _, staged -> staged.writeText("db") },
                ).exportTo(File(folder.root, "out/backup.zip"), BackupScope.Records)

            val filesDir = folder.newFolder("files")
            val databaseFile = File(folder.newFolder("databases"), "app.db")
            restorer(databaseFile, filesDir).restoreFrom { exported.inputStream() }

            assertEquals(1_600_000_000_000, File(filesDir, "thumbnails/a.jpg").lastModified())
            assertFalse(File(filesDir, "thumbnails/b.jpg.part").exists())
            assertFalse(File(filesDir, "thumbnails/b.jpg").exists())
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
