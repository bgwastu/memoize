package net.wastu.memoize.ui

import androidx.compose.ui.text.TextRange
import android.os.SystemClock

internal enum class EditKind { TYPING, COMPOSITION, COMMAND, CLIPBOARD }
internal data class TextDelta(val start: Int, val removed: String, val inserted: String)
internal data class EditTransaction(val changes: List<TextDelta>, val before: TextRange, val after: TextRange,
    val kind: EditKind, val time: Long)

/** Range payloads, never document snapshots; open composition remains one transaction. */
internal class NativeEditHistory(private val clock: () -> Long = SystemClock::uptimeMillis) {
    private data class Pending(val before: TextRange, var kind: EditKind, val changes: MutableList<TextDelta>, var discarded: Boolean = false)
    private var pending: Pending? = null
    private val undo = ArrayDeque<EditTransaction>()
    private val redo = ArrayDeque<EditTransaction>()
    private var mayMergeTyping = false
    val canUndo: Boolean get() = undo.isNotEmpty() || pending?.changes?.isNotEmpty() == true
    val canRedo: Boolean get() = redo.isNotEmpty() && pending?.changes.isNullOrEmpty()

    fun begin(selection: TextRange, kind: EditKind) {
        if (pending == null) pending = Pending(selection, kind, ArrayList())
        else if (kind != EditKind.TYPING) pending?.kind = kind
    }

    fun record(delta: TextDelta) {
        if (delta.removed == delta.inserted) return
        val transaction = requireNotNull(pending)
        redo.clear()
        if (transaction.discarded) return
        val last = transaction.changes.lastOrNull()
        // Successive composing replacements at the same position have one exact inverse.
        if (transaction.kind == EditKind.COMPOSITION && last != null && last.start == delta.start && last.inserted == delta.removed) {
            transaction.changes[transaction.changes.lastIndex] = last.copy(inserted = delta.inserted)
        } else transaction.changes += delta
        if (transaction.changes.sumOf { cost(it) } > MAX_PAYLOAD) {
            transaction.changes.clear()
            transaction.discarded = true
            undo.clear()
            mayMergeTyping = false
        } else {
            val openPayload = transaction.changes.sumOf { cost(it) }
            while (undo.isNotEmpty() && openPayload + undo.sumOf { group ->
                group.changes.sumOf { cost(it) }
            } > MAX_PAYLOAD) undo.removeFirst()
        }
    }

    fun finish(selection: TextRange, composing: Boolean) {
        val transaction = pending ?: return
        if (composing) { transaction.kind = EditKind.COMPOSITION; return }
        pending = null
        val changes = transaction.changes.filter { it.removed != it.inserted }
        if (changes.isEmpty()) return
        val current = EditTransaction(changes, transaction.before, selection, transaction.kind, clock())
        val previous = undo.lastOrNull()
        if (mayMergeTyping && previous != null && mergeable(previous, current)) {
            undo.removeLast()
            val last = previous.changes.last()
            val next = current.changes.single()
            val combined = if (next.removed.isEmpty()) last.copy(inserted = last.inserted + next.inserted)
                else TextDelta(next.start, next.removed + last.removed, "")
            undo.addLast(current.copy(changes = previous.changes.dropLast(1) + combined, before = previous.before))
        } else undo.addLast(current)
        mayMergeTyping = current.kind == EditKind.TYPING && current.changes.none(::hasNewline)
        trim()
    }

    fun selectionMoved() { mayMergeTyping = false }
    fun nextUndo(): EditTransaction? = undo.lastOrNull()
    fun nextRedo(): EditTransaction? = redo.lastOrNull()
    fun undone(transaction: EditTransaction) {
        check(undo.removeLast() === transaction)
        redo.addLast(transaction)
        mayMergeTyping = false
    }
    fun redone(transaction: EditTransaction) {
        check(redo.removeLast() === transaction)
        undo.addLast(transaction)
        mayMergeTyping = false
    }
    fun clear() { pending = null; undo.clear(); redo.clear(); mayMergeTyping = false }

    private fun mergeable(previous: EditTransaction, current: EditTransaction): Boolean {
        if (previous.kind != EditKind.TYPING || current.kind != EditKind.TYPING || current.time - previous.time > 1_000 ||
            previous.after != current.before || !current.before.collapsed) return false
        val next = current.changes.singleOrNull() ?: return false
        val last = previous.changes.lastOrNull() ?: return false
        if (hasNewline(last)) return false
        if (next.inserted.any { it == '\n' || it == '\r' } || next.removed.any { it == '\n' || it == '\r' }) return false
        return (next.removed.isEmpty() && last.removed.isEmpty() && next.start == last.start + last.inserted.length) ||
            (next.inserted.isEmpty() && last.inserted.isEmpty() && next.start + next.removed.length == last.start)
    }

    private fun cost(change: TextDelta) = 128L + 2L * (change.removed.length + change.inserted.length)
    private fun hasNewline(change: TextDelta) = change.inserted.any { it == '\n' || it == '\r' } ||
        change.removed.any { it == '\n' || it == '\r' }
    private companion object { const val MAX_PAYLOAD = 32L * 1024 * 1024 }

    private fun trim() {
        fun payload() = undo.sumOf { it.changes.sumOf { change -> cost(change) } }
        // An oversized command establishes a barrier. Source itself is never limited.
        if (undo.lastOrNull()?.changes?.sumOf { cost(it) }?.let { it > MAX_PAYLOAD } == true) {
            clear()
            return
        }
        while (undo.size > 200 || payload() > MAX_PAYLOAD) undo.removeFirst()
    }
}
