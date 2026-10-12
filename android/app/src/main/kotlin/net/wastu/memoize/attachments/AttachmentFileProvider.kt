package net.wastu.memoize.attachments

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import net.wastu.memoize.R
import java.io.FileNotFoundException

/** Original paths are opaque IDs; viewers receive the retained MIME and display name. */
class AttachmentFileProvider : FileProvider(R.xml.attachment_paths) {
    override fun getType(uri: Uri): String? {
        val fallback = super.getType(uri) // Resolve and validate the configured private path first.
        return uri.getQueryParameter("mimeType")?.takeIf {
            it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
        } ?: fallback
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw FileNotFoundException("Attachment access is read-only.")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Attachment access is read-only.")
}
