package net.wastu.memoize.data

import android.app.Application
import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Open a historical shipped database through the public repository, never the DAO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConflictMigrationTest {
    @Test
    fun versionOnePausedWorkMigratesWithoutPublishingOrLosingVersionsAndOriginals() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "legacy-conflict-${UUID.randomUUID()}.db"
        val account = "legacy-account"
        val namespace = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
        val original = File(context.filesDir, "accounts/$namespace/originals/legacy-file").apply {
            parentFile!!.mkdirs(); writeText("Historical original bytes 📝")
        }
        val attachments = JSONArray().put(JSONObject().put("id", "legacy-file").put("remoteName", "attachments/legacy-file")
            .put("filename", "legacy.txt").put("mimeType", "text/plain").put("size", original.length())
            .put("localPath", original.absolutePath).put("uploaded", true))
        fun body(content: String): String = JSONObject().put("remoteName", "memos/legacy").put("content", content)
            .put("createdAt", 1).put("updatedAt", 2).put("state", "NORMAL").put("visibility", "PRIVATE")
            .put("attachments", attachments).toString()
        val historicalMessage = "A server edit conflicts with this note. Both versions are preserved. Choose a recovery version to continue."
        val schema = JSONObject(requireNotNull(javaClass.getResource("/net.wastu.memoize.data.NoteDatabase/1.json")).readText())
            .getJSONObject("database")
        BundledSQLiteDriver().open(context.getDatabasePath(name).absolutePath).use { database ->
            fun sql(statement: String) = database.prepare(statement).use { it.step(); Unit }
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                sql(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indexes = entity.optJSONArray("indices") ?: JSONArray()
                for (item in 0 until indexes.length()) sql(indexes.getJSONObject(item).getString("createSql")
                    .replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) sql(setup.getString(index))
            sql("CREATE VIRTUAL TABLE note_search USING fts5(searchContent, content='notes', content_rowid='rowid', tokenize='trigram case_sensitive 1')")
            sql("INSERT INTO notes VALUES ('$account', 'legacy', 'Local preserved source', 'local preserved source', 1, 2, 'NORMAL', 'PRIVATE', 'memos/legacy', 4, 1, NULL, NULL, NULL, NULL, NULL, 3, 8, 17, '[]', '[\"work\"]')")
            database.prepare("UPDATE notes SET baseJson = ?, sentJson = ?, sentRevision = 3, error = ?, attachmentsJson = ? WHERE id = 'legacy'").use {
                it.bindText(1, body("Verified server base")); it.bindText(2, body("In-flight preserved source"))
                it.bindText(3, historicalMessage); it.bindText(4, attachments.toString()); it.step()
            }
            database.prepare("INSERT INTO recovery_revisions VALUES (?, 'recovery-v1', 'legacy', ?, 'Conflicting server version', 2)").use {
                it.bindText(1, account); it.bindText(2, body("Verified server base")); it.step()
            }
            database.prepare("INSERT INTO pending_operations VALUES (?, 'legacy', 4, 'SAVE', 2, ?, ?)").use {
                it.bindText(1, account); it.bindLong(2, Long.MAX_VALUE); it.bindText(3, historicalMessage); it.step()
            }
            sql("INSERT INTO note_search(note_search) VALUES ('rebuild')")
            sql("PRAGMA user_version = 1")
        }
        val notes = NoteRepository.open(context, account, name)
        try {
            val note = requireNotNull(notes.get("legacy"))
            assertEquals(NoteProblem.CONFLICT, note.problem)
            assertEquals("Local preserved source", note.content)
            assertEquals("Verified server base", note.base?.content)
            assertEquals("In-flight preserved source", note.sent?.content)
            assertEquals(3L, note.sentRevision)
            assertEquals(3, note.selectionStart); assertEquals(8, note.selectionEnd); assertEquals(17, note.scroll)
            assertEquals("Historical original bytes 📝", File(requireNotNull(note.attachments.single().localPath)).readText())
            assertEquals("legacy", notes.search(NoteFilter(query = "preserved")).single().note.id)
            assertTrue(notes.search().single().note.hasConflict)
            notes.retry()
            assertTrue(requireNotNull(notes.get("legacy")).hasConflict)
            assertEquals(Long.MAX_VALUE, notes.pending().single().retryAt)
            assertEquals(2, notes.pending().single().attempts)
            assertEquals(null, notes.beginSendIfUnchanged(note.id, note.revision))
            notes.recordFailure(note.id, "Later network wording must not unpause a conflict", 1, 9)
            assertTrue(requireNotNull(notes.get("legacy")).hasConflict)
            val recovery = notes.recoveries().single()
            assertEquals("Verified server base", recovery.snapshot.content)
            val chosen = notes.restoreRecovery(note.id, recovery.id)
            assertFalse(chosen.hasConflict)
            assertEquals("Verified server base", chosen.content)
            assertTrue(chosen.isDirty)
            assertEquals(null, notes.pending().single().retryAt)
            assertEquals("Historical original bytes 📝", File(requireNotNull(chosen.attachments.single().localPath)).readText())
        } finally { notes.close(); context.deleteDatabase(name); original.delete() }
    }
}
