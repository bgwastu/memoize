package net.wastu.memoize.data

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomRawQuery
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "notes", primaryKeys = ["account", "id"],
    indices = [Index(value = ["account", "createdAt", "id"]), Index(value = ["account", "remoteName"], unique = true)],
)
internal data class NoteEntity(
    val account: String,
    val id: String,
    val content: String = "",
    val searchContent: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val state: String = "NORMAL",
    val visibility: String = "PRIVATE",
    val remoteName: String? = null,
    val revision: Long = 0,
    val syncedRevision: Long = 0,
    val baseJson: String? = null,
    val sentJson: String? = null,
    val sentRevision: Long? = null,
    val deleteAfter: Long? = null,
    val error: String? = null,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val scroll: Int = 0,
    val attachmentsJson: String = "[]",
    val tagsJson: String = "[]",
    @ColumnInfo(defaultValue = "NULL") val problemCode: String? = null,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val locationJson: String? = null,
)

/** A Room projection keeps list reads independent of potentially large sync snapshots. */
internal data class NotePreviewRow(
    val account: String,
    val id: String,
    val content: String,
    val searchContent: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: String,
    val remoteName: String?,
    val error: String?,
    val attachmentsJson: String,
    val tagsJson: String,
    val problemCode: String?,
    val pinned: Boolean = false,
    val locationJson: String? = null,
)

@Entity(tableName = "pending_operations", primaryKeys = ["account", "noteId"])
internal data class PendingEntity(
    val account: String,
    val noteId: String,
    val revision: Long,
    val kind: String = "SAVE",
    val attempts: Int = 0,
    val retryAt: Long? = null,
    val error: String? = null,
)

@Entity(tableName = "recovery_revisions", primaryKeys = ["account", "id"], indices = [Index(value = ["account", "noteId"])])
internal data class RecoveryEntity(
    val account: String,
    val id: String,
    val noteId: String,
    val snapshotJson: String,
    val reason: String,
    val savedAt: Long,
)

@Entity(tableName = "metadata", primaryKeys = ["account", "key"])
internal data class MetadataEntity(val account: String, val key: String, val value: String)

@Entity(tableName = "attachment_cleanup", primaryKeys = ["account", "id"])
internal data class AttachmentCleanupEntity(
    val account: String, val id: String, val attachmentJson: String,
    val noteId: String?, val deleteAfter: Long, val attempts: Int = 0, val error: String? = null,
)

@Dao
internal interface NoteDao {
    @Query("SELECT * FROM notes WHERE account = :account AND id = :id")
    suspend fun get(account: String, id: String): NoteEntity?
    @Query("SELECT * FROM notes WHERE account = :account AND remoteName = :name")
    suspend fun remote(account: String, name: String): NoteEntity?
    @Query("SELECT * FROM notes WHERE account = :account ORDER BY createdAt DESC, id ASC")
    suspend fun all(account: String): List<NoteEntity>
    @Query("SELECT account, id, content, searchContent, createdAt, updatedAt, state, remoteName, error, attachmentsJson, tagsJson, problemCode, pinned, locationJson FROM notes WHERE account = :account AND deleteAfter IS NULL AND state = :state ORDER BY pinned DESC, createdAt DESC, id ASC")
    suspend fun previews(account: String, state: String): List<NotePreviewRow>
    @Query("SELECT tagsJson FROM notes WHERE account = :account AND deleteAfter IS NULL")
    suspend fun tagRows(account: String): List<String>
    @Query("SELECT COALESCE(SUM(revision), 0) + COALESCE(SUM(updatedAt), 0) + COUNT(*) FROM notes WHERE account = :account")
    fun observeChanges(account: String): Flow<Long>
    @Query("SELECT * FROM notes WHERE account = :account AND id = :id")
    fun observeNote(account: String, id: String): Flow<NoteEntity?>
    @Query("SELECT * FROM notes")
    suspend fun allAccounts(): List<NoteEntity>
    @Query("SELECT * FROM recovery_revisions")
    suspend fun allRecovery(): List<RecoveryEntity>
    @Query("SELECT * FROM attachment_cleanup")
    suspend fun allCleanup(): List<AttachmentCleanupEntity>
    @RawQuery
    suspend fun queryPreviews(query: RoomRawQuery): List<NotePreviewRow>
    @Upsert suspend fun put(note: NoteEntity)
    @Query("DELETE FROM notes WHERE account = :account AND id = :id")
    suspend fun delete(account: String, id: String)
    @Query("SELECT * FROM pending_operations WHERE account = :account ORDER BY noteId ASC")
    suspend fun pending(account: String): List<PendingEntity>
    @Upsert suspend fun putPending(operation: PendingEntity)
    @Query("DELETE FROM pending_operations WHERE account = :account AND noteId = :id")
    suspend fun deletePending(account: String, id: String)
    @Upsert suspend fun putRecovery(recovery: RecoveryEntity)
    @Query("SELECT * FROM recovery_revisions WHERE account = :account AND noteId = :id ORDER BY savedAt DESC, id ASC")
    suspend fun recovery(account: String, id: String): List<RecoveryEntity>
    @Upsert suspend fun putMetadata(value: MetadataEntity)
    @Query("SELECT value FROM metadata WHERE account = :account AND `key` = :key")
    suspend fun metadata(account: String, key: String): String?
    @Upsert suspend fun putCleanup(value: AttachmentCleanupEntity)
    @Query("SELECT * FROM attachment_cleanup WHERE account = :account ORDER BY deleteAfter ASC")
    suspend fun cleanup(account: String): List<AttachmentCleanupEntity>
    @Query("DELETE FROM attachment_cleanup WHERE account = :account AND id = :id")
    suspend fun deleteCleanup(account: String, id: String)
}

