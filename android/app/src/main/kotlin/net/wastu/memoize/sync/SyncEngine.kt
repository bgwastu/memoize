package net.wastu.memoize.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.wastu.memoize.data.Note
import net.wastu.memoize.data.NoteRepository
import java.io.File
import net.wastu.memoize.data.NoteAttachment
import kotlin.math.pow

/** One serialized worker acknowledges only the immutable revision represented by its request. */
class SyncEngine(
    private val notes: NoteRepository,
    private val api: MemosApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Double = { kotlin.random.Random.nextDouble(0.85, 1.15) },
) {
    private val lock = Mutex()

    suspend fun synchronizeOnce(force: Boolean = false) = lock.withLock {
        if (force) {
            notes.setMetadata("authenticationPaused", "false")
            notes.setMetadata("retryAt", "")
            notes.retry()
        }
        if (notes.metadata("authenticationPaused") == "true") return@withLock
        if ((notes.metadata("retryAt")?.toLongOrNull() ?: 0) > clock()) return@withLock
        try {
            performPass()
            notes.setMetadata("retryAttempts", "0")
            notes.setMetadata("retryAt", "")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val failure = classifyFailure(error, notes.metadata("retryAttempts")?.toIntOrNull() ?: 0)
            notes.setMetadata("retryAttempts", failure.attempts.toString())
            notes.setMetadata("authenticationPaused", failure.needsAuthentication.toString())
            notes.setMetadata("retryAt", if (failure.retryable) failure.retryAt.toString() else "")
            throw error
        }
    }

    /** Durable retry and Undo deadlines drive foreground wakeups and persistent work requests. */
    suspend fun nextWake(): Long? {
        if (notes.metadata("authenticationPaused") == "true") return null
        notes.metadata("retryAt")?.toLongOrNull()?.takeIf { it > clock() }?.let { return it }
        val candidates = mutableListOf<Long>()
        val cleanup = notes.attachmentCleanup()
        for (operation in notes.pending()) {
            if (operation.retryAt == Long.MAX_VALUE) continue
            val note = notes.get(operation.noteId) ?: continue
            if (!note.isBlankDraft || note.deleteAfter != null) {
                val undo = cleanup.filter { it.noteId == note.id && it.deleteAfter > clock() }.maxOfOrNull { it.deleteAfter } ?: 0
                candidates += maxOf(operation.retryAt ?: clock(), undo)
            }
        }
        for (item in cleanup) {
            if (item.retryAt == Long.MAX_VALUE) continue
            val current = item.noteId?.let { notes.get(it) }
            if (current != null && (current.hasConflict || current.deleteAfter != null ||
                (current.isDirty && !current.isBlankDraft))) continue
            candidates += maxOf(item.deleteAfter, item.retryAt ?: 0)
        }
        return candidates.minOrNull()
    }

    private suspend fun performPass() {
        val remote = api.list() // Neither partial pages nor a failed ARCHIVED list can infer deletion.
        remote.forEach { currentCoroutineContext().ensureActive(); notes.applyRemote(it) }
        val names = remote.mapNotNull { it.remoteName }.toSet()
        for (note in notes.all()) {
            if (note.base != null && note.remoteName != null && note.remoteName !in names &&
                (note.deleteAfter == null || note.deleteAfter <= clock())) {
                val latest = api.get(note.remoteName)
                if (latest != null) notes.applyRemote(latest) else notes.remoteMissing(note.id)
            }
        }
        var transient: Exception? = null
        for (operation in notes.pending()) {
            if (operation.retryAt != null && operation.retryAt > clock()) continue
            try {
                currentCoroutineContext().ensureActive()
                var current = notes.get(operation.noteId) ?: continue
                if (current.deleteAfter != null) {
                    deleteAfterUndo(current)
                    continue
                }
                if (current.isBlankDraft) continue
                val latest = api.get(current.remoteName ?: current.id)
                if (latest != null) notes.applyRemote(latest)
                else if (current.base != null) { notes.remoteMissing(current.id); continue }
                current = notes.get(current.id) ?: continue
                if (!current.isDirty || current.hasConflict) continue
                val cleanup = notes.attachmentCleanup().filter { it.noteId == current.id }
                if (cleanup.any { it.deleteAfter > clock() }) continue
                for (removed in current.base?.attachments.orEmpty().filter { old -> current.attachments.none { it.id == old.id } }) {
                    try { durableOriginal(removed) } catch (error: MemosApiException) { if (error.status != 404) throw error }
                }
                for (file in current.attachments.filter { !it.uploaded }) {
                    val original = durableOriginal(file)
                    val uploaded = api.upload(file, original).copy(localPath = original.absolutePath)
                    notes.updateAttachment(current.id, file.id, uploaded)
                }
                val sent = notes.beginSendIfUnchanged(current.id, current.revision) ?: continue
                val result = if (sent.base == null) api.create(sent.id, sent.snapshot())
                    else api.update(sent.snapshot(), sent.base)
                notes.acknowledge(sent.id, requireNotNull(sent.sentRevision), result)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val failure = classifyFailure(error, operation.attempts)
                if (notes.get(operation.noteId) != null) notes.recordFailure(operation.noteId,
                    error.message ?: "Sync could not finish. Saved work remains on this device.", failure.retryAt, failure.attempts)
                if (failure.haltPass) throw error
                if (failure.retryable) transient = error
            }
        }
        for (cleanup in notes.attachmentCleanup()) {
            if (cleanup.deleteAfter > clock() || (cleanup.retryAt ?: 0) > clock()) continue
            val current = cleanup.noteId?.let { notes.get(it) }
            if (notes.all().any { note -> note.attachments.any { it.id == cleanup.attachment.id } }) {
                notes.finishAttachmentCleanup(cleanup.attachment.id)
                continue
            }
            if (current != null && (current.hasConflict || current.deleteAfter != null ||
                (current.isDirty && !current.isBlankDraft))) continue
            try {
                currentCoroutineContext().ensureActive()
                val serverFile = api.getAttachment(cleanup.attachment.id)
                if (serverFile != null) {
                    durableOriginal(serverFile.copy(localPath = cleanup.attachment.localPath))
                    // A recovery choice made during download cancels destructive orphan cleanup.
                    if (notes.all().any { note -> note.attachments.any { it.id == cleanup.attachment.id } }) {
                        notes.finishAttachmentCleanup(cleanup.attachment.id)
                        continue
                    }
                    api.deleteAttachment(cleanup.attachment.id)
                }
                notes.finishAttachmentCleanup(cleanup.attachment.id)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val failure = classifyFailure(error, cleanup.attempts)
                notes.recordAttachmentCleanupFailure(cleanup.attachment.id,
                    error.message ?: "File cleanup could not finish. Recovery originals remain on this device.",
                    failure.retryAt, failure.attempts)
                if (failure.haltPass) throw error
                if (failure.retryable) transient = error
            }
        }
        transient?.let { throw it }
    }

    private suspend fun deleteAfterUndo(note: Note) {
        val deadline = note.deleteAfter ?: return
        if (deadline > clock()) return
        val latest = api.get(note.remoteName ?: note.id)
        val pending = notes.get(note.id) ?: return
        if (pending.deleteAfter != deadline) return
        if (latest != null && pending.base != null && !pending.base.sameBody(latest)) {
            notes.applyRemote(latest)
            return
        }
        if (latest != null && pending.base == null && !pending.snapshot().sameBody(latest)) {
            notes.applyRemote(latest)
            return
        }
        if (latest != null) {
            for (file in latest.attachments) {
                val known = pending.attachments.firstOrNull { it.id == file.id } ?: file
                try { durableOriginal(known) } catch (error: MemosApiException) { if (error.status != 404) throw error }
            }
            api.delete(note.remoteName ?: note.id)
        }
        notes.finishDelete(note.id)
    }

    private suspend fun durableOriginal(attachment: NoteAttachment): File {
        val kept = notes.keepAttachmentOriginal(attachment)
        val original = kept.localPath?.let(::File)?.takeIf { it.isFile } ?: notes.originalAttachmentFile(attachment.id)
        if (!original.isFile) {
            api.download(attachment, original)
            notes.keepAttachmentOriginal(attachment)
        }
        return original
    }

    private data class Failure(val attempts: Int, val retryable: Boolean, val retryAt: Long,
        val needsAuthentication: Boolean, val haltPass: Boolean)

    /** Shared decision; each operation retains its own durable failure record. */
    private fun classifyFailure(error: Exception, previousAttempts: Int): Failure {
        val apiError = error as? MemosApiException
        val attempts = previousAttempts + 1
        val retryable = apiError == null || apiError.retryable
        val authentication = apiError?.needsAuthentication == true
        return Failure(attempts, retryable, if (retryable) deadline(backoff(attempts, apiError)) else Long.MAX_VALUE,
            authentication, authentication || apiError?.status == 429)
    }

    private fun backoff(attempts: Int, error: MemosApiException?): Long =
        maxOf(error?.retryAfterMillis ?: 0, (minOf(300.0, 2.0.pow(attempts.coerceAtMost(8))) * jitter() * 1_000).toLong())
    private fun deadline(delay: Long): Long = if (delay > Long.MAX_VALUE - clock()) Long.MAX_VALUE else clock() + delay
}
