package net.wastu.memoize.sync

import net.wastu.memoize.data.MemoSnapshot
import net.wastu.memoize.data.NoteAttachment
import java.io.File
import java.io.IOException

/** The server boundary; clients and fault tests share the same observable contract. */
interface MemosApi {
    suspend fun list(): List<MemoSnapshot>
    suspend fun get(id: String): MemoSnapshot?
    suspend fun create(id: String, snapshot: MemoSnapshot): MemoSnapshot
    suspend fun update(snapshot: MemoSnapshot, base: MemoSnapshot?): MemoSnapshot
    suspend fun delete(id: String)
    suspend fun deleteAttachment(id: String)
    suspend fun getAttachment(id: String): NoteAttachment?
    suspend fun upload(attachment: NoteAttachment, file: File): NoteAttachment
    suspend fun download(attachment: NoteAttachment, destination: File)
}

class MemosApiException(
    val status: Int,
    message: String,
    val retryAfterMillis: Long? = null,
) : IOException(message) {
    val retryable: Boolean get() = status == 429 || status >= 500
    val needsAuthentication: Boolean get() = status == 401 || status == 403
}
