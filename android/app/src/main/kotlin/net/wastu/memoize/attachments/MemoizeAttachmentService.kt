package net.wastu.memoize.attachments

import android.content.Context
import android.content.ContentResolver
import android.content.Intent
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.app.Activity
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.wastu.memoize.data.NoteAttachment
import net.wastu.memoize.data.NoteRepository
import net.wastu.memoize.ui.AttachmentService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Picker and viewer adapter; the repository confirms durable associations. */
class MemoizeAttachmentService(
    private val context: Context,
    private val cacheLimitBytes: Long = 128L * 1_024 * 1_024,
    private val download: suspend (NoteRepository, NoteAttachment, File) -> Unit,
) : AttachmentService {
    private val downloads = Mutex()
    init { require(cacheLimitBytes >= 0) }

    override suspend fun import(repository: NoteRepository, noteId: String, uri: Uri): NoteAttachment = withContext(Dispatchers.IO) {
        try {
            require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Select a file using the photo or document picker." }
            val resolver = context.contentResolver
            var expectedSize: Long? = null
            val filename = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    expectedSize = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                }
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) cursor.getString(column) else null
            }?.takeIf { it.isNotEmpty() } ?: uri.lastPathSegment?.takeIf { it.isNotEmpty() } ?: "attachment"
            val mimeType = resolver.getType(uri)?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
            val id = UUID.randomUUID().toString().lowercase()
            val temporary = File.createTempFile(".import-", ".partial", repository.originalsDirectory)
            val original = File(repository.originalsDirectory, id)
            var associated = false
            try {
                val size = requireNotNull(resolver.openInputStream(uri)) { "The selected file could not be opened." }.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(65_536)
                        var count = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            currentCoroutineContext().ensureActive()
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            count += read
                        }
                        output.fd.sync()
                        count
                    }
                }
                if (expectedSize != null && size < expectedSize) {
                    throw IOException("The selected file ended before copying finished. Select it again.")
                }
                currentCoroutineContext().ensureActive()
                Files.move(temporary.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE)
                generateSequence(repository.originalsDirectory) { it.parentFile }.take(4).forEach { directory ->
                    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                }
                val attachment = NoteAttachment(id = id, filename = filename, mimeType = mimeType, size = size, localPath = original.absolutePath)
                // Cancellation cannot split an already durable file from its committed association.
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    repository.addAttachment(noteId, attachment)
                    associated = true
                }
                attachment
            } finally {
                temporary.delete()
                if (!associated) original.delete()
            }
        } catch (error: IOException) {
            throw IOException("This file could not be saved on this device. Check available storage and select it again.", error)
        } catch (error: SecurityException) {
            throw IOException("Access to this file expired. Select it again using the photo or file picker.", error)
        }
    }

    override suspend fun open(context: Context, repository: NoteRepository, attachment: NoteAttachment) = downloads.withLock {
        val original = withContext(Dispatchers.IO) {
            val kept = if (attachment.uploaded) attachment else repository.keepAttachmentOriginal(attachment)
            val available = kept.localPath?.let(::File)?.takeIf { it.isFile && it.length() == kept.size }
                ?: run {
                    val cached = repository.cachedAttachmentFile(attachment.id)
                    if (!cached.isFile || cached.length() != attachment.size) {
                        require(attachment.uploaded || attachment.remoteName != null) {
                            "The original is not available on this device. Select the file again to attach it."
                        }
                        repository.cacheDirectory.mkdirs()
                        val temporary = File.createTempFile(".download-", ".partial", repository.cacheDirectory)
                        try {
                            download(repository, attachment, temporary)
                            if (!temporary.isFile || (attachment.size > 0L && temporary.length() < attachment.size)) {
                                throw IOException("The file download did not finish. Try opening it again.")
                            }
                            currentCoroutineContext().ensureActive()
                            FileOutputStream(temporary, true).use { it.fd.sync() }
                            Files.move(temporary.toPath(), cached.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            FileChannel.open(repository.cacheDirectory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                        } finally { temporary.delete() }
                    }
                    if (kept.uploaded) cached else {
                        repository.keepAttachmentOriginal(kept).localPath?.let(::File)
                            ?: throw IOException("This file could not be kept for upload. Check available storage and try again.")
                    }
                }
            if (available.parentFile?.canonicalFile == repository.cacheDirectory.canonicalFile) {
                available.setLastModified(System.currentTimeMillis())
            }
            trimCache(repository, available)
            available
        }
        withContext(Dispatchers.Main.immediate) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", original, attachment.filename)
                .buildUpon().appendQueryParameter("mimeType", attachment.mimeType).build()
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(context.contentResolver, attachment.filename, uri)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(intent) }
            catch (error: ActivityNotFoundException) {
                throw IOException("No installed app can open this file. Install an app that supports ${attachment.mimeType}.", error)
            }
        }
    }

    /** Only complete, known server copies can be evicted; the current viewer file stays pinned. */
    private suspend fun trimCache(repository: NoteRepository, pinned: File) {
        val cached = repository.cacheDirectory.listFiles()?.filter { it.isFile } ?: return
        var bytes = cached.sumOf { it.length() }
        if (bytes <= cacheLimitBytes) return
        val notes = repository.all()
        val held = notes.filter { it.isDirty || it.deleteAfter != null || it.error != null || it.remoteName == null }
            .flatMap { it.attachments } + repository.recoveries().flatMap { it.snapshot.attachments } +
            repository.attachmentCleanup().map { it.attachment }
        val heldPaths = held.flatMap { listOfNotNull(it.id, it.remoteName?.substringAfterLast('/')) }
            .map { repository.cachedAttachmentFile(it).canonicalPath }.toHashSet()
        val protected = repository.protectedPaths().map { File(it).canonicalPath }.toHashSet()
        val known = notes.filter { !it.isDirty && it.deleteAfter == null && it.error == null && it.remoteName != null }
            .flatMap { it.attachments }.filter { it.uploaded && it.remoteName != null && it.externalLink == null }
            .associate { repository.cachedAttachmentFile(it.id).canonicalPath to it.size }
        val pinnedPath = pinned.canonicalPath
        for (entry in cached.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })) {
            if (bytes <= cacheLimitBytes) break
            currentCoroutineContext().ensureActive()
            val path = entry.canonicalPath
            val size = entry.length()
            if (path == pinnedPath || path in protected || path in heldPaths || known[path] != size) continue
            if (entry.delete()) bytes -= size
        }
    }
}
