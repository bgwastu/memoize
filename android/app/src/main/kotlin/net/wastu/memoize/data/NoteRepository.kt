package net.wastu.memoize.data

import android.content.Context
import androidx.room.RoomRawQuery
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

data class AttachmentCleanup(val attachment: NoteAttachment, val noteId: String?, val deleteAfter: Long,
    val attempts: Int = 0, val retryAt: Long? = null, val error: String? = null)

/** UI, widget and workers see only committed data through this account-scoped boundary. */
class NoteRepository private constructor(
    private val database: NoteDatabase,
    val account: String,
    val originalsDirectory: File,
    val cacheDirectory: File,
    private val clock: () -> Long,
) : Closeable {
    private val dao = database.notes()
    val changes: Flow<Unit> = dao.observeChanges(account).map { }

    /** Canonical complete-download path; remote identities cannot escape their account directory. */
    fun cachedAttachmentFile(id: String): File = File(cacheDirectory, accountHash(id))

    internal fun originalAttachmentFile(id: String): File = File(originalsDirectory, accountHash(id))

    /** Make any complete cached copy durable before it becomes the sole pending/recovery original. */
    suspend fun keepAttachmentOriginal(attachment: NoteAttachment): NoteAttachment {
        val kept = promoteCachedAttachment(attachment)
        kept.localPath?.let { recordAttachmentOriginal(attachment.id, it) }
        return kept
    }

    /** Account views share one database; old editor flushes remain bound to their original namespace. */
    fun forAccount(account: String): NoteRepository {
        require(account.isNotBlank())
        if (account == this.account) return this
        val namespace = accountHash(account)
        val originalAccounts = requireNotNull(requireNotNull(originalsDirectory.parentFile).parentFile)
        val cachedAccounts = requireNotNull(requireNotNull(cacheDirectory.parentFile).parentFile)
        return NoteRepository(database, account,
            File(originalAccounts, "$namespace/originals").apply { mkdirs() },
            File(cachedAccounts, "$namespace/attachments").apply { mkdirs() }, clock)
    }

    suspend fun get(id: String): Note? = dao.get(account, id)?.note()
    fun observeNote(id: String): Flow<Note?> = dao.observeNote(account, id).map { it?.note() }
    suspend fun all(): List<Note> = dao.all(account).map { it.note() }
    fun observe(filter: NoteFilter = NoteFilter()): Flow<List<NoteResult>> =
        dao.observeChanges(account).map { search(filter) }

    suspend fun search(filter: NoteFilter = NoteFilter()): List<NoteResult> = withContext(Dispatchers.Default) {
        val queryContext = currentCoroutineContext()
        val query = LiteralSearch.prepare(filter.query)
        val indexQuery = query.indexExpression
        val rows = if (indexQuery == null) dao.previews(account, filter.state.name) else dao.queryPreviews(RoomRawQuery(
            "SELECT notes.account, notes.id, notes.content, notes.searchContent, notes.createdAt, notes.updatedAt, notes.state, notes.visibility, notes.remoteName, notes.error, notes.attachmentsJson, notes.tagsJson, notes.problemCode, notes.pinned, notes.locationJson FROM notes JOIN note_search ON notes.rowid = note_search.rowid WHERE notes.account = ? AND notes.deleteAfter IS NULL AND notes.state = ? AND note_search MATCH ? ORDER BY notes.pinned DESC, notes.createdAt DESC, notes.id ASC",
        ) { statement -> statement.bindText(1, account); statement.bindText(2, filter.state.name); statement.bindText(3, indexQuery) })
        val results = rows.asSequence().onEach { queryContext.ensureActive() }
            .map { entity -> entity to entity.preview() }
            .filter { (_, note) -> !note.isBlankDraft }
            .filter { (_, note) -> filter.tag == null || note.tags.any { it == filter.tag || it.startsWith("${filter.tag}/") } }
            .filter { (_, note) -> when (filter.attachments) {
                AttachmentFilter.ANY -> true
                AttachmentFilter.IMAGES -> note.attachments.any { it.isImage }
                AttachmentFilter.FILES -> note.attachments.any { !it.isImage }
            } }
            .map { (entity, note) -> LiteralSearch.result(note, query, entity.searchContent) }
            .filter { filter.query.isEmpty() || it.matches.isNotEmpty() }
            .toList()
        val comparator = compareByDescending<NoteResult> { it.note.pinned }
        when (filter.sort) {
            NoteSort.NEWEST -> results.sortedWith(comparator.thenByDescending { it.note.createdAt }.thenBy { it.note.id })
            NoteSort.OLDEST -> results.sortedWith(comparator.thenBy { it.note.createdAt }.thenBy { it.note.id })
            NoteSort.UPDATED -> results.sortedWith(comparator.thenByDescending { it.note.updatedAt }
                .thenByDescending { it.note.createdAt }.thenBy { it.note.id })
        }
    }

    suspend fun tags(): List<String> = dao.tagRows(account)
        .flatMap(SnapshotCodec::tags).distinct().sorted()

    suspend fun create(id: String = UUID.randomUUID().toString().lowercase(), createdAt: Long = clock()): Note = transaction {
        require(dao.get(account, id) == null) { "A note with this identity already exists" }
        val note = NoteEntity(account, id, createdAt = createdAt, updatedAt = createdAt)
        dao.put(note)
        note.note()
    }

    suspend fun saveEdit(id: String, edit: EditSnapshot): Note = transaction {
        val current = dao.get(account, id) ?: return@transaction preserveDeletedEdit(id, edit)
        check(current.deleteAfter == null) { "This note is pending deletion. Undo deletion before editing." }
        val changed = current.content != edit.content
        val incomingChanged = edit.origin != null && edit.origin.content != current.content && changed
        val message = if (changed) null else current.error
        if (incomingChanged) {
            saveRecovery(current, current.note().snapshot(), "Server version received while editing")
            saveRecovery(current, edit.origin.copy(content = edit.content), "Local edit during server update")
        }
        val saved = current.copy(
            content = edit.content,
            searchContent = if (changed) LiteralSearch.folded(edit.content) else current.searchContent,
            tagsJson = if (changed) SnapshotCodec.tags(MemosTags.extract(edit.content)) else current.tagsJson,
            revision = current.revision + if (changed) 1 else 0,
            updatedAt = if (changed) clock() else current.updatedAt,
            selectionStart = edit.selectionStart.coerceIn(0, edit.content.length),
            selectionEnd = edit.selectionEnd.coerceIn(0, edit.content.length), scroll = maxOf(0, edit.scroll),
            error = message, problemCode = if (incomingChanged) NoteProblem.CONFLICT.code else current.problemCode,
        )
        dao.put(saved)
        if (changed) {
            queue(saved)
        }
        saved.note()
    }

    suspend fun setState(id: String, state: NoteState): Note = transaction {
        val current = requireNote(id)
        val saved = current.copy(state = state.name, revision = current.revision + 1, updatedAt = clock(), error = null)
        dao.put(saved); queue(saved); saved.note()
    }

    suspend fun setPinned(id: String, pinned: Boolean): Note = transaction {
        val current = requireNote(id)
        if (current.pinned == pinned) return@transaction current.note()
        val saved = current.copy(pinned = pinned, revision = current.revision + 1, updatedAt = clock())
        dao.put(saved); queue(saved); saved.note()
    }

    suspend fun setLocation(id: String, location: NoteLocation?): Note = transaction {
        val current = requireNote(id)
        if (SnapshotCodec.location(current.locationJson) == location) return@transaction current.note()
        val saved = current.copy(locationJson = SnapshotCodec.location(location), revision = current.revision + 1, updatedAt = clock())
        dao.put(saved); queue(saved); saved.note()
    }

    suspend fun setVisibility(id: String, visibility: NoteVisibility): Note = transaction {
        val current = requireNote(id)
        if (current.visibility == visibility.name) return@transaction current.note()
        val saved = current.copy(visibility = visibility.name, revision = current.revision + 1, updatedAt = clock())
        dao.put(saved); queue(saved); saved.note()
    }

    suspend fun markDeleted(id: String): Long = transaction {
        val current = requireNote(id)
        val deadline = clock() + 5_000
        val snapshot = durableSnapshot(current.note().snapshot())
        saveRecovery(current, snapshot, "Deleted note")
        val saved = current.copy(deleteAfter = deadline, attachmentsJson = SnapshotCodec.attachments(snapshot.attachments),
            error = null)
        dao.put(saved)
        queue(saved)
        deadline
    }

    suspend fun undoDelete(id: String): Boolean = transaction {
        val current = dao.get(account, id) ?: return@transaction false
        val deadline = current.deleteAfter ?: return@transaction false
        if (clock() >= deadline) return@transaction false
        val saved = current.copy(deleteAfter = null)
        dao.put(saved)
        if (saved.note().isDirty) queue(saved) else dao.deletePending(account, id)
        true
    }

    /** A selection commits together, including its sync queue and recovery copies. */
    suspend fun setState(ids: Set<String>, state: NoteState): Int = transaction {
        val selected = ids.map { requireNote(it) }
        check(selected.all { it.deleteAfter == null }) { "A selected note is no longer available." }
        val changed = selected.filter { it.state != state.name }
        changed.forEach { current ->
            val saved = current.copy(state = state.name, revision = current.revision + 1, updatedAt = clock(), error = null)
            dao.put(saved); queue(saved)
        }
        changed.size
    }

    suspend fun markDeleted(ids: Set<String>): Long = transaction {
        val selected = ids.map { requireNote(it) }
        check(selected.all { it.deleteAfter == null }) { "A selected note is no longer available." }
        val copies = selected.map { it to durableSnapshot(it.note().snapshot()) }
        copies.forEach { (note, snapshot) -> saveRecovery(note, snapshot, "Deleted note") }
        val deadline = clock() + 5_000
        copies.forEach { (note, snapshot) ->
            val saved = note.copy(deleteAfter = deadline, attachmentsJson = SnapshotCodec.attachments(snapshot.attachments), error = null)
            dao.put(saved); queue(saved)
        }
        deadline
    }

    suspend fun undoDelete(ids: Set<String>, deadline: Long): Boolean = transaction {
        if (clock() >= deadline) return@transaction false
        val selected = ids.map { dao.get(account, it) ?: return@transaction false }
        if (selected.any { it.deleteAfter != deadline }) return@transaction false
        selected.forEach { note ->
            val saved = note.copy(deleteAfter = null)
            dao.put(saved)
            if (saved.note().isDirty) queue(saved) else dao.deletePending(account, saved.id)
        }
        true
    }

    suspend fun discardBlankDraft(id: String): Boolean = transaction {
        val current = dao.get(account, id) ?: return@transaction false
        if (!current.note().isBlankDraft) return@transaction false
        dao.delete(account, id); dao.deletePending(account, id); true
    }

    suspend fun pending(): List<PendingOperation> = dao.pending(account).map {
        PendingOperation(it.noteId, it.revision, it.kind, it.attempts, it.retryAt,
            if (dao.get(account, it.noteId)?.hasConflict == true) CONFLICT_MESSAGE else it.error)
    }

    /** Keep the exact sent source recoverable across process death and later local edits. */
    suspend fun beginSend(id: String): Note = transaction {
        val current = requireNote(id)
        val snapshot = current.note().snapshot()
        saveRecovery(current, snapshot, "In-flight revision ${current.revision}")
        val sent = current.copy(sentJson = SnapshotCodec.snapshot(snapshot), sentRevision = current.revision)
        dao.put(sent); sent.note()
    }

    /** Transfers may race with typing or file removal; capture only a fully prepared unchanged revision. */
    suspend fun beginSendIfUnchanged(id: String, expectedRevision: Long): Note? = transaction {
        val current = dao.get(account, id) ?: return@transaction null
        if (current.revision != expectedRevision || current.deleteAfter != null || current.hasConflict ||
            current.note().attachments.any { !it.uploaded || it.remoteName == null }) return@transaction null
        beginSend(id)
    }

    /** A late response cannot overwrite or acknowledge edits made after sentRevision. */
    suspend fun acknowledge(id: String, sentRevision: Long, remote: MemoSnapshot): Note = transaction {
        val current = requireNote(id)
        if (current.hasConflict) return@transaction current.note()
        require(sentRevision <= current.revision) { "Acknowledgment is newer than local work" }
        val sent = current.sentJson?.let(::readSnapshot)
        if (current.sentRevision != sentRevision || sent == null || !sent.sameBody(remote)) {
            return@transaction current.note()
        }
        val remoteAttachments = remote.attachments.associateBy { it.id }
        val attachments = readAttachments(current.attachmentsJson).map { local ->
            remoteAttachments[local.id]?.copy(localPath = local.localPath, uploaded = true) ?: local
        }
        val saved = current.copy(
            remoteName = remote.remoteName, syncedRevision = maxOf(current.syncedRevision, sentRevision),
            baseJson = SnapshotCodec.snapshot(remote), sentJson = null, sentRevision = null,
            attachmentsJson = SnapshotCodec.attachments(attachments), error = null,
            pinned = remote.pinned, locationJson = SnapshotCodec.location(remote.location),
        )
        dao.put(saved)
        if (saved.revision > saved.syncedRevision || saved.deleteAfter != null) queue(saved) else dao.deletePending(account, id)
        saved.note()
    }

    /** Import only clean remote work. Dirty disagreements are preserved and paused for recovery. */
    suspend fun applyRemote(remote: MemoSnapshot): Note = transaction {
        val name = requireNotNull(remote.remoteName)
        val callerId = name.substringAfterLast('/')
        val current = dao.remote(account, name) ?: dao.get(account, callerId)
        if (current != null) {
            val note = current.note()
            if (note.hasConflict) {
                if (note.base?.sameBody(remote) != true) {
                    saveRecovery(current, remote, "Server version received while conflict is paused")
                    val paused = current.copy(remoteName = name, baseJson = SnapshotCodec.snapshot(remote))
                    dao.put(paused)
                    return@transaction paused.note()
                }
                if (current.remoteName != name) {
                    val paused = current.copy(remoteName = name)
                    dao.put(paused)
                    return@transaction paused.note()
                }
                return@transaction note
            }
            if (note.base == null && note.sent != null && note.sent.state == NoteState.ARCHIVED && remote.state == NoteState.NORMAL &&
                note.sent.copy(state = remote.state).sameBody(remote)) {
                // Creation succeeded but the follow-up offline archive was interrupted.
                val partial = current.copy(remoteName = name, baseJson = SnapshotCodec.snapshot(remote), sentJson = null, sentRevision = null)
                dao.put(partial)
                return@transaction partial.note()
            }
            if (note.sent != null && note.sentRevision != null && note.sent.sameBody(remote)) {
                return@transaction acknowledge(note.id, note.sentRevision, remote)
            }
            if (note.isDirty && note.snapshot().sameBody(remote)) {
                val matched = current.copy(baseJson = SnapshotCodec.snapshot(remote), remoteName = name,
                    syncedRevision = current.revision, sentJson = null, sentRevision = null)
                dao.put(matched)
                if (matched.deleteAfter == null) dao.deletePending(account, matched.id)
                return@transaction matched.note()
            }
            if (note.isDirty && note.base?.sameBody(remote) == true) return@transaction note
            if (note.isDirty) {
                saveRecovery(current, note.snapshot(), "Local version before server conflict")
                saveRecovery(current, remote, "Conflicting server version")
                note.sent?.let { saveRecovery(current, it, "In-flight version before server conflict") }
                val message = CONFLICT_MESSAGE
                val conflicted = current.copy(error = null, problemCode = NoteProblem.CONFLICT.code, remoteName = name, baseJson = SnapshotCodec.snapshot(remote), sentJson = null, sentRevision = null)
                dao.put(conflicted)
                dao.putPending(PendingEntity(account, current.id, current.revision, "SAVE", retryAt = Long.MAX_VALUE, error = message))
                return@transaction conflicted.note()
            }
        }
        if (current != null && current.note().attachments.any { old -> remote.attachments.none { it.id == old.id } }) {
            saveRecovery(current, current.note().snapshot(), "Before server removed attachments")
        }
        val cached = current?.note()?.attachments.orEmpty().associateBy { it.id }
        val saved = NoteEntity(
            account = account, id = current?.id ?: callerId,
            content = remote.content, searchContent = LiteralSearch.folded(remote.content),
            createdAt = remote.createdAt, updatedAt = remote.updatedAt,
            state = remote.state.name, visibility = remote.visibility.name, remoteName = name,
            revision = current?.revision ?: 0, syncedRevision = current?.revision ?: 0,
            baseJson = SnapshotCodec.snapshot(remote),
            attachmentsJson = SnapshotCodec.attachments(remote.attachments.map { it.copy(localPath = cached[it.id]?.localPath, uploaded = true) }),
            tagsJson = SnapshotCodec.tags(MemosTags.extract(remote.content)),
            selectionStart = current?.selectionStart?.coerceAtMost(remote.content.length) ?: 0,
            selectionEnd = current?.selectionEnd?.coerceAtMost(remote.content.length) ?: 0,
            scroll = current?.scroll ?: 0,
            pinned = remote.pinned,
            locationJson = SnapshotCodec.location(remote.location),
        )
        dao.put(saved); saved.note()
    }

    suspend fun recovery(id: String): List<RecoveryRevision> = dao.recovery(account, id).map {
        RecoveryRevision(it.id, it.noteId, readSnapshot(it.snapshotJson), it.reason, it.savedAt)
    }
    suspend fun preserve(id: String, snapshot: MemoSnapshot, reason: String) = transaction {
        saveRecovery(requireNote(id), snapshot, reason)
    }

    suspend fun recoveries(): List<RecoveryRevision> = dao.allRecovery().filter { it.account == account }.map {
        RecoveryRevision(it.id, it.noteId, readSnapshot(it.snapshotJson), it.reason, it.savedAt)
    }

    suspend fun restoreRecovery(id: String, recoveryId: String): Note = transaction {
        val revision = dao.recovery(account, id).first { it.id == recoveryId }
        val snapshot = durableSnapshot(readSnapshot(revision.snapshotJson))
        val existing = dao.get(account, id)
        val current = existing ?: NoteEntity(account, UUID.randomUUID().toString().lowercase(), createdAt = snapshot.createdAt, updatedAt = clock())
        existing?.let { saveRecovery(it, it.note().snapshot(), "Before restoring a recovery version") }
        val saved = current.copy(content = snapshot.content, searchContent = LiteralSearch.folded(snapshot.content),
            tagsJson = SnapshotCodec.tags(MemosTags.extract(snapshot.content)),
            attachmentsJson = SnapshotCodec.attachments(if (existing == null) independentAttachments(snapshot.attachments) else snapshot.attachments.map {
                if (current.note().attachments.any { file -> file.id == it.id }) it else it.copy(uploaded = false)
            }),
            state = snapshot.state.name, visibility = if (existing == null) NoteVisibility.PRIVATE.name else snapshot.visibility.name,
            pinned = snapshot.pinned, locationJson = SnapshotCodec.location(snapshot.location),
            revision = current.revision + 1, updatedAt = clock(), error = null, problemCode = null, deleteAfter = null,
            sentJson = null, sentRevision = null)
        dao.put(saved); queue(saved)
        if (existing == null) dao.putMetadata(MetadataEntity(account, "replacement:$id", saved.id))
        saved.note()
    }

    suspend fun finishDelete(id: String) = transaction {
        val current = dao.get(account, id) ?: return@transaction
        saveRecovery(current, current.note().snapshot(), "Deleted note")
        dao.delete(account, id); dao.deletePending(account, id)
    }

    suspend fun remoteMissing(id: String) = transaction {
        val current = dao.get(account, id) ?: return@transaction
        if (current.note().isDirty && current.deleteAfter == null) {
            val snapshot = durableSnapshot(current.note().snapshot())
            saveRecovery(current, snapshot, "Server note disappeared with unsynced local work")
            val saved = current.copy(remoteName = null, baseJson = null, sentJson = null, sentRevision = null,
                attachmentsJson = SnapshotCodec.attachments(snapshot.attachments.map { it.copy(uploaded = false) }),
                visibility = NoteVisibility.PRIVATE.name, revision = current.revision + 1, error = null)
            dao.put(saved); queue(saved)
        } else finishDelete(id)
    }

    suspend fun recordFailure(id: String, message: String, retryAt: Long?, attempts: Int) = transaction {
        val current = requireNote(id)
        if (current.hasConflict) { queue(current); return@transaction }
        dao.put(current.copy(error = message))
        dao.putPending(PendingEntity(account, id, current.revision, if (current.deleteAfter == null) "SAVE" else "DELETE", attempts, retryAt, message))
    }
    suspend fun retry(id: String? = null) = transaction {
        dao.pending(account).filter { id == null || it.noteId == id }.forEach { pending ->
            val note = dao.get(account, pending.noteId) ?: return@forEach
            if (note.hasConflict) return@forEach
            dao.put(note.copy(error = null)); queue(note.copy(error = null))
        }
        dao.cleanup(account).filter { id == null || it.noteId == id }.forEach { cleanup ->
            dao.putCleanup(cleanup.copy(attempts = 0, error = null))
            dao.putMetadata(MetadataEntity(account, "attachmentRetry:${cleanup.id}", ""))
        }
    }
    suspend fun metadata(key: String): String? = dao.metadata(account, key)
    suspend fun setMetadata(key: String, value: String) { dao.putMetadata(MetadataEntity(account, key, value)) }

    /** The caller must complete and fsync a durable original before association is confirmed. */
    suspend fun addAttachment(id: String, attachment: NoteAttachment): Note = transaction {
        require(attachment.localPath != null && File(attachment.localPath).isFile) { "Attachment original is unavailable" }
        val current = requireNote(id)
        val values = readAttachments(current.attachmentsJson).filterNot { it.id == attachment.id } + attachment
        val saved = current.copy(attachmentsJson = SnapshotCodec.attachments(values), revision = current.revision + 1,
            updatedAt = clock(), error = null)
        dao.put(saved); queue(saved); saved.note()
    }
    suspend fun updateAttachment(id: String, oldAttachmentId: String, attachment: NoteAttachment): Note = transaction {
        val current = requireNote(id)
        val values = readAttachments(current.attachmentsJson).map {
            if (it.id == oldAttachmentId || it.id == attachment.id) attachment else it
        }
        val saved = current.copy(attachmentsJson = SnapshotCodec.attachments(values))
        dao.put(saved)
        if (oldAttachmentId != attachment.id) {
            val oldFile = originalAttachmentFile(oldAttachmentId)
            val newFile = originalAttachmentFile(attachment.id)
            if (oldFile.isFile && !newFile.exists()) {
                oldFile.renameTo(newFile)
            }
        }
        saved.note()
    }
    suspend fun updateAttachment(id: String, attachment: NoteAttachment): Note =
        updateAttachment(id, attachment.id, attachment)
    /** Preserve downloaded originals in every recovery/removal snapshot before the server can destroy bytes. */
    suspend fun recordAttachmentOriginal(attachmentId: String, path: String) = transaction {
        require(File(path).isFile)
        fun files(values: List<NoteAttachment>): List<NoteAttachment> = values.map {
            if (it.id == attachmentId) it.copy(localPath = path) else it
        }
        fun body(json: String?): String? = json?.let {
            val snapshot = readSnapshot(it)
            SnapshotCodec.snapshot(snapshot.copy(attachments = files(snapshot.attachments)))
        }
        dao.all(account).forEach { row -> dao.put(row.copy(attachmentsJson = SnapshotCodec.attachments(files(readAttachments(row.attachmentsJson))),
            baseJson = body(row.baseJson), sentJson = body(row.sentJson))) }
        dao.allRecovery().filter { it.account == account }.forEach { row ->
            dao.putRecovery(row.copy(snapshotJson = requireNotNull(body(row.snapshotJson))))
        }
        dao.cleanup(account).forEach { row -> dao.putCleanup(row.copy(attachmentJson = SnapshotCodec.attachments(files(readAttachments(row.attachmentJson))))) }
    }

    suspend fun removeAttachment(id: String, attachmentId: String): NoteAttachment = transaction {
        val current = requireNote(id)
        val snapshot = durableSnapshot(current.note().snapshot())
        val values = snapshot.attachments
        val removed = values.first { it.id == attachmentId }
        saveRecovery(current, snapshot, "Before removing ${removed.filename}")
        dao.putCleanup(AttachmentCleanupEntity(account, removed.id, SnapshotCodec.attachments(listOf(removed)), id, clock() + 5_000))
        val saved = current.copy(attachmentsJson = SnapshotCodec.attachments(values.filterNot { it.id == attachmentId }),
            revision = current.revision + 1, updatedAt = clock(), error = null)
        dao.put(saved); queue(saved); removed
    }
    suspend fun undoRemoveAttachment(id: String, attachmentId: String): Boolean = transaction {
        val cleanup = dao.cleanup(account).firstOrNull { it.id == attachmentId && it.noteId == id } ?: return@transaction false
        if (clock() >= cleanup.deleteAfter) return@transaction false
        val attachment = readAttachments(cleanup.attachmentJson).single()
        val current = requireNote(id)
        val saved = current.copy(attachmentsJson = SnapshotCodec.attachments(readAttachments(current.attachmentsJson) + attachment),
            revision = current.revision + 1, updatedAt = clock())
        dao.put(saved); queue(saved); dao.deleteCleanup(account, attachmentId); true
    }
    suspend fun attachmentCleanup(): List<AttachmentCleanup> = dao.cleanup(account).map {
        AttachmentCleanup(readAttachments(it.attachmentJson).single(), it.noteId, it.deleteAfter,
            it.attempts, dao.metadata(account, "attachmentRetry:${it.id}")?.toLongOrNull(), it.error)
    }
    suspend fun recordAttachmentCleanupFailure(id: String, message: String, retryAt: Long, attempts: Int) = transaction {
        val current = dao.cleanup(account).firstOrNull { it.id == id } ?: return@transaction
        dao.putCleanup(current.copy(attempts = attempts, error = message))
        dao.putMetadata(MetadataEntity(account, "attachmentRetry:$id", retryAt.toString()))
    }
    suspend fun finishAttachmentCleanup(id: String) = transaction {
        dao.deleteCleanup(account, id)
        dao.putMetadata(MetadataEntity(account, "attachmentRetry:$id", ""))
    }
    /** Protect durable and recoverable bytes even after a row is deleted or another account is active. */
    suspend fun protectedPaths(): Set<String> = buildSet {
        dao.allAccounts().forEach { readAttachments(it.attachmentsJson).mapNotNullTo(this) { file -> file.localPath } }
        dao.allRecovery().forEach { readSnapshot(it.snapshotJson).attachments.mapNotNullTo(this) { file -> file.localPath } }
        dao.allCleanup().forEach { readAttachments(it.attachmentJson).mapNotNullTo(this) { file -> file.localPath } }
    }

    /** First connection claims device-only drafts and recoveries; configured namespaces are never imported. */
    suspend fun importLocalDrafts(): Int = withContext(Dispatchers.IO) {
        require(account != LOCAL_ACCOUNT)
        transaction {
            val claimed = dao.metadata(LOCAL_ACCOUNT, "draftsClaimedBy")
            if (claimed != null && claimed != account) return@transaction 0
            var imported = 0
            suspend fun copyFiles(snapshot: MemoSnapshot): MemoSnapshot = snapshot.copy(attachments = snapshot.attachments.map { file ->
                val sourceFile = file.localPath?.let(::File)
                if (sourceFile == null || !sourceFile.isFile) file else {
                    val destination = originalAttachmentFile(file.id)
                    if (!destination.isFile) {
                        val temporary = File.createTempFile(".import-", ".partial", originalsDirectory)
                        try {
                            sourceFile.inputStream().use { input -> java.io.FileOutputStream(temporary).use { output ->
                                input.copyTo(output, 65_536); output.fd.sync()
                            } }
                            java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                            syncOriginalDirectories()
                        } finally { temporary.delete() }
                    }
                    file.copy(localPath = destination.absolutePath, uploaded = false, remoteName = null)
                }
            })
            for (source in dao.all(LOCAL_ACCOUNT)) {
                if (source.deleteAfter != null || dao.get(account, source.id) != null) continue
                val snapshot = copyFiles(source.note().snapshot())
                val importedNote = source.copy(account = account, remoteName = null, baseJson = null, sentJson = null,
                    sentRevision = null, syncedRevision = 0, revision = maxOf(1, source.revision),
                    visibility = NoteVisibility.PRIVATE.name, attachmentsJson = SnapshotCodec.attachments(snapshot.attachments))
                dao.put(importedNote)
                if (!importedNote.note().isBlankDraft) queue(importedNote)
                dao.delete(LOCAL_ACCOUNT, source.id); dao.deletePending(LOCAL_ACCOUNT, source.id)
                imported++
            }
            for (recovery in dao.allRecovery().filter { it.account == LOCAL_ACCOUNT }) {
                dao.putRecovery(recovery.copy(account = account,
                    snapshotJson = SnapshotCodec.snapshot(copyFiles(readSnapshot(recovery.snapshotJson)))))
            }
            dao.putMetadata(MetadataEntity(LOCAL_ACCOUNT, "draftsClaimedBy", account))
            imported
        }
    }

    private suspend fun requireNote(id: String): NoteEntity = requireNotNull(dao.get(account, id)) { "Note is unavailable" }
    private suspend fun preserveDeletedEdit(id: String, edit: EditSnapshot): Note {
        val origin = requireNotNull(edit.origin) { "Note is unavailable" }
        require(edit.content != origin.content) { "This note was deleted before an unchanged editor snapshot was saved." }
        val aliases = linkedSetOf(id)
        var target = id
        while (true) {
            val replacement = dao.metadata(account, "replacement:$target") ?: break
            check(aliases.add(replacement)) { "A preserved note has an invalid replacement identity." }
            if (dao.get(account, replacement) != null) return saveEdit(replacement, edit)
            target = replacement
        }
        val durableOrigin = durableSnapshot(origin)
        val previous = NoteEntity(account, id, createdAt = origin.createdAt, updatedAt = origin.updatedAt)
        saveRecovery(previous, durableOrigin, "Server note deleted while editor was open")
        saveRecovery(previous, durableOrigin.copy(content = edit.content), "Unsaved typing preserved after server deletion")
        val saved = NoteEntity(account, UUID.randomUUID().toString().lowercase(), content = edit.content,
            searchContent = LiteralSearch.folded(edit.content), createdAt = origin.createdAt, updatedAt = clock(),
            state = origin.state.name, visibility = NoteVisibility.PRIVATE.name, revision = 1,
            attachmentsJson = SnapshotCodec.attachments(independentAttachments(durableOrigin.attachments)),
            tagsJson = SnapshotCodec.tags(MemosTags.extract(edit.content)),
            selectionStart = edit.selectionStart.coerceIn(0, edit.content.length),
            selectionEnd = edit.selectionEnd.coerceIn(0, edit.content.length), scroll = maxOf(0, edit.scroll))
        dao.put(saved); queue(saved)
        for (alias in aliases) dao.putMetadata(MetadataEntity(account, "replacement:$alias", saved.id))
        return saved.note()
    }
    /** Immutable private originals are shared locally and protected by both recovery/live references. */
    private suspend fun independentAttachments(attachments: List<NoteAttachment>): List<NoteAttachment> = attachments.map {
        promoteCachedAttachment(it).copy(id = UUID.randomUUID().toString().lowercase(), uploaded = false)
    }
    private suspend fun queue(note: NoteEntity) {
        dao.putPending(PendingEntity(account, note.id, note.revision,
            if (note.deleteAfter == null) "SAVE" else "DELETE",
            retryAt = if (note.hasConflict) Long.MAX_VALUE else note.deleteAfter,
            error = null))
    }
    private suspend fun saveRecovery(note: NoteEntity, snapshot: MemoSnapshot, reason: String) {
        val encoded = SnapshotCodec.snapshot(durableSnapshot(snapshot))
        if (dao.recovery(account, note.id).any { it.snapshotJson == encoded && it.reason == reason }) return
        dao.putRecovery(RecoveryEntity(account, UUID.randomUUID().toString(), note.id, encoded, reason, clock()))
    }

    private suspend fun durableSnapshot(snapshot: MemoSnapshot): MemoSnapshot =
        snapshot.copy(attachments = snapshot.attachments.map { promoteCachedAttachment(it) })

    private suspend fun promoteCachedAttachment(attachment: NoteAttachment): NoteAttachment = withContext(Dispatchers.IO) {
        val local = attachment.localPath?.let(::File)?.takeIf { it.isFile }
        if (local != null) {
            if (local.length() != attachment.size) throw IOException("The saved attachment original is incomplete.")
            if (local.canonicalFile.toPath().startsWith(originalsDirectory.canonicalFile.toPath())) return@withContext attachment
        }
        val original = originalAttachmentFile(attachment.id)
        if (!original.isFile) {
            val identities = listOfNotNull(attachment.id, attachment.remoteName?.substringAfterLast('/')).distinct()
            val cached = local ?: identities.asSequence().map(::cachedAttachmentFile)
                .firstOrNull { it.isFile && it.length() == attachment.size } ?: return@withContext attachment
            try {
                // Immutable cache and files storage share the app volume; linking avoids a large autosave copy.
                Files.createLink(original.toPath(), cached.toPath())
            } catch (error: Exception) {
                if (error !is IOException && error !is UnsupportedOperationException) throw error
                if (!original.isFile) {
                    val temporary = File.createTempFile(".original-", ".partial", originalsDirectory)
                    try {
                        cached.inputStream().use { input -> FileOutputStream(temporary).use { output ->
                            input.copyTo(output, 65_536); output.fd.sync()
                        } }
                        if (temporary.length() != attachment.size) throw IOException("The cached file is incomplete. Its original could not be preserved.")
                        Files.move(temporary.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } finally { temporary.delete() }
                }
            }
        }
        if (original.length() != attachment.size) throw IOException("The saved attachment original is incomplete.")
        FileChannel.open(original.toPath(), StandardOpenOption.READ).use { it.force(true) }
        syncOriginalDirectories()
        attachment.copy(localPath = original.absolutePath)
    }

    private fun syncOriginalDirectories() {
        // Persist newly created account folders through their private files root as well as the file entry.
        generateSequence(originalsDirectory) { it.parentFile }.take(4).forEach { directory ->
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
    private suspend fun <T> transaction(block: suspend () -> T): T = database.useWriterConnection {
        it.immediateTransaction { block() }
    }
    private fun readAttachments(json: String): List<NoteAttachment> =
        SnapshotCodec.attachments(json).map(::restoredAttachment)

    private fun readSnapshot(json: String): MemoSnapshot = SnapshotCodec.snapshot(json).let {
        it.copy(attachments = it.attachments.map(::restoredAttachment))
    }

    /** Old installations persisted absolute paths. Rebase only our known private directory
     * shape; preserve immutable originals across accounts and discard absent download cache. */
    private fun restoredAttachment(attachment: NoteAttachment): NoteAttachment {
        val path = attachment.localPath ?: return attachment
        val match = PRIVATE_FILE_PATH.find(path) ?: return attachment
        val (domain, namespace, directory, filename) = match.destructured
        if (filename == "." || filename == "..") return attachment.copy(localPath = null)
        val accounts = when {
            domain == "files" && directory == "originals" -> originalsDirectory.parentFile!!.parentFile!!
            domain == "cache" && directory == "attachments" -> cacheDirectory.parentFile!!.parentFile!!
            else -> return attachment.copy(localPath = null)
        }
        val restored = File(accounts, "$namespace/$directory/$filename")
        return attachment.copy(localPath = restored.absolutePath.takeIf { directory == "originals" || restored.isFile })
    }

    private val NoteEntity.problem: NoteProblem? get() = NoteProblem.entries.firstOrNull { it.code == problemCode }
    private val NoteEntity.hasConflict: Boolean get() = problem == NoteProblem.CONFLICT

    private fun NoteEntity.note(): Note = Note(id, account, content, createdAt, updatedAt, NoteState.valueOf(state),
        NoteVisibility.valueOf(visibility), remoteName, revision, syncedRevision,
        baseJson?.let(::readSnapshot), sentJson?.let(::readSnapshot), sentRevision,
        deleteAfter, if (hasConflict) CONFLICT_MESSAGE else error, selectionStart, selectionEnd, scroll,
        readAttachments(attachmentsJson), SnapshotCodec.tags(tagsJson), problem, pinned, SnapshotCodec.location(locationJson))

    private fun NotePreviewRow.preview(): NotePreview = NotePreview(id, account, content, createdAt, updatedAt,
        NoteState.valueOf(state), NoteVisibility.valueOf(visibility), remoteName, if (problemCode == NoteProblem.CONFLICT.code) CONFLICT_MESSAGE else error,
        readAttachments(attachmentsJson), SnapshotCodec.tags(tagsJson), NoteProblem.entries.firstOrNull { it.code == problemCode }, pinned, SnapshotCodec.location(locationJson))

    override fun close() { database.close() }

    companion object {
        private val PRIVATE_FILE_PATH = Regex("(?:^|/)(files|cache)/accounts/([0-9a-f]{64})/(originals|attachments)/([^/]+)$")
        private fun accountHash(account: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
        const val LOCAL_ACCOUNT = "local"
        const val CONFLICT_MESSAGE = "A server edit conflicts with this note. Both versions are preserved. Choose a recovery version to continue."
        fun open(context: Context, account: String = LOCAL_ACCOUNT, databaseName: String = "memoize.db", clock: () -> Long = System::currentTimeMillis): NoteRepository {
            require(account.isNotBlank())
            val namespace = accountHash(account)
            return NoteRepository(NoteDatabase.open(context, databaseName), account,
                File(context.filesDir, "accounts/$namespace/originals").apply { mkdirs() },
                File(context.cacheDir, "accounts/$namespace/attachments").apply { mkdirs() }, clock)
        }
    }
}