@Database(
    entities = [NoteEntity::class, PendingEntity::class, RecoveryEntity::class, MetadataEntity::class, AttachmentCleanupEntity::class],
    version = 4, exportSchema = true,
)
internal abstract class NoteDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        fun open(context: Context, name: String): NoteDatabase = Room.databaseBuilder<NoteDatabase>(context, name)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            // Intentionally no destructive migration fallback. Upgrade schemas explicitly.
            .addMigrations(
                object : Migration(1, 2) {
                    override fun migrate(connection: SQLiteConnection) {
                        connection.exec("ALTER TABLE notes ADD COLUMN problemCode TEXT DEFAULT NULL")
                        // Frozen version-1 representation; future UI wording must not change migration.
                        connection.prepare("UPDATE notes SET problemCode = 'CONFLICT', error = NULL WHERE error = ?").use {
                            it.bindText(1, "A server edit conflicts with this note. Both versions are preserved. Choose a recovery version to continue.")
                            it.step()
                        }
                    }
                },
                object : Migration(2, 3) {
                    override fun migrate(connection: SQLiteConnection) {
                        connection.exec("ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                    }
                },
                object : Migration(3, 4) {
                    override fun migrate(connection: SQLiteConnection) {
                        connection.exec("ALTER TABLE notes ADD COLUMN locationJson TEXT DEFAULT NULL")
                    }
                }
            )
            .addCallback(object : Callback() {
                override fun onCreate(connection: SQLiteConnection) {
                    connection.exec("CREATE VIRTUAL TABLE note_search USING fts5(searchContent, content='notes', content_rowid='rowid', tokenize='trigram case_sensitive 1')")
                    connection.exec("CREATE TRIGGER notes_search_insert AFTER INSERT ON notes BEGIN INSERT INTO note_search(rowid, searchContent) VALUES (new.rowid, new.searchContent); END")
                    connection.exec("CREATE TRIGGER notes_search_delete AFTER DELETE ON notes BEGIN INSERT INTO note_search(note_search, rowid, searchContent) VALUES ('delete', old.rowid, old.searchContent); END")
                    connection.exec("CREATE TRIGGER notes_search_update AFTER UPDATE OF searchContent ON notes BEGIN INSERT INTO note_search(note_search, rowid, searchContent) VALUES ('delete', old.rowid, old.searchContent); INSERT INTO note_search(rowid, searchContent) VALUES (new.rowid, new.searchContent); END")
                }
                override fun onOpen(connection: SQLiteConnection) {
                    connection.exec("PRAGMA synchronous=FULL")
                    // Fail early if the shipped SQLite driver lacks the required index feature.
                    connection.prepare("SELECT count(*) FROM note_search WHERE note_search MATCH 'probe'").use { it.step() }
                }
            }).build()
    }
}

private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }
