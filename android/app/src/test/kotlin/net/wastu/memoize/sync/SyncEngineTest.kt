package net.wastu.memoize.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import net.wastu.memoize.data.EditSnapshot
import net.wastu.memoize.data.MemoSnapshot
import net.wastu.memoize.data.NoteAttachment
import net.wastu.memoize.data.NoteRepository
import net.wastu.memoize.data.NoteState
import net.wastu.memoize.data.NoteVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncEngineTest {
    @Test
    fun aCommittedLocalRevisionBecomesOnePrivateServerNote() = runTest {
        withNotes { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Offline saved source"))
            val api = FakeMemos()
            SyncEngine(notes, api).synchronizeOnce()
            assertEquals("Offline saved source", api.get(note.id)?.content)
            assertEquals("PRIVATE", api.get(note.id)?.visibility?.name)
            assertFalse(notes.get(note.id)!!.isDirty)
            assertTrue(notes.pending().isEmpty())
        }
    }

    @Test
    fun aLostCreateResponseRecoversByCallerIdentityWithANewWorker() = runTest {
        withNotes { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Saved before an ambiguous create"))
            val api = FakeMemos().apply { failAfterCreate = true; hideFromList = true }
            try { SyncEngine(notes, api).synchronizeOnce() } catch (_: IOException) { }
            assertEquals("Saved before an ambiguous create", notes.get(note.id)?.sent?.content)
            SyncEngine(notes, api).synchronizeOnce(force = true)
            api.hideFromList = false
            assertEquals(1, api.list().size)
            assertFalse(notes.get(note.id)!!.isDirty)
            assertTrue(notes.pending().isEmpty())
        }
    }

    @Test
    fun deletionWaitsForItsUndoDeadlineAndThenRemovesTheServerNote() = runTest {
        var now = 1_800_000_000_000L
        withNotes(clock = { now }) { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Delete with a durable Undo window"))
            val api = FakeMemos()
            val engine = SyncEngine(notes, api, clock = { now })
            engine.synchronizeOnce()
            notes.markDeleted(note.id)
            engine.synchronizeOnce()
            assertEquals("Delete with a durable Undo window", api.get(note.id)?.content)
            assertTrue(notes.get(note.id) != null)
            now += 5_001
            engine.synchronizeOnce()
            assertEquals(null, api.get(note.id))
            assertEquals(null, notes.get(note.id))
            assertTrue(notes.recovery(note.id).any { it.snapshot.content == "Delete with a durable Undo window" })
        }
    }

    @Test
    fun aConfirmedRemoteDeletionPreservesCleanNotesInRecovery() = runTest {
        withNotes { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Previously synchronized source"))
            val api = FakeMemos()
            val engine = SyncEngine(notes, api)
            engine.synchronizeOnce()
            api.delete(note.id)
            engine.synchronizeOnce()
            assertEquals(null, notes.get(note.id))
            assertTrue(notes.recovery(note.id).any { it.snapshot.content == "Previously synchronized source" })
        }
    }

    @Test
    fun authenticationFailurePausesPendingWorkForANewWorker() = runTest {
        withNotes { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Never discard saved text after an expired token"))
            val api = FakeMemos().apply { listFailure = MemosApiException(401, "Synthetic expired token") }
            try { SyncEngine(notes, api).synchronizeOnce() } catch (_: MemosApiException) { }
            assertEquals("true", notes.metadata("authenticationPaused"))
            api.listFailure = null
            SyncEngine(notes, api).synchronizeOnce()
            assertEquals(null, api.get(note.id))
            assertTrue(notes.get(note.id)!!.isDirty)
        }
    }

    @Test
    fun anOfflineOriginalIsUploadedBeforeTheMemoIsAssociated() = runTest {
        withNotes { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Offline original file"))
            val original = File(notes.originalsDirectory, UUID.randomUUID().toString())
            original.writeText("Exact original bytes 🐈")
            val file = NoteAttachment(filename = "Notes [日本語].txt", mimeType = "text/plain",
                size = original.length(), localPath = original.absolutePath)
            notes.addAttachment(note.id, file)
            val api = FakeMemos()
            SyncEngine(notes, api).synchronizeOnce()
            assertEquals("Notes [日本語].txt", api.get(note.id)?.attachments?.single()?.filename)
            assertTrue(notes.get(note.id)!!.attachments.single().uploaded)
            val downloaded = File(notes.cacheDirectory, "verification")
            api.download(api.get(note.id)!!.attachments.single(), downloaded)
            assertEquals("Exact original bytes 🐈", downloaded.readText())
        }
    }

    @Test
    fun editsAndAttachmentMutationsStayPausedUntilAnExplicitRecoveryChoice() = runTest {
        withNotes { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Shared base"))
            val api = FakeMemos()
            val engine = SyncEngine(notes, api)
            engine.synchronizeOnce()
            notes.saveEdit(note.id, EditSnapshot("Local conflict"))
            val remote = api.get(note.id)!!
            api.update(remote.copy(content = "Concurrent server edit"), remote)
            engine.synchronizeOnce()
            notes.saveEdit(note.id, EditSnapshot("More local typing while paused"))
            val original = File(notes.originalsDirectory, UUID.randomUUID().toString()).apply { writeText("Keep local original") }
            notes.addAttachment(note.id, NoteAttachment(filename = "pending.txt", mimeType = "text/plain", size = original.length(), localPath = original.absolutePath))
            notes.setState(note.id, NoteState.ARCHIVED)
            engine.synchronizeOnce(force = true)
            assertEquals("Concurrent server edit", api.get(note.id)?.content)
            assertEquals(NoteRepository.CONFLICT_MESSAGE, notes.get(note.id)?.error)
            val chosen = notes.recovery(note.id).first { it.snapshot.content == "Local conflict" }
            notes.restoreRecovery(note.id, chosen.id)
            engine.synchronizeOnce()
            assertEquals("Local conflict", api.get(note.id)?.content)
            assertFalse(notes.get(note.id)!!.isDirty)
            assertTrue(notes.recovery(note.id).any { it.snapshot.content == "Concurrent server edit" })
            assertTrue(notes.recovery(note.id).any { it.snapshot.content == "More local typing while paused" && it.snapshot.attachments.single().filename == "pending.txt" })
        }
    }

    @Test
    fun typingAfterACleanRemoteDeletionCreatesOneNewPrivateNoteWithIndependentFiles() = runTest {
        withNotes { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Clean note before unsaved typing"))
            val original = File(notes.originalsDirectory, UUID.randomUUID().toString()).apply { writeText("Original survives the deletion") }
            notes.addAttachment(note.id, NoteAttachment(filename = "saved.txt", mimeType = "text/plain", size = original.length(), localPath = original.absolutePath))
            val api = FakeMemos()
            val engine = SyncEngine(notes, api)
            engine.synchronizeOnce()
            val origin = notes.get(note.id)!!.snapshot()
            api.delete(note.id)
            engine.synchronizeOnce()
            assertEquals(null, notes.get(note.id))
            val saved = notes.saveEdit(note.id, EditSnapshot("Typed while the deletion arrived", origin = origin))
            assertTrue(saved.id != note.id)
            assertEquals(NoteVisibility.PRIVATE, saved.visibility)
            assertEquals(null, saved.remoteName)
            assertTrue(saved.attachments.single().id != origin.attachments.single().id)
            assertFalse(saved.attachments.single().uploaded)
            notes.saveEdit(saved.id, EditSnapshot("Continued typing on the saved clone", origin = saved.snapshot()))
            assertEquals(1, notes.all().size)
            engine.synchronizeOnce()
            assertEquals(null, api.get(note.id))
            assertEquals("Continued typing on the saved clone", api.get(saved.id)?.content)
            assertTrue(notes.recovery(note.id).any { it.snapshot.content == origin.content })
            assertTrue(notes.protectedPaths().contains(original.absolutePath))
            assertEquals("Original survives the deletion", original.readText())
        }
    }

    @Test
    fun anUploadAndLateAcknowledgmentCannotAcknowledgeNewerTyping() = runTest {
        withNotes { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Source captured by the send"))
            val original = File(notes.originalsDirectory, UUID.randomUUID().toString()).apply { writeText("Original for an in-flight revision") }
            val pending = NoteAttachment(filename = "in-flight.txt", mimeType = "text/plain", size = original.length(), localPath = original.absolutePath)
            notes.addAttachment(note.id, pending)
            val origin = notes.get(note.id)!!.snapshot()
            val uploadedBody = origin.copy(attachments = origin.attachments.map { it.copy(remoteName = "attachments/${it.id}", uploaded = true) })
            assertTrue("Caller identity stays the same before and after upload", origin.sameBody(uploadedBody))
            val api = FakeMemos().apply {
                onCreate = {
                    notes.saveEdit(note.id, EditSnapshot("Newer typing while upload/create was in flight", origin = origin))
                }
            }
            val engine = SyncEngine(notes, api)
            engine.synchronizeOnce()
            val newer = notes.get(note.id)!!
            assertEquals("Newer typing while upload/create was in flight", newer.content)
            assertTrue(newer.isDirty)
            assertEquals(null, newer.error)
            assertEquals("Source captured by the send", api.get(note.id)?.content)
            engine.synchronizeOnce()
            assertEquals(newer.content, api.get(note.id)?.content)
            assertFalse(notes.get(note.id)!!.isDirty)
            assertEquals(1, api.list().size)
        }
    }

    @Test
    fun attachmentRemovalHonorsUndoAndRetriesCleanupWithTheOriginalProtected() = runTest {
        var now = 1_800_000_000_000L
        withNotes(clock = { now }) { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Attachment cleanup preserves recoverable bytes"))
            val original = File(notes.originalsDirectory, UUID.randomUUID().toString()).apply { writeText("Restore these exact original bytes") }
            val file = NoteAttachment(filename = "recovery.txt", mimeType = "text/plain", size = original.length(), localPath = original.absolutePath)
            notes.addAttachment(note.id, file)
            val api = FakeMemos()
            val engine = SyncEngine(notes, api, clock = { now }, jitter = { 1.0 })
            engine.synchronizeOnce()
            notes.removeAttachment(note.id, file.id)
            engine.synchronizeOnce()
            assertEquals(file.id, api.get(note.id)?.attachments?.single()?.id)
            assertTrue(notes.undoRemoveAttachment(note.id, file.id))
            engine.synchronizeOnce()
            assertTrue(notes.attachmentCleanup().isEmpty())
            notes.removeAttachment(note.id, file.id)
            now += 5_001
            api.deleteAttachmentFailure = MemosApiException(503, "Synthetic cleanup unavailable", 60_000)
            try { engine.synchronizeOnce() } catch (_: MemosApiException) { }
            assertTrue(api.get(note.id)!!.attachments.isEmpty())
            assertEquals(1, notes.attachmentCleanup().size)
            assertEquals(now + 60_000, notes.metadata("retryAt")?.toLongOrNull())
            api.deleteAttachmentFailure = null
            SyncEngine(notes, api, clock = { now }).synchronizeOnce()
            assertTrue(api.getAttachment(file.id) != null)
            now += 60_001
            SyncEngine(notes, api, clock = { now }).synchronizeOnce()
            assertEquals(null, api.getAttachment(file.id))
            assertTrue(notes.attachmentCleanup().isEmpty())
            assertTrue(notes.protectedPaths().contains(original.absolutePath))
            val recovery = notes.recovery(note.id).first { it.reason == "Before removing recovery.txt" }
            notes.restoreRecovery(note.id, recovery.id)
            engine.synchronizeOnce()
            assertEquals(file.id, api.get(note.id)?.attachments?.single()?.id)
            val downloaded = File(notes.cacheDirectory, "restored-original")
            api.download(api.get(note.id)!!.attachments.single(), downloaded)
            assertEquals("Restore these exact original bytes", downloaded.readText())
        }
    }

    @Test
    fun retryAfterAndPendingSourceSurviveAnActualDatabaseReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = "backoff-${UUID.randomUUID()}.db"
        var now = 1_800_000_000_000L
        var notes = NoteRepository.open(context, "synthetic-account", database, clock = { now })
        try {
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Persist exact source and Retry-After across process death"))
            val api = FakeMemos().apply { listFailure = MemosApiException(429, "Synthetic throttling", 60_000) }
            try { SyncEngine(notes, api, clock = { now }, jitter = { 1.0 }).synchronizeOnce() } catch (_: MemosApiException) { }
            notes.close()
            notes = NoteRepository.open(context, "synthetic-account", database, clock = { now })
            assertEquals(now + 60_000, notes.metadata("retryAt")?.toLongOrNull())
            assertEquals("Persist exact source and Retry-After across process death", notes.get(note.id)?.content)
            api.listFailure = null
            SyncEngine(notes, api, clock = { now }).synchronizeOnce()
            assertEquals(null, api.get(note.id))
            now += 60_001
            SyncEngine(notes, api, clock = { now }).synchronizeOnce()
            assertEquals(notes.get(note.id)?.content, api.get(note.id)?.content)
            assertFalse(notes.get(note.id)!!.isDirty)
        } finally { notes.close(); context.deleteDatabase(database) }
    }

    @Test
    fun cancellationAfterServerPersistenceLeavesAnExactRecoverableSend() = runTest {
        withNotes { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Saved before a canceled response"))
            val api = FakeMemos().apply { onCreate = { throw CancellationException("Synthetic transfer cancellation") } }
            try { SyncEngine(notes, api).synchronizeOnce() } catch (_: CancellationException) { }
            assertEquals("Saved before a canceled response", notes.get(note.id)?.sent?.content)
            assertTrue(notes.get(note.id)!!.isDirty)
            assertTrue(notes.recovery(note.id).any { it.snapshot.content == "Saved before a canceled response" })
            api.onCreate = null
            SyncEngine(notes, api).synchronizeOnce()
            assertEquals(1, api.list().size)
            assertFalse(notes.get(note.id)!!.isDirty)
        }
    }

    @Test
    fun anAmbiguousUpdateReconcilesItsExactBodyBeforePublishingNewerTyping() = runTest {
        withNotes { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Initial shared source"))
            val api = FakeMemos()
            val engine = SyncEngine(notes, api)
            engine.synchronizeOnce()
            notes.saveEdit(note.id, EditSnapshot("Server persisted this update before the lost response"))
            api.failAfterUpdate = true
            api.hideFromList = true
            try { engine.synchronizeOnce() } catch (_: IOException) { }
            assertEquals("Server persisted this update before the lost response", notes.get(note.id)?.sent?.content)
            notes.saveEdit(note.id, EditSnapshot("Newer typing after the ambiguous update"))
            SyncEngine(notes, api).synchronizeOnce(force = true)
            api.hideFromList = false
            assertEquals(1, api.list().size)
            assertEquals("Newer typing after the ambiguous update", api.get(note.id)?.content)
            assertFalse(notes.get(note.id)!!.isDirty)
            assertEquals(null, notes.get(note.id)?.error)
            assertTrue(notes.recovery(note.id).any { it.snapshot.content == "Server persisted this update before the lost response" })
        }
    }

    @Test
    fun twoStaleEditorsReuseOneDeletedNoteReplacementAndRetainBothTypedVersions() = runTest {
        withNotes { notes ->
            val note = notes.create()
            notes.saveEdit(note.id, EditSnapshot("Shared editor origin"))
            val api = FakeMemos()
            val engine = SyncEngine(notes, api)
            engine.synchronizeOnce()
            val origin = notes.get(note.id)!!.snapshot()
            api.delete(note.id)
            engine.synchronizeOnce()
            val first = notes.saveEdit(note.id, EditSnapshot("Typing in the first editor", origin = origin))
            val second = notes.saveEdit(note.id, EditSnapshot("Typing in the second editor", origin = origin))
            assertEquals(first.id, second.id)
            assertEquals(1, notes.all().size)
            assertEquals(NoteRepository.CONFLICT_MESSAGE, second.error)
            assertTrue(notes.recovery(first.id).any { it.snapshot.content == first.content })
            val selected = notes.recovery(first.id).first { it.snapshot.content == second.content }
            notes.restoreRecovery(first.id, selected.id)
            engine.synchronizeOnce()
            assertEquals(second.content, api.get(first.id)?.content)
            assertEquals(null, api.get(note.id))
            assertEquals(1, api.list().size)
        }
    }

    @Test
    fun lateResponsesAndFailuresCannotResolveAPausedEditorConflict() = runTest {
        for (loseResponse in listOf(false, true)) {
            withNotes { notes ->
                val note = notes.create()
                val displayed = notes.saveEdit(note.id, EditSnapshot("Shared displayed origin")).snapshot()
                notes.saveEdit(note.id, EditSnapshot("First editor committed source"))
                val api = FakeMemos().apply {
                    onCreate = {
                        notes.saveEdit(note.id, EditSnapshot("Second editor typed during the request", origin = displayed))
                        if (loseResponse) throw IOException("Synthetic response lost after an editor conflict")
                    }
                }
                val engine = SyncEngine(notes, api)
                try { engine.synchronizeOnce() } catch (_: IOException) { }
                assertEquals(NoteRepository.CONFLICT_MESSAGE, notes.get(note.id)?.error)
                engine.synchronizeOnce(force = true)
                assertEquals("First editor committed source", api.get(note.id)?.content)
                assertEquals(NoteRepository.CONFLICT_MESSAGE, notes.get(note.id)?.error)
                val selected = notes.recovery(note.id).first { it.snapshot.content == "Second editor typed during the request" }
                notes.restoreRecovery(note.id, selected.id)
                engine.synchronizeOnce()
                assertEquals(selected.snapshot.content, api.get(note.id)?.content)
                assertFalse(notes.get(note.id)!!.isDirty)
            }
        }
    }

    @Test
    fun cachedServerBytesSurviveRemoteRemovalAndCacheEviction() = runTest {
        for (deleteNote in listOf(false, true)) {
            withNotes { notes ->
                val bytes = "Cached image and file bytes remain recoverable 📝".toByteArray(Charsets.UTF_8)
                val attachment = NoteAttachment(id = "cached-file", remoteName = "attachments/cached-file",
                    filename = "original.txt", mimeType = "text/plain", size = bytes.size.toLong(), uploaded = true)
                val remote = MemoSnapshot("memos/${UUID.randomUUID()}", "Server note with a cached attachment",
                    1_800_000_000_000, 1_800_000_000_000, attachments = listOf(attachment))
                val note = notes.applyRemote(remote)
                val cached = notes.cachedAttachmentFile(attachment.id).apply { writeBytes(bytes) }
                if (deleteNote) notes.remoteMissing(note.id)
                else notes.applyRemote(remote.copy(attachments = emptyList()))
                val recovery = notes.recovery(note.id).first { it.snapshot.attachments.any { file -> file.id == attachment.id } }
                val original = File(requireNotNull(recovery.snapshot.attachments.single().localPath))
                assertEquals(notes.originalsDirectory.canonicalFile, requireNotNull(original.parentFile).canonicalFile)
                assertTrue(cached.delete())
                assertTrue(original.readBytes().contentEquals(bytes))
                assertTrue(notes.forAccount("another-account").protectedPaths().contains(original.absolutePath))
                val restored = notes.restoreRecovery(note.id, recovery.id)
                assertEquals(NoteVisibility.PRIVATE, restored.visibility)
                assertTrue(File(requireNotNull(restored.attachments.single().localPath)).readBytes().contentEquals(bytes))
                assertFalse(restored.attachments.single().uploaded)
            }
        }
    }

    @Test
    fun pendingCachePathsBecomePrivateOriginalsAndTruncatedPathsAreRejected() = runTest {
        withNotes { notes ->
            val cached = notes.cachedAttachmentFile(UUID.randomUUID().toString()).apply { writeText("Complete pending bytes") }
            val attachment = NoteAttachment(filename = "pending.txt", mimeType = "text/plain",
                size = cached.length(), localPath = cached.absolutePath)
            val kept = notes.keepAttachmentOriginal(attachment)
            val original = File(requireNotNull(kept.localPath))
            assertEquals(notes.originalsDirectory.canonicalFile, requireNotNull(original.parentFile).canonicalFile)
            assertTrue(cached.delete())
            assertEquals("Complete pending bytes", original.readText())
            val truncated = File(notes.originalsDirectory, "truncated-${UUID.randomUUID()}").apply { writeText("cut") }
            val result = runCatching { notes.keepAttachmentOriginal(attachment.copy(localPath = truncated.absolutePath)) }
            assertTrue(result.isFailure)
            assertEquals("cut", truncated.readText())
        }
    }

    private suspend fun withNotes(clock: () -> Long = System::currentTimeMillis, block: suspend (NoteRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = "sync-${UUID.randomUUID()}.db"
        val notes = NoteRepository.open(context, "synthetic-account", database, clock)
        try { block(notes) } finally { notes.close(); context.deleteDatabase(database) }
    }
}

