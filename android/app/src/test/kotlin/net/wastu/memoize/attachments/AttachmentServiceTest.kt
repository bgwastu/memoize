package net.wastu.memoize.attachments

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.app.Application
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.provider.OpenableColumns
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import net.wastu.memoize.R
import net.wastu.memoize.data.NoteRepository
import net.wastu.memoize.data.NoteAttachment
import net.wastu.memoize.data.MemoSnapshot
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentServiceTest {
    private lateinit var context: Context
    private lateinit var notes: NoteRepository
    private lateinit var provider: FixtureProvider
    private lateinit var service: MemoizeAttachmentService
    private val database = "attachment-${UUID.randomUUID()}.db"
    private val uri = Uri.parse("content://memoize.synthetic.files/original")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notes = NoteRepository.open(context, "attachment-${UUID.randomUUID()}", database)
        provider = Robolectric.buildContentProvider(FixtureProvider::class.java).create("memoize.synthetic.files").get()
        provider.source = File.createTempFile("synthetic-", ".bin", context.cacheDir)
        // Reattach the manifest-configured provider for each sandbox's private directory.
        // Robolectric's API 33 ProviderInfo flags overload is not implemented yet.
        val sharingInfo = ProviderInfo().apply {
            name = AttachmentFileProvider::class.java.name
            packageName = context.packageName
            applicationInfo = context.applicationInfo
            authority = "${context.packageName}.attachments"
            exported = false
            grantUriPermissions = true
            metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.attachment_paths) }
        }
        Robolectric.buildContentProvider(AttachmentFileProvider::class.java).create(sharingInfo).get()
        service = MemoizeAttachmentService(context) { _, _, _ -> error("No network was requested") }
    }

    @After fun tearDown() {
        notes.close()
        context.deleteDatabase(database)
        provider.source.delete()
        notes.originalsDirectory.deleteRecursively()
        notes.cacheDirectory.deleteRecursively()
    }

    @Test fun selectedOriginalIsDurableBeforeItAppearsInTheNote() = runTest {
        val bytes = "first **original** 日本語\u0000last".toByteArray(Charsets.UTF_8)
        provider.source.writeBytes(bytes)
        provider.filename = "日本語 — original.bin"
        val draft = notes.create()
        val file = service.import(notes, draft.id, uri)
        provider.source.delete()
        notes.close()
        notes = NoteRepository.open(context, draft.account, database)

        val saved = notes.get(draft.id)!!.attachments.single()
        assertEquals(file.id, saved.id)
        assertEquals("日本語 — original.bin", saved.filename)
        assertEquals("application/octet-stream", saved.mimeType)
        assertEquals(bytes.size.toLong(), saved.size)
        assertArrayEquals(bytes, File(saved.localPath!!).readBytes())
        assertFalse(saved.uploaded)
        assertTrue(notes.get(draft.id)!!.isDirty)
        assertEquals(notes.get(draft.id)!!.revision, notes.pending().single().revision)
    }

    @Test fun interruptedProviderCopyNeverConfirmsAPartialAttachment() = runTest {
        provider.source.writeBytes(byteArrayOf(1, 2, 3))
        provider.reportedSize = 196_621
        val draft = notes.create()

        val failure = runCatching { service.import(notes, draft.id, uri) }.exceptionOrNull()

        assertNotNull("A prematurely ended provider stream must fail", failure)
        assertTrue(notes.get(draft.id)!!.attachments.isEmpty())
        assertTrue(notes.originalsDirectory.listFiles()!!.isEmpty())
        assertTrue(notes.pending().isEmpty())
    }

    @Test fun emptyAndMultiChunkOriginalsRetainTheirExactBytesAndMetadata() = runTest {
        for (bytes in listOf(ByteArray(0), ByteArray(2_500_123) { (it * 31 + 7).toByte() })) {
            provider.source.writeBytes(bytes)
            provider.filename = if (bytes.isEmpty()) "空 — empty.bin" else "Report 日本語.bin"
            provider.mimeType = "application/x-memoize-test"
            val draft = notes.create()
            val file = service.import(notes, draft.id, uri)

            assertEquals(provider.filename, file.filename)
            assertEquals("application/x-memoize-test", file.mimeType)
            assertEquals(bytes.size.toLong(), file.size)
            assertArrayEquals(bytes, File(file.localPath!!).readBytes())
            assertEquals(file, notes.get(draft.id)!!.attachments.single())
        }
    }

    @Test fun cancellingAnImportStopsReadingTheProviderAndLeavesNoAssociation() = runTest {
        val bytes = ByteArray(2_500_123) { (it * 17).toByte() }
        provider.source.writeBytes(bytes)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val consumed = AtomicInteger()
        val closed = AtomicBoolean()
        val input = object : InputStream() {
            override fun read(): Int = error("Use bounded reads")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (consumed.get() == 0) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                val start = consumed.get()
                if (start == bytes.size) return -1
                val count = minOf(length, bytes.size - start)
                bytes.copyInto(buffer, offset, start, start + count)
                consumed.addAndGet(count)
                return count
            }
            override fun close() { closed.set(true) }
        }
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { input }
        val draft = notes.create()
        val job = launch(Dispatchers.IO) { service.import(notes, draft.id, uri) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancel()
            release.countDown()
            job.join()
            assertTrue("Cancellation must stop consuming the selected file", consumed.get() < bytes.size)
            assertTrue(closed.get())
            assertTrue(notes.get(draft.id)!!.attachments.isEmpty())
            assertTrue(notes.pending().isEmpty())
            assertTrue(notes.originalsDirectory.listFiles()!!.isEmpty())
        } finally {
            release.countDown()
            job.cancel()
            job.join()
        }
    }

    @Test fun expiredPickerAccessExplainsHowToRetryWithoutChangingTheNote() = runTest {
        provider.source.writeText("protected original")
        val draft = notes.create()
        val committed = service.import(notes, draft.id, uri)
        provider.accessExpired = true

        val failure = runCatching { service.import(notes, draft.id, uri) }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue("The user must be told to select the file again", failure!!.message!!.contains("Select it again"))
        assertFalse(failure.message!!.contains("provider internals"))
        assertEquals(listOf(committed), notes.get(draft.id)!!.attachments)
        assertEquals("protected original", File(committed.localPath!!).readText())
        assertEquals(1, notes.originalsDirectory.listFiles()!!.size)
    }

    @Test fun storageFailureExplainsHowToRetryAndPreservesThePreviousOriginal() = runTest {
        provider.source.writeText("sole original")
        val draft = notes.create()
        val committed = service.import(notes, draft.id, uri)
        val before = notes.get(draft.id)
        val pending = notes.pending()
        val retained = File(notes.originalsDirectory.parentFile, "retained-${UUID.randomUUID()}")
        assertTrue(notes.originalsDirectory.renameTo(retained))
        notes.originalsDirectory.writeText("Synthetic storage obstruction")
        val failure = try {
            runCatching { service.import(notes, draft.id, uri) }.exceptionOrNull()
        } finally {
            assertTrue(notes.originalsDirectory.delete())
            assertTrue(retained.renameTo(notes.originalsDirectory))
        }

        assertNotNull(failure)
        assertTrue("Storage failures need actionable feedback", failure!!.message!!.contains("available storage"))
        assertEquals(before, notes.get(draft.id))
        assertEquals(pending, notes.pending())
        assertEquals("sole original", File(committed.localPath!!).readText())
        assertEquals(1, notes.originalsDirectory.listFiles()!!.size)
    }

    @Test fun openingAnOfflineOriginalSharesItsBytesNameAndMimeWithReadOnlyAccess() = runTest {
        val bytes = "%PDF-1.7\nsynthetic\n".toByteArray(Charsets.UTF_8)
        provider.source.writeBytes(bytes)
        provider.filename = "日本語 — résumé.pdf"
        provider.mimeType = "application/pdf"
        val file = service.import(notes, notes.create().id, uri)
        provider.source.delete()

        service.open(context, notes, file)

        val intent = Shadows.shadowOf(context as Application).nextStartedActivity
        val shared = requireNotNull(intent.data)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", shared.scheme)
        assertEquals("${context.packageName}.attachments", shared.authority)
        assertEquals("application/pdf", intent.type)
        assertEquals("application/pdf", context.contentResolver.getType(shared))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.contentResolver.query(shared, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("日本語 — résumé.pdf", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(bytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        assertArrayEquals(bytes, context.contentResolver.openInputStream(shared)!!.use { it.readBytes() })
        assertTrue("A viewer cannot modify an immutable original", runCatching {
            context.contentResolver.openFileDescriptor(shared, "rw")?.use { }
        }.isFailure)
        assertArrayEquals(bytes, File(file.localPath!!).readBytes())
    }

    @Test fun aServerImageDownloadsOnDemandAndCanBeReopenedOfflineAfterRestart() = runTest {
        val bytes = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        val fileId = UUID.randomUUID().toString()
        val file = NoteAttachment(id = fileId, remoteName = "attachments/$fileId", filename = "写真 — 猫.png",
            mimeType = "image/png", size = bytes.size.toLong(), uploaded = true)
        val note = notes.applyRemote(MemoSnapshot(remoteName = "memos/${UUID.randomUUID()}", content = "Remote image",
            createdAt = 1, updatedAt = 1, attachments = listOf(file)))
        assertTrue(notes.originalsDirectory.listFiles()!!.isEmpty())
        assertTrue(notes.cacheDirectory.listFiles()!!.isEmpty())
        service = MemoizeAttachmentService(context) { _, _, destination -> destination.writeBytes(bytes) }

        service.open(context, notes, note.attachments.single())
        val online = Shadows.shadowOf(context as Application).nextStartedActivity
        assertEquals("image/png", context.contentResolver.getType(online.data!!))
        assertArrayEquals(bytes, context.contentResolver.openInputStream(online.data!!)!!.use { it.readBytes() })

        notes.close()
        notes = NoteRepository.open(context, note.account, database)
        service = MemoizeAttachmentService(context) { _, _, _ -> throw IOException("Disconnected synthetic server") }
        service.open(context, notes, notes.get(note.id)!!.attachments.single())
        val offline = Shadows.shadowOf(context as Application).nextStartedActivity
        assertArrayEquals(bytes, context.contentResolver.openInputStream(offline.data!!)!!.use { it.readBytes() })
    }

    @Test fun failedOrCancelledDownloadsNeverOpenPartialBytesAndACompleteRetryWorksOffline() = runTest {
        val bytes = "durable 日本語 transfer\n".repeat(6_000).toByteArray(Charsets.UTF_8)
        val fileId = UUID.randomUUID().toString()
        val file = NoteAttachment(id = fileId, remoteName = "attachments/$fileId", filename = "途中 — transfer.txt",
            mimeType = "text/plain", size = bytes.size.toLong(), uploaded = true)
        val note = notes.applyRemote(MemoSnapshot(remoteName = "memos/${UUID.randomUUID()}", content = "Remote file",
            createdAt = 1, updatedAt = 1, attachments = listOf(file)))
        var transferAttempt = 0
        val entered = CountDownLatch(1)
        service = MemoizeAttachmentService(context) { _, _, destination ->
            when (transferAttempt) {
                0 -> { destination.writeBytes(bytes.copyOf(3)); throw IOException("Synthetic interrupted transfer") }
                1 -> destination.writeBytes(bytes.copyOf(3))
                2 -> { destination.writeBytes(bytes.copyOf(3)); entered.countDown(); awaitCancellation() }
                else -> destination.writeBytes(bytes)
            }
        }
        for (attempt in 0..1) {
            transferAttempt = attempt
            assertNotNull(runCatching { service.open(context, notes, file) }.exceptionOrNull())
            assertNull(Shadows.shadowOf(context as Application).nextStartedActivity)
            assertTrue(notes.cacheDirectory.listFiles()!!.isEmpty())
            assertEquals(note, notes.get(note.id))
        }
        transferAttempt = 2
        val job = launch(Dispatchers.IO) { service.open(context, notes, file) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancel()
            job.join()
            assertNull(Shadows.shadowOf(context as Application).nextStartedActivity)
            assertTrue(notes.cacheDirectory.listFiles()!!.isEmpty())
            assertEquals(note, notes.get(note.id))
        } finally { job.cancel(); job.join() }

        transferAttempt = 3
        service.open(context, notes, file)
        val online = Shadows.shadowOf(context as Application).nextStartedActivity
        assertArrayEquals(bytes, context.contentResolver.openInputStream(online.data!!)!!.use { it.readBytes() })
        service = MemoizeAttachmentService(context) { _, _, _ -> throw IOException("Disconnected synthetic server") }
        service.open(context, notes, file)
        val offline = Shadows.shadowOf(context as Application).nextStartedActivity
        assertArrayEquals(bytes, context.contentResolver.openInputStream(offline.data!!)!!.use { it.readBytes() })
        assertEquals(note, notes.get(note.id))
        assertTrue(notes.pending().isEmpty())
    }

    @Test fun aDownloadedRecoveryOriginalSurvivesCacheEvictionWhileItsUploadIsPending() = runTest {
        val bytes = "restored 日本語 original\u0000".repeat(4_000).toByteArray(Charsets.UTF_8)
        val fileId = UUID.randomUUID().toString()
        val file = NoteAttachment(id = fileId, remoteName = "attachments/$fileId", filename = "回復 — original.bin",
            mimeType = "application/octet-stream", size = bytes.size.toLong(), uploaded = true)
        val note = notes.applyRemote(MemoSnapshot(remoteName = "memos/${UUID.randomUUID()}", content = "Restore this file",
            createdAt = 1, updatedAt = 1, attachments = listOf(file)))
        notes.finishDelete(note.id)
        val restored = notes.restoreRecovery(note.id, notes.recovery(note.id).single().id)
        val pendingFile = restored.attachments.single()
        assertFalse(pendingFile.uploaded)
        assertTrue(pendingFile.id != fileId)
        assertEquals(file.remoteName, pendingFile.remoteName)
        service = MemoizeAttachmentService(context) { _, _, destination -> destination.writeBytes(bytes) }

        service.open(context, notes, pendingFile)
        val online = Shadows.shadowOf(context as Application).nextStartedActivity
        assertArrayEquals(bytes, context.contentResolver.openInputStream(online.data!!)!!.use { it.readBytes() })
        notes.cacheDirectory.deleteRecursively()
        notes.close()
        notes = NoteRepository.open(context, restored.account, database)
        service = MemoizeAttachmentService(context) { _, _, _ -> throw IOException("Disconnected synthetic server") }
        val saved = notes.get(restored.id)!!

        service.open(context, notes, saved.attachments.single())
        val offline = Shadows.shadowOf(context as Application).nextStartedActivity
        assertArrayEquals(bytes, context.contentResolver.openInputStream(offline.data!!)!!.use { it.readBytes() })
        assertArrayEquals(bytes, File(saved.attachments.single().localPath!!).readBytes())
        assertFalse(saved.attachments.single().uploaded)
        assertTrue(saved.isDirty)
        assertEquals(saved.revision, notes.pending().single().revision)
    }

    @Test fun evictionRedownloadsOnlyDisposableEntriesAndProtectsPendingUndoRecoveryAndOtherAccounts() = runTest {
        val account = notes.account
        notes.close()
        notes = NoteRepository.open(context, account, database, clock = { 1_700_000_000_000 })
        val bytesA = "Server A 日本語".toByteArray(Charsets.UTF_8)
        val bytesB = "Server B 日本語".toByteArray(Charsets.UTF_8)
        val bytesC = "Server C 日本語".toByteArray(Charsets.UTF_8)
        fun file(name: String, bytes: ByteArray): NoteAttachment {
            val id = UUID.randomUUID().toString()
            return NoteAttachment(id = id, remoteName = "attachments/$id", filename = "$name.txt",
                mimeType = "text/plain", size = bytes.size.toLong(), uploaded = true)
        }
        val fileA = file("A", bytesA)
        val fileB = file("B", bytesB)
        val fileC = file("C", bytesC)
        for (remote in listOf(fileA, fileB, fileC)) notes.applyRemote(MemoSnapshot(
            remoteName = "memos/${UUID.randomUUID()}", content = "Remote ${remote.filename}",
            createdAt = 1, updatedAt = 1, attachments = listOf(remote)))
        val serverBytes = mapOf(fileA.remoteName to bytesA, fileB.remoteName to bytesB, fileC.remoteName to bytesC)
        val downloads = mutableMapOf<String?, Int>()
        service = MemoizeAttachmentService(context, cacheLimitBytes = 0) { _, remote, destination ->
            downloads[remote.remoteName] = downloads.getOrDefault(remote.remoteName, 0) + 1
            destination.writeBytes(serverBytes.getValue(remote.remoteName))
        }
        provider.source.writeText("pending bytes")
        provider.filename = "pending.bin"
        val pendingNote = notes.create()
        val pendingOriginal = service.import(notes, pendingNote.id, uri)
        provider.source.writeText("Undo bytes")
        provider.filename = "undo.bin"
        val undoNote = notes.create()
        val undoOriginal = service.import(notes, undoNote.id, uri)
        notes.removeAttachment(undoNote.id, undoOriginal.id)
        assertTrue(notes.undoRemoveAttachment(undoNote.id, undoOriginal.id))
        notes.removeAttachment(undoNote.id, undoOriginal.id)
        provider.source.writeText("Recovery bytes")
        provider.filename = "recovery.bin"
        val recoveryNote = notes.create()
        val recoveryOriginal = service.import(notes, recoveryNote.id, uri)
        notes.finishDelete(recoveryNote.id)

        service.open(context, notes, fileA)
        Shadows.shadowOf(context as Application).nextStartedActivity
        val cachedA = notes.cachedAttachmentFile(fileA.id)
        val otherAccount = notes.forAccount("other-attachment-${UUID.randomUUID()}")
        val otherNote = otherAccount.create()
        val borrowed = fileA.copy(id = UUID.randomUUID().toString(), localPath = cachedA.absolutePath, uploaded = false)
        otherAccount.addAttachment(otherNote.id, borrowed)
        service.open(context, notes, fileC)
        Shadows.shadowOf(context as Application).nextStartedActivity
        service.open(context, notes, fileB)
        val current = Shadows.shadowOf(context as Application).nextStartedActivity
        assertArrayEquals(bytesB, context.contentResolver.openInputStream(current.data!!)!!.use { it.readBytes() })

        assertFalse("A disposable server entry must be evicted over budget", notes.cachedAttachmentFile(fileC.id).exists())
        assertArrayEquals(bytesA, cachedA.readBytes())
        assertEquals("pending bytes", File(pendingOriginal.localPath!!).readText())
        assertEquals("Undo bytes", File(undoOriginal.localPath!!).readText())
        assertEquals("Recovery bytes", File(recoveryOriginal.localPath!!).readText())
        assertTrue(notes.undoRemoveAttachment(undoNote.id, undoOriginal.id))
        val recovered = notes.restoreRecovery(recoveryNote.id, notes.recovery(recoveryNote.id).single().id)
        val offline = MemoizeAttachmentService(context) { _, _, _ -> error("No network is available") }
        for ((repository, original, expected) in listOf(
            Triple(notes, pendingOriginal, "pending bytes"),
            Triple(notes, notes.get(undoNote.id)!!.attachments.single(), "Undo bytes"),
            Triple(notes, recovered.attachments.single(), "Recovery bytes"),
            Triple(otherAccount, borrowed, "Server A 日本語"),
        )) {
            offline.open(context, repository, original)
            val intent = Shadows.shadowOf(context as Application).nextStartedActivity
            assertEquals(expected, context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes().toString(Charsets.UTF_8) })
        }
        service.open(context, notes, fileC)
        val retried = Shadows.shadowOf(context as Application).nextStartedActivity
        assertArrayEquals(bytesC, context.contentResolver.openInputStream(retried.data!!)!!.use { it.readBytes() })
        assertEquals(2, downloads[fileC.remoteName])
        assertArrayEquals(bytesA, File(otherAccount.get(otherNote.id)!!.attachments.single().localPath!!).readBytes())
    }
}

/** A disposable system content provider; no broad storage permissions are involved. */
class FixtureProvider : ContentProvider() {
    lateinit var source: File
    var filename = "original.bin"
    var mimeType = "application/octet-stream"
    var reportedSize: Long? = null
    var accessExpired = false
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = mimeType
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            if (accessExpired) throw SecurityException("provider internals: access expired")
            addRow(arrayOf<Any>(filename, reportedSize ?: source.length()))
        }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only synthetic provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only synthetic provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only synthetic provider")
}
