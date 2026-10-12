package net.wastu.memoize.ui

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.wastu.memoize.R
import net.wastu.memoize.data.EditSnapshot
import net.wastu.memoize.data.NoteRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The same production incoming-note method used by NoteEditor, with native input and real Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeSaveEchoTest {
    @Test fun aDelayedOwnSaveCannotRewindAnEditReturningToTheOriginalSource() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = "save-echo-${UUID.randomUUID()}.db"
        val gate = AtomicReference<SaveBarrier?>()
        val notes = NoteRepository.open(context, "save-echo", database) {
            gate.getAndSet(null)?.hold()
            System.currentTimeMillis()
        }
        val original = "cat 🐈"
        val inserted = original + "!"
        val initial = runBlocking { notes.create("save-echo"); notes.saveEdit("save-echo", EditSnapshot(original)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = EditorSession(notes, initial, scope)
        val source = NativeSourceState(initial, TextRange(original.length))
        val editor = NativeSourceView(ContextThemeWrapper(context, R.style.Theme_Memoize))
        val host = FrameLayout(context).apply { addView(editor, FrameLayout.LayoutParams(320, 470)) }
        host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(470, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 320, 470)
        source.attach(editor)
        val observer = scope.launch {
            notes.observeNote(initial.id).collect { incoming ->
                incoming?.let { note ->
                    source.acceptIncoming(note, session)
                }
            }
        }
        val firstSave = SaveBarrier()
        val nextSave = SaveBarrier()
        try {
            val input = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            gate.set(firstSave)
            assertTrue(input.setSelection(original.length, original.length))
            assertTrue(input.commitText("!", 1))
            assertEquals(inserted, editor.text.toString())
            session.submit(source.snapshot(), source.characterEpoch)
            await("first changed save held") { firstSave.entered.count == 0L }
            assertTrue(input.setSelection(original.length, original.length + 1))
            assertTrue(input.commitText("", 1))
            assertEquals(original, editor.text.toString())
            session.submit(source.snapshot(), source.characterEpoch)
            gate.set(nextSave)
            firstSave.release.countDown()
            await("old save acknowledgment processed") { nextSave.entered.count == 0L || editor.text.toString() != original }
            val acknowledged = runBlocking { requireNotNull(notes.get(initial.id)) }
            assertEquals(inserted, acknowledged.content)
            assertEquals("An older own-save result must not replace newer native edits", original, editor.text.toString())
            nextSave.release.countDown()
            await("newer source durable") { runBlocking { notes.get(initial.id)?.content == original } }
            assertEquals(original, source.snapshot().content)
        } finally {
            firstSave.release.countDown(); nextSave.release.countDown(); gate.set(null)
            observer.cancel(); scope.cancel()
            source.detach(editor)
            notes.close(); context.deleteDatabase(database)
        }
    }

    @Test fun nativeInputBeforeSubmissionRejectsAnOlderSaveAcknowledgment() = withEditor { fixture ->
        val first = SaveBarrier()
        try {
            fixture.gate.set(first)
            fixture.replace(fixture.original.length, fixture.original.length, "!")
            fixture.submit()
            await("preceding save held") { first.entered.count == 0L }
            fixture.replace(fixture.original.length, fixture.original.length + 1, "")
            // The latest characters exist before snapshotFlow has submitted them.
            first.release.countDown()
            await("preceding receipt") { fixture.session.status.value.receipt?.epoch == 1L }
            fixture.accept(requireNotNull(runBlocking { fixture.notes.get(fixture.initial.id) }))
            assertEquals(fixture.original, fixture.source.snapshot().content)
            fixture.submit()
            assertEquals(fixture.original, fixture.flush().content)
        } finally { first.release.countDown(); fixture.gate.set(null) }
    }

    @Test fun coalescedReturnToTheSameBodyAcknowledgesItsNewEpochWithoutARevisionChange() = withEditor { fixture ->
        val before = fixture.source.snapshot()
        fixture.replace(fixture.original.length, fixture.original.length, "!")
        fixture.replace(fixture.original.length, fixture.original.length + 1, "")
        assertEquals(before, fixture.source.snapshot())
        fixture.submit()
        val saved = fixture.flush()
        assertEquals(fixture.initial.revision, saved.revision)
        assertEquals(2L, fixture.session.status.value.receipt?.epoch)
        val incoming = runBlocking { fixture.notes.saveEdit(saved.id, EditSnapshot("Incoming source 📝")) }
        fixture.accept(incoming)
        assertEquals(incoming.content, fixture.source.snapshot().content)
        fixture.replace(incoming.content.length, incoming.content.length, "!")
        assertEquals(incoming.content + "!", fixture.flush().content)
    }

    @Test fun anOlderRoomRowCannotReplaceANewerCommittedNativeSource() = withEditor { fixture ->
        fixture.replace(fixture.original.length, fixture.original.length, "!")
        val preceding = fixture.flush()
        fixture.replace(fixture.original.length, fixture.original.length + 1, "")
        val current = fixture.flush()
        assertTrue(current.revision > preceding.revision)
        fixture.accept(preceding)
        assertEquals(fixture.original, fixture.source.snapshot().content)
        assertEquals(current.revision, fixture.session.status.value.receipt?.revision)
        assertEquals(current.content, runBlocking { fixture.notes.get(current.id)?.content })
        fixture.accept(preceding.copy(id = "another-note", revision = current.revision + 1))
        assertEquals(fixture.original, fixture.source.snapshot().content)
    }

    @Test fun aLateOwnRowCannotReplayAfterACleanRemoteImportWithTheSameLocalRevision() = withEditor { fixture ->
        fixture.replace(fixture.original.length, fixture.original.length, "!")
        val own = fixture.flush()
        val acknowledged = runBlocking { fixture.notes.applyRemote(own.snapshot().copy(remoteName = "memos/save-echo")) }
        fixture.accept(acknowledged)
        val incoming = runBlocking { fixture.notes.applyRemote(acknowledged.snapshot().copy(content = "Clean incoming source 📝", updatedAt = acknowledged.updatedAt + 1)) }
        assertEquals(own.revision, incoming.revision)
        fixture.accept(incoming)
        assertEquals(incoming.content, fixture.source.snapshot().content)
        val cursorOnly = fixture.flush()
        assertEquals(incoming.revision, cursorOnly.revision)
        fixture.accept(own)
        assertEquals(incoming.content, fixture.source.snapshot().content)
        fixture.replace(incoming.content.length, incoming.content.length, "!")
        val next = fixture.flush()
        assertEquals(incoming.content + "!", next.content)
        assertNull(next.problem)
    }

    @Test fun cleanIncomingSourceWaitsForCompositionThenPreservesItsOriginForTheNextEdit() = withEditor { fixture ->
        assertTrue(fixture.input.setComposingRegion(0, fixture.original.length))
        val incoming = runBlocking { fixture.notes.saveEdit(fixture.initial.id, EditSnapshot("Incoming source 📝")) }
        fixture.accept(incoming)
        assertEquals(fixture.original, fixture.source.snapshot().content)
        assertTrue(fixture.input.finishComposingText())
        fixture.accept(incoming)
        assertEquals(incoming.content, fixture.source.snapshot().content)
        fixture.replace(incoming.content.length, incoming.content.length, "!")
        val next = fixture.flush()
        assertEquals(incoming.content + "!", next.content)
        assertNull("Accepted incoming origin must not create a false conflict", next.problem)
    }

    @Test fun acceptedRestoreAcknowledgesItsNativeEpochBeforeImmediateTyping() = withEditor { fixture ->
        runBlocking { fixture.notes.preserve(fixture.initial.id, fixture.initial.snapshot(), "Before editing") }
        fixture.replace(fixture.original.length, fixture.original.length, "!")
        fixture.flush()
        val revision = runBlocking { fixture.notes.recovery(fixture.initial.id).first() }
        val restored = runBlocking { fixture.notes.restoreRecovery(fixture.initial.id, revision.id) }
        assertTrue(fixture.source.replace(restored.content, TextRange(restored.content.length)))
        fixture.session.acceptRemote(restored, fixture.source.characterEpoch)
        fixture.replace(restored.content.length, restored.content.length, "?")
        val saved = fixture.flush()
        assertEquals(restored.content + "?", saved.content)
        assertNull(saved.problem)
        assertEquals(fixture.source.characterEpoch, fixture.session.status.value.receipt?.epoch)
    }

    private fun withEditor(action: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { action(fixture) } finally { fixture.close() }
    }

    private inner class Fixture {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = "save-echo-${UUID.randomUUID()}.db"
        val gate = AtomicReference<SaveBarrier?>()
        val notes = NoteRepository.open(context, "save-echo", database) {
            gate.getAndSet(null)?.hold()
            System.currentTimeMillis()
        }
        val original = "cat 🐈"
        val initial = runBlocking { notes.create("save-echo"); notes.saveEdit("save-echo", EditSnapshot(original, original.length)) }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = EditorSession(notes, initial, scope)
        val source = NativeSourceState(initial, TextRange(original.length))
        private val editor = NativeSourceView(ContextThemeWrapper(context, R.style.Theme_Memoize))
        val input: android.view.inputmethod.InputConnection
        init {
            val host = FrameLayout(context).apply { addView(editor, FrameLayout.LayoutParams(320, 470)) }
            host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(470, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 320, 470)
            source.attach(editor)
            input = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        }
        fun replace(start: Int, end: Int, replacement: String) {
            assertTrue(input.setSelection(start, end))
            assertTrue(input.commitText(replacement, 1))
        }
        fun submit() = session.submit(source.snapshot(), source.characterEpoch)
        fun flush(): net.wastu.memoize.data.Note {
            val snapshot = source.snapshot()
            val epoch = source.characterEpoch
            val saved = scope.async { session.flush(snapshot, epoch) }
            await("native snapshot flushed") { saved.isCompleted }
            return runBlocking { saved.await() }
        }
        fun accept(note: net.wastu.memoize.data.Note) {
            val admission = scope.async { source.acceptIncoming(note, session) }
            await("incoming row admitted or rejected") { admission.isCompleted }
            runBlocking { admission.await() }
        }
        fun close() {
            gate.getAndSet(null)?.release?.countDown()
            scope.cancel(); source.detach(editor)
            notes.close(); context.deleteDatabase(database)
        }
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $label" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            Thread.sleep(2)
        }
    }

    private class SaveBarrier {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun hold() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "The synthetic save barrier was not released" }
        }
    }
}
