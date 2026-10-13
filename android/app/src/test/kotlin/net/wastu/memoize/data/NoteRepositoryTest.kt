package net.wastu.memoize.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoteRepositoryTest {
    @Test
    fun bulkActionsKeepRollbackRecoveryAndUndoAtomicAcrossReopening() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "bulk-${UUID.randomUUID()}.db"
        val now = 1_000L
        var notes = NoteRepository.open(context, "bulk-account", database) { now }
        try {
            for (id in listOf("a", "b", "c")) {
                notes.create(id)
                notes.saveEdit(id, EditSnapshot("$id **source** e\u0301 🐱", 3, 5))
            }
            val outside = notes.forAccount("other-account")
            outside.create("outside")
            val original = File(notes.originalsDirectory, "batch-original.txt").apply { writeText("Original attachment bytes") }
            notes.addAttachment("a", NoteAttachment(filename = original.name, mimeType = "text/plain", size = original.length(), localPath = original.path))
            var rejected = false
            try { notes.setState(setOf("a", "outside"), NoteState.ARCHIVED) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(NoteState.NORMAL, notes.get("a")!!.state)
            assertEquals(NoteState.NORMAL, outside.get("outside")!!.state)
            assertEquals(2, notes.setState(setOf("a", "b"), NoteState.ARCHIVED))
            assertEquals(NoteState.ARCHIVED, notes.get("b")!!.state)
            notes.setState(setOf("a", "b"), NoteState.NORMAL)
            rejected = false
            try { notes.markDeleted(setOf("a", "missing")) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(null, notes.get("a")!!.deleteAfter)
            assertTrue(notes.recoveries().isEmpty())
            val deadline = notes.markDeleted(setOf("a", "b"))
            assertEquals(listOf("c"), notes.search().map { it.note.id })
            assertEquals(setOf("a", "b"), notes.recoveries().map { it.noteId }.toSet())
            notes.close()
            notes = NoteRepository.open(context, "bulk-account", database) { now }
            assertEquals(deadline, notes.get("a")!!.deleteAfter)
            assertEquals(deadline, notes.get("b")!!.deleteAfter)
            assertFalse(notes.undoDelete(setOf("a", "missing"), deadline))
            assertEquals(deadline, notes.get("a")!!.deleteAfter)
            assertTrue(notes.undoDelete(setOf("a", "b"), deadline))
            assertEquals(setOf("a", "b", "c"), notes.search().map { it.note.id }.toSet())
            assertEquals("a **source** e\u0301 🐱", notes.get("a")!!.content)
            assertEquals(3, notes.get("a")!!.selectionStart)
            assertEquals("Original attachment bytes", File(requireNotNull(notes.get("a")!!.attachments.single().localPath)).readText())
        } finally { notes.close(); context.deleteDatabase(database) }
    }

    @Test
    fun sortingKeepsTheQueryFiltersHighlightsAndAccountBoundary() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "search-sort-${UUID.randomUUID()}.db"
        var now = 1_000L
        val notes = NoteRepository.open(context, "sort-account", database, clock = { now })
        try {
            for ((id, created, updated) in listOf(
                Triple("a", 10L, 1_000L), Triple("b", 20L, 2_000L),
                Triple("c", 20L, 2_000L), Triple("old", 1L, 3_000L),
            )) {
                now = updated
                notes.create(id, created)
                notes.saveEdit(id, EditSnapshot("#work/project $id\nA NEEDLE in the original source"))
            }
            notes.create("other-tag", 100)
            notes.saveEdit("other-tag", EditSnapshot("#home needle"))
            notes.create("other-query", 200)
            notes.saveEdit("other-query", EditSnapshot("#work/project haystack"))
            val other = notes.forAccount("other-account")
            other.create("outside-account", 1_000)
            other.saveEdit("outside-account", EditSnapshot("#work/project needle"))
            val filter = NoteFilter(query = "needle", tag = "work")
            val expected = mapOf(
                NoteSort.NEWEST to listOf("b", "c", "a", "old"),
                NoteSort.OLDEST to listOf("old", "a", "b", "c"),
                NoteSort.UPDATED to listOf("old", "b", "c", "a"),
            )
            for ((sort, ids) in expected) {
                val results = notes.search(filter.copy(sort = sort))
                assertEquals(ids, results.map { it.note.id })
                assertTrue(results.all { result -> result.matches.isNotEmpty() && result.matches.all {
                    result.note.content.substring(it.start, it.end).equals("needle", ignoreCase = true)
                } })
            }
            val original = java.io.File(notes.originalsDirectory, "sort.txt").apply { writeText("file") }
            notes.addAttachment("b", NoteAttachment(filename = "sort.txt", mimeType = "text/plain", size = 4, localPath = original.path))
            for (sort in NoteSort.entries) {
                assertEquals(listOf("b"), notes.search(filter.copy(sort = sort, attachments = AttachmentFilter.FILES)).map { it.note.id })
            }
            notes.setState("a", NoteState.ARCHIVED)
            assertEquals(listOf("a"), notes.search(filter.copy(sort = NoteSort.UPDATED, state = NoteState.ARCHIVED)).map { it.note.id })
        } finally { notes.close(); context.deleteDatabase(database) }
    }

    @Test
    fun listObserversReceiveEachCommittedSourceEvenWhenTheNoteCountDoesNotChange() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "observe-${UUID.randomUUID()}.db"
        val notes = NoteRepository.open(context, "observe-account", database)
        val updates = Channel<List<NoteResult>>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.Default) { notes.observe().collect { updates.send(it) } }
        suspend fun awaitSource(source: String?) = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (true) {
                    val result = updates.receive()
                    if (result.singleOrNull()?.note?.content == source) break
                }
            }
        }
        try {
            awaitSource(null)
            notes.create("observed")
            notes.saveEdit("observed", EditSnapshot("First committed source"))
            awaitSource("First committed source")
            notes.saveEdit("observed", EditSnapshot("Second committed source"))
            awaitSource("Second committed source")
            assertEquals("Second committed source", notes.search().single().note.content)
        } finally { observer.cancelAndJoin(); notes.close(); context.deleteDatabase(database) }
    }

    @Test
    fun pullBeforeDelayedAutosaveKeepsTheTypedAndIncomingVersionsAcrossRestart() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "pull-edit-${UUID.randomUUID()}.db"
        var notes = NoteRepository.open(context, "pull-account", database)
        val initial = MemoSnapshot("memos/captured-editor", "Original source", 1, 1)
        val editorOrigin = notes.applyRemote(initial).snapshot()
        notes.applyRemote(initial.copy(content = "Incoming server source", updatedAt = 2))
        val edited = notes.saveEdit("captured-editor", EditSnapshot("My unsaved **typing**", origin = editorOrigin))
        assertNotNull(edited.error)
        assertEquals(NoteProblem.CONFLICT, edited.problem)
        notes.close()
        notes = NoteRepository.open(context, "pull-account", database)
        try {
            assertEquals("My unsaved **typing**", notes.get("captured-editor")?.content)
            assertTrue(requireNotNull(notes.get("captured-editor")).hasConflict)
            val preserved = notes.recovery("captured-editor").map { it.snapshot.content }
            assertTrue(preserved.contains("Incoming server source"))
            assertTrue(preserved.contains("My unsaved **typing**"))
            assertEquals(Long.MAX_VALUE, notes.pending().single().retryAt)
        } finally { notes.close(); context.deleteDatabase(database) }
    }

    @Test
    fun combinedFiltersUseSourceAwareHierarchyAttachmentKindAndStableCreationOrder() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "filters-${UUID.randomUUID()}.db"
        val notes = NoteRepository.open(context, "filters-${UUID.randomUUID()}", database)
        try {
            notes.create("blank", 100)
            for (id in listOf("b", "a", "older")) {
                notes.create(id, if (id == "older") 1 else 10)
                notes.saveEdit(id, EditSnapshot("# Headline\n#work/project needle source"))
            }
            notes.create("opaque", 20)
            notes.saveEdit("opaque", EditSnapshot("`#work/project` needle source"))
            val image = java.io.File(notes.originalsDirectory, "test.png").apply { requireNotNull(parentFile).mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            notes.addAttachment("b", NoteAttachment(filename = "test.png", mimeType = "image/png", size = 3, localPath = image.path))
            val file = java.io.File(notes.originalsDirectory, "report.txt").apply { writeText("report") }
            notes.addAttachment("a", NoteAttachment(filename = "report.txt", mimeType = "text/plain", size = 6, localPath = file.path))
            val tagged = NoteFilter(query = "needle", tag = "work")
            assertEquals(listOf("a", "b", "older"), notes.search(tagged).map { it.note.id })
            assertEquals(listOf("b"), notes.search(tagged.copy(attachments = AttachmentFilter.IMAGES)).map { it.note.id })
            assertEquals(listOf("a"), notes.search(tagged.copy(attachments = AttachmentFilter.FILES)).map { it.note.id })
            notes.setState("b", NoteState.ARCHIVED)
            assertEquals(listOf("a", "older"), notes.search(tagged).map { it.note.id })
            assertEquals(listOf("b"), notes.search(tagged.copy(state = NoteState.ARCHIVED)).map { it.note.id })
            assertEquals(listOf("work", "work/project"), notes.tags())
        } finally { notes.close(); context.deleteDatabase(database); notes.originalsDirectory.deleteRecursively() }
    }

    @Test
    fun deepMatchesHaveVisibleSourceExcerptsAndKeepRawMarkdown() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "deep-${UUID.randomUUID()}.db"
        val notes = NoteRepository.open(context, "deep-account", database)
        try {
            val source = "# Title\n" + "body 🐈 line\n".repeat(1_000) + "Needle **literal** source\n"
            notes.create("deep")
            notes.saveEdit("deep", EditSnapshot(source))
            val result = notes.search(NoteFilter(query = "needle")).single()
            assertTrue(result.excerpt.contains("Needle **literal** source"))
            assertTrue(result.excerpt.lineSequence().first().contains("Needle"))
            assertEquals("Needle", result.excerpt.substring(result.excerptMatches.single().start, result.excerptMatches.single().end))
            assertEquals(source.indexOf("Needle"), result.matches.single().start)
            assertEquals(source, result.note.content)
        } finally { notes.close(); context.deleteDatabase(database) }
    }

    @Test
    fun deletionDeadlineAndUndoSurviveReopeningAndRecoverDeletedWork() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "delete-${UUID.randomUUID()}.db"
        var now = 1_000L
        var notes = NoteRepository.open(context, "delete-account", database) { now }
        notes.create("delete-me")
        notes.saveEdit("delete-me", EditSnapshot("Recoverable source"))
        assertEquals(6_000L, notes.markDeleted("delete-me"))
        assertTrue(notes.search().isEmpty())
        notes.close()
        notes = NoteRepository.open(context, "delete-account", database) { now }
        try {
            now = 5_999
            assertTrue(notes.undoDelete("delete-me"))
            assertEquals("Recoverable source", notes.search().single().note.content)
            notes.markDeleted("delete-me")
            now += 5_000
            assertFalse(notes.undoDelete("delete-me"))
            notes.finishDelete("delete-me")
            assertEquals(null, notes.get("delete-me"))
            val recovery = notes.recoveries().single()
            val restored = notes.restoreRecovery(recovery.noteId, recovery.id)
            assertEquals("Recoverable source", restored.content)
            assertTrue(restored.isDirty)
        } finally { notes.close(); context.deleteDatabase(database) }
    }

    @Test
    fun accountsNeverShareSearchResultsDraftsOrRecoveries() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "accounts-${UUID.randomUUID()}.db"
        val first = NoteRepository.open(context, "first", database)
        val second = NoteRepository.open(context, "second", database)
        try {
            first.create("same-local-id")
            first.saveEdit("same-local-id", EditSnapshot("First private needle"))
            second.create("same-local-id")
            second.saveEdit("same-local-id", EditSnapshot("Second private needle"))
            first.preserve("same-local-id", requireNotNull(first.get("same-local-id")).snapshot(), "First recovery")
            assertEquals("Second private needle", second.search(NoteFilter(query = "needle")).single().note.content)
            assertTrue(second.recoveries().isEmpty())
            assertEquals(1, first.recoveries().size)
        } finally { first.close(); second.close(); context.deleteDatabase(database) }
    }

    @Test
    fun literalSearchMatchesTheSharedMacContractWithoutChangingSource() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "search-${UUID.randomUUID()}.db"
        val notes = NoteRepository.open(context, "search-account", database)
        try {
            val fixtures = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/search-parity.json")).readText()).jsonArray
            for ((index, fixture) in fixtures.withIndex()) {
                val record = fixture.jsonObject
                val source = record.getValue("source").jsonPrimitive.content
                val query = record.getValue("query").jsonPrimitive.content
                val expected = record.getValue("ranges").jsonArray.map { pair ->
                    val values = pair.jsonArray
                    val start = values[0].jsonPrimitive.content.toInt()
                    TextMatch(start, start + values[1].jsonPrimitive.content.toInt())
                }
                val note = notes.create("fixture-$index")
                notes.saveEdit(note.id, EditSnapshot(source))
                val result = notes.search(NoteFilter(query = query)).find { it.note.id == note.id }
                assertEquals(record.getValue("name").jsonPrimitive.content, expected, result?.matches.orEmpty())
                assertEquals(source, notes.get(note.id)?.content)
                if (expected.isNotEmpty()) assertTrue("Indexed candidates must include every literal match", result != null)
            }
        } finally {
            notes.close()
            context.deleteDatabase(database)
        }
    }

    @Test
    fun committedSourceAndPendingRevisionSurviveReopening() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "test-${UUID.randomUUID()}.db"
        var notes = NoteRepository.open(context, "account-a", database)
        val draft = notes.create()
        val saved = notes.saveEdit(draft.id, EditSnapshot("# hello\nLiteral **source** 📝", 31, 31))
        notes.close()
        notes = NoteRepository.open(context, "account-a", database)
        try {
            assertEquals("# hello\nLiteral **source** 📝", notes.get(draft.id)?.content)
            assertEquals(saved.revision, notes.pending().single().revision)
            assertTrue(notes.get(draft.id)!!.isDirty)
        } finally {
            notes.close()
            context.deleteDatabase(database)
        }
    }

    @Test
    fun anOfflineDeletionHasRecoverableSourceAndOriginalsBeforeAnyWorkerFinishes() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "offline-delete-${UUID.randomUUID()}.db"
        var now = 1_800_000_000_000L
        var notes = NoteRepository.open(context, databaseName = database, clock = { now })
        try {
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Offline source must remain recoverable 📝"))
            val original = File(notes.originalsDirectory, "offline-original").apply { writeText("Exact private original bytes") }
            notes.addAttachment(note.id, NoteAttachment(filename = "offline.txt", mimeType = "text/plain",
                size = original.length(), localPath = original.absolutePath))
            val deadline = notes.markDeleted(note.id)
            assertTrue(notes.search().isEmpty())
            assertTrue(notes.recovery(note.id).any { it.reason == "Deleted note" })
            notes.close()
            notes = NoteRepository.open(context, databaseName = database, clock = { now })
            now = deadline + 1
            assertFalse(notes.undoDelete(note.id))
            val recovery = notes.recoveries().first { it.noteId == note.id && it.reason == "Deleted note" }
            assertEquals("Offline source must remain recoverable 📝", recovery.snapshot.content)
            assertTrue(notes.protectedPaths().contains(original.absolutePath))
            val restored = notes.restoreRecovery(note.id, recovery.id)
            assertEquals(recovery.snapshot.content, restored.content)
            assertEquals(null, restored.deleteAfter)
            assertEquals("Exact private original bytes", File(requireNotNull(restored.attachments.single().localPath)).readText())
            assertEquals(restored.id, notes.search().single().note.id)
        } finally { notes.close(); context.deleteDatabase(database) }
    }

    @Test
    fun theFirstAccountClaimsDeletedLocalRecoveryWithoutResurrectingOrSharingWithLaterAccounts() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "deleted-local-claim-${UUID.randomUUID()}.db"
        val local = NoteRepository.open(context, databaseName = database)
        try {
            val deleted = local.create()
            local.saveEdit(deleted.id, EditSnapshot("Earlier offline version remains recoverable"))
            val original = File(local.originalsDirectory, "deleted-local-${UUID.randomUUID()}").apply { writeText("First-account recovery bytes") }
            local.addAttachment(deleted.id, NoteAttachment(filename = "deleted.txt", mimeType = "text/plain",
                size = original.length(), localPath = original.absolutePath))
            local.preserve(deleted.id, requireNotNull(local.get(deleted.id)).snapshot(), "Earlier local version")
            local.saveEdit(deleted.id, EditSnapshot("Deleted offline source stays recoverable after connection 📝"))
            local.markDeleted(deleted.id)
            val first = local.forAccount("first-account")
            assertEquals(0, first.importLocalDrafts())
            assertTrue(first.all().isEmpty())
            val recovery = first.recoveries().first { it.noteId == deleted.id && it.reason == "Deleted note" }
            assertEquals("Deleted offline source stays recoverable after connection 📝", recovery.snapshot.content)
            assertEquals(setOf("Earlier offline version remains recoverable", "Deleted offline source stays recoverable after connection 📝"),
                first.recoveries().map { it.snapshot.content }.toSet())
            val restored = first.restoreRecovery(deleted.id, recovery.id)
            assertFalse(restored.id == deleted.id)
            val restoredOriginal = File(requireNotNull(restored.attachments.single().localPath))
            assertEquals(first.originalsDirectory.canonicalFile, requireNotNull(restoredOriginal.parentFile).canonicalFile)
            assertEquals("First-account recovery bytes", restoredOriginal.readText())
            val later = local.forAccount("later-account")
            assertEquals(0, later.importLocalDrafts())
            assertTrue(later.all().isEmpty())
            assertTrue(later.recoveries().isEmpty())
            assertTrue(later.protectedPaths().contains(restoredOriginal.absolutePath))
        } finally { local.close(); context.deleteDatabase(database) }
    }
}