private class FakeMemos : MemosApi {
    private val memos = linkedMapOf<String, MemoSnapshot>()
    private val files = linkedMapOf<String, Pair<NoteAttachment, ByteArray>>()
    var failAfterCreate = false
    var failAfterUpdate = false
    var hideFromList = false
    var listFailure: Exception? = null
    var deleteAttachmentFailure: Exception? = null
    var onCreate: (suspend () -> Unit)? = null
    override suspend fun list(): List<MemoSnapshot> {
        listFailure?.let { throw it }
        return if (hideFromList) emptyList() else memos.values.toList()
    }
    override suspend fun get(id: String): MemoSnapshot? = memos[id.removePrefix("memos/")]
    override suspend fun create(id: String, snapshot: MemoSnapshot): MemoSnapshot {
        val created = snapshot.copy(remoteName = "memos/$id")
        if (created.attachments.any { file -> files[file.id] == null }) throw MemosApiException(400, "Synthetic attachment missing")
        check(memos.putIfAbsent(id, created) == null) { "Duplicate caller memo identity" }
        if (failAfterCreate) { failAfterCreate = false; throw IOException("Synthetic response lost after persistence") }
        onCreate?.invoke()
        return created
    }
    override suspend fun update(snapshot: MemoSnapshot, base: MemoSnapshot?): MemoSnapshot =
        snapshot.also {
            memos[requireNotNull(it.remoteName).removePrefix("memos/")] = it
            if (failAfterUpdate) { failAfterUpdate = false; throw IOException("Synthetic response lost after update persistence") }
        }
    override suspend fun delete(id: String) { memos.remove(id.removePrefix("memos/")) }
    override suspend fun deleteAttachment(id: String) {
        deleteAttachmentFailure?.let { throw it }
        files.remove(id.removePrefix("attachments/"))
    }
    override suspend fun getAttachment(id: String): NoteAttachment? = files[id.removePrefix("attachments/")]?.first
    override suspend fun upload(attachment: NoteAttachment, file: File): NoteAttachment =
        attachment.copy(remoteName = "attachments/${attachment.id}", uploaded = true).also {
            files[attachment.id] = it to file.readBytes()
        }
    override suspend fun download(attachment: NoteAttachment, destination: File) {
        val bytes = files[attachment.id]?.second ?: throw MemosApiException(404, "Synthetic file missing")
        destination.writeBytes(bytes)
    }
}
