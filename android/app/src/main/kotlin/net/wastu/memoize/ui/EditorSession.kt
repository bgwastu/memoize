package net.wastu.memoize.ui

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import net.wastu.memoize.data.EditSnapshot
import net.wastu.memoize.data.Note
import net.wastu.memoize.data.NoteRepository
import net.wastu.memoize.data.MemoSnapshot

/** Latest own-save acknowledgment, without retaining another source buffer or version. */
data class SourceReceipt(val noteId: String, val revision: Long, val epoch: Long)

data class LocalSaveStatus(val saving: Boolean = false, val error: String? = null, val committedContent: String = "", val replacementId: String? = null, val receipt: SourceReceipt? = null)

/** Serialized immutable snapshots. Commit after a 150ms pause, bounded by 500ms while typing. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorSession(private val notes: NoteRepository, initial: Note, scope: CoroutineScope) {
    private sealed interface Event {
        data class Edit(val snapshot: EditSnapshot, val generation: Long, val epoch: Long, val time: Long) : Event
        data class Flush(val snapshot: EditSnapshot, val generation: Long, val epoch: Long, val result: CompletableDeferred<Note>) : Event
        data class AcceptRemote(val note: Note, val epoch: Long) : Event
        data object Stop : Event
    }
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val initialId = initial.id
    private var id = initial.id
    private var origin: MemoSnapshot = initial.snapshot()
    private val mutableStatus = MutableStateFlow(LocalSaveStatus(committedContent = initial.content, receipt = SourceReceipt(initial.id, initial.revision, 0)))
    val status: StateFlow<LocalSaveStatus> = mutableStatus
    private var generation = 0L
    private var acceptedId = initial.id
    private var acceptedEpoch = 0L
    private var acceptedRevision = initial.revision

    private val worker = scope.launch {
        try {
            var pending: Event.Edit? = null
            var firstEditAt = 0L
            while (true) {
                val wait = pending?.let { minOf(it.time + 150, firstEditAt + 500) - SystemClock.elapsedRealtime() }
                val event: Event? = if (wait == null) events.receive() else select<Event?> {
                    events.onReceive { it }
                    onTimeout(maxOf(0, wait)) { null }
                }
                when (event) {
                    is Event.Edit -> {
                        if (pending == null) firstEditAt = event.time
                        pending = event
                    }
                    is Event.Flush -> {
                        pending = null
                        commit(event.snapshot, event.generation, event.epoch, event.result)
                    }
                    is Event.AcceptRemote -> {
                        if (event.note.id == id) {
                            // An accepted body can still have a pending cursor/scroll edit. Keep
                            // that view state; only discard a snapshot of replaced source text.
                            if (pending != null && pending.epoch <= event.epoch && pending.snapshot.content != event.note.content) pending = null
                            origin = event.note.snapshot()
                            mutableStatus.value = mutableStatus.value.copy(saving = pending != null, error = null)
                        }
                    }
                    Event.Stop -> break
                    null -> {
                        val edit = pending ?: continue
                        pending = null
                        commit(edit.snapshot, edit.generation, edit.epoch)
                    }
                }
            }
        } finally { events.close() }
    }

    fun submit(snapshot: EditSnapshot, epoch: Long) {
        generation++
        mutableStatus.value = mutableStatus.value.copy(saving = true, error = null)
        events.trySend(Event.Edit(snapshot, generation, epoch, SystemClock.elapsedRealtime()))
    }

    /** Native character changes are authoritative even before snapshotFlow submits them. */
    fun canAcceptIncoming(note: Note, epoch: Long): Boolean =
        note.id == acceptedId && epoch == acceptedEpoch && note.revision >= acceptedRevision

    /** Remote imports can retain a local revision; a replacement must still be the current row. */
    suspend fun isCurrentIncoming(note: Note): Boolean = notes.get(note.id) == note

    /** Called only after the native editor has actually accepted this body (or a restored version). */
    fun acceptRemote(note: Note, epoch: Long) {
        acknowledge(note, epoch)
        mutableStatus.value = mutableStatus.value.copy(committedContent = note.content)
        events.trySend(Event.AcceptRemote(note, epoch))
    }

    private fun acknowledge(note: Note, epoch: Long) {
        if (epoch < acceptedEpoch || (note.id == acceptedId && note.revision < acceptedRevision)) return
        acceptedId = note.id
        acceptedEpoch = epoch
        acceptedRevision = note.revision
    }

    suspend fun flush(snapshot: EditSnapshot, epoch: Long): Note {
        generation++
        val result = CompletableDeferred<Note>()
        events.send(Event.Flush(snapshot, generation, epoch, result))
        return result.await()
    }

    suspend fun finish(snapshot: EditSnapshot?, epoch: Long) {
        try { if (snapshot != null) flush(snapshot, epoch) } finally {
            events.send(Event.Stop)
            worker.join()
        }
    }

    private suspend fun commit(snapshot: EditSnapshot, sentGeneration: Long, epoch: Long, result: CompletableDeferred<Note>? = null) {
        try {
            val saved = notes.saveEdit(id, snapshot.copy(origin = origin))
            id = saved.id
            origin = saved.snapshot()
            acknowledge(saved, epoch)
            mutableStatus.value = mutableStatus.value.copy(saving = sentGeneration != generation, error = null,
                committedContent = if (epoch == acceptedEpoch) saved.content else mutableStatus.value.committedContent,
                receipt = SourceReceipt(saved.id, saved.revision, epoch),
                replacementId = saved.id.takeIf { it != initialId })
            result?.complete(saved)
        } catch (exception: Exception) {
            if (exception is kotlinx.coroutines.CancellationException) throw exception
            mutableStatus.value = mutableStatus.value.copy(saving = false, error = exception.message ?: "Unable to save on this device")
            result?.completeExceptionally(exception)
        }
    }
}
