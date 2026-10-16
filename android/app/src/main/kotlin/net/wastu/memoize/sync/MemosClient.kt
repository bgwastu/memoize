package net.wastu.memoize.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.wastu.memoize.data.MemoSnapshot
import net.wastu.memoize.data.NoteAttachment
import net.wastu.memoize.data.NoteState
import net.wastu.memoize.data.NoteVisibility
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resumeWithException

/** Memos 0.31 transport. Requests never follow redirects or expose credentials to other origins. */
class MemosClient(
    server: String,
    private val token: String,
    val user: String = "",
    private val allowLoopbackForTests: Boolean = false,
    private val pageSize: Int = 100,
) : MemosApi {
    val server: URI = normalizedServer(server, allowLoopbackForTests)

    init {
        require(token.isNotBlank() && token.none { it == '\r' || it == '\n' }) { "Enter an access token." }
        require(pageSize in 1..500)
    }

    suspend fun authenticate(): String = json("auth/me").getJSONObject("user").getString("name").also {
        require(it.startsWith("users/") && it.length > 6) { "The server returned an invalid account." }
    }

    override suspend fun list(): List<MemoSnapshot> {
        require(user.isNotBlank()) { "Connect an account before syncing." }
        val result = linkedMapOf<String, MemoSnapshot>()
        for (state in NoteState.entries) {
            var pageToken = ""
            val seen = mutableSetOf<String>()
            do {
                currentCoroutineContext().ensureActive()
                val escaped = user.replace("\\", "\\\\").replace("\"", "\\\"")
                val page = json("memos", query = mapOf(
                    "filter" to "creator == \"$escaped\"", "state" to state.name,
                    "pageSize" to pageSize.toString(), "orderBy" to "create_time desc", "pageToken" to pageToken,
                ))
                val memos = page.optJSONArray("memos") ?: JSONArray()
                for (index in 0 until memos.length()) {
                    val memo = memo(memos.getJSONObject(index))
                    result[requireNotNull(memo.remoteName)] = memo
                }
                pageToken = page.optString("nextPageToken")
                if (pageToken.isNotEmpty() && !seen.add(pageToken)) {
                    throw IOException("The server repeated a page. Sync will retry safely.")
                }
            } while (pageToken.isNotEmpty())
        }
        // Do not return until both states and every page completed successfully.
        return result.values.toList()
    }

    override suspend fun get(id: String): MemoSnapshot? = notFound { memo(json("memos/${resourceId(id, "memos")}")) }

    override suspend fun create(id: String, snapshot: MemoSnapshot): MemoSnapshot {
        val created = memo(json("memos", "POST", mapOf("memoId" to resourceId(id, "memos")), memoBody(snapshot).apply {
            put("visibility", "PRIVATE")
            put("createTime", Instant.ofEpochMilli(snapshot.createdAt).toString())
        }))
        // Memos creates NORMAL rows. An explicit offline archive needs a separate masked update.
        return if (snapshot.state == NoteState.ARCHIVED && created.state != snapshot.state)
            update(created.copy(state = snapshot.state), created) else created
    }

    override suspend fun update(snapshot: MemoSnapshot, base: MemoSnapshot?): MemoSnapshot {
        val body = memoBody(snapshot)
        val mask = mutableListOf("content", "update_time", "attachments")
        if (base != null && base.state != snapshot.state) { body.put("state", snapshot.state.name); mask += "state" }
        if (base != null && base.visibility != snapshot.visibility) {
            body.put("visibility", snapshot.visibility.name); mask += "visibility"
        }
        if (base != null && base.pinned != snapshot.pinned) {
            body.put("pinned", snapshot.pinned); mask += "pinned"
        }
        // Omitting updateTime while including the mask requests the server's timestamp.
        return memo(json(requireNotNull(snapshot.remoteName), "PATCH", mapOf("updateMask" to mask.joinToString(",")), body))
    }

    override suspend fun delete(id: String) { notFound { json("memos/${resourceId(id, "memos")}", "DELETE") } }
    override suspend fun deleteAttachment(id: String) { notFound { json("attachments/${resourceId(id, "attachments")}", "DELETE") } }
    override suspend fun getAttachment(id: String): NoteAttachment? =
        notFound { attachment(json("attachments/${resourceId(id, "attachments")}")) }

    override suspend fun upload(attachment: NoteAttachment, file: File): NoteAttachment {
        require(file.isFile) { "The original file is unavailable." }
        if (attachment.remoteName != null) {
            getAttachment(attachment.id)?.let { existing ->
                if (existing.filename != attachment.filename || existing.mimeType != attachment.mimeType || existing.size != file.length()) {
                    throw IOException("A different server file already has this attachment identity. The local original is preserved.")
                }
                return existing.copy(localPath = attachment.localPath)
            }
        }
        val size = file.length()
        val spec = JSONObject()
            .put("attachment", JSONObject().put("filename", attachment.filename).put("type", attachment.mimeType))
            .put("totalSize", size.toString())
        val opening = JSONObject().put("spec", spec)
        if (size == 0L) opening.put("finishWrite", true)
        var response = json("attachments:upload", "POST", body = opening)
        response.optJSONObject("attachment")?.let { return attachment(it).copy(localPath = attachment.localPath) }
        val uploadId = response.optString("uploadId").ifBlank { throw IOException("The server did not start the upload.") }
        val chunkSize = response.optInt("maxChunkSize", 1_048_576).coerceIn(1, 1_048_576)
        withContext(Dispatchers.IO) {
            file.inputStream().use { input ->
                var offset = 0L
                val buffer = ByteArray(chunkSize)
                while (offset < size) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - offset).toInt())
                    if (count <= 0) throw IOException("The original file ended before the upload completed.")
                    val bytes = if (count == buffer.size) buffer else buffer.copyOf(count)
                    response = json("attachments:upload", "POST", body = JSONObject()
                        .put("uploadId", uploadId).put("writeOffset", offset.toString())
                        .put("data", Base64.getEncoder().encodeToString(bytes)).put("finishWrite", offset + count == size))
                    offset += count
                }
            }
        }
        return (response.optJSONObject("attachment")?.let(::attachment) ?: getAttachment(attachment.id)
            ?: throw IOException("The server did not confirm the completed upload.")).copy(localPath = attachment.localPath)
    }

    override suspend fun download(attachment: NoteAttachment, destination: File) = withContext<Unit>(Dispatchers.IO) {
        val uid = resourceId(attachment.remoteName ?: attachment.id, "attachments")
        val uri = attachment.externalLink?.takeIf { it.isNotBlank() }?.let(URI::create)
            ?: endpoint("file/attachments/$uid/${encode(attachment.filename)}", emptyMap(), false)
        validateDownload(uri)
        destination.parentFile?.mkdirs()
        val temporary = File.createTempFile(".download-", ".partial", destination.parentFile)
        try {
            request(uri, "GET", null, sameOrigin(uri, server)) { response, context ->
                val expected = response.body.contentLength().takeIf { it >= 0 }
                var total = 0L
                response.body.byteStream().use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count); total += count
                        }
                        output.fd.sync()
                    }
                }
                if (expected != null && expected != total) throw IOException("The file download was incomplete. Retry to download it again.")
                context.ensureActive()
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun memoBody(snapshot: MemoSnapshot): JSONObject = JSONObject().put("content", snapshot.content)
        .put("pinned", snapshot.pinned)
        .put("attachments", JSONArray(snapshot.attachments.map {
            JSONObject().put("name", "attachments/${resourceId(it.remoteName ?: it.id, "attachments")}")
        }))

    private fun memo(value: JSONObject): MemoSnapshot {
        val created = Instant.parse(value.getString("createTime")).toEpochMilli()
        val files = value.optJSONArray("attachments") ?: JSONArray()
        return MemoSnapshot(
            "memos/${resourceId(value.getString("name"), "memos")}", value.optString("content"), created,
            value.optString("updateTime").takeIf { it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() } ?: created,
            NoteState.valueOf(value.optString("state", "NORMAL")),
            NoteVisibility.valueOf(value.optString("visibility", "PRIVATE")),
            (0 until files.length()).map { attachment(files.getJSONObject(it)) },
            pinned = value.optBoolean("pinned", false),
        )
    }

    private fun attachment(value: JSONObject): NoteAttachment {
        val id = resourceId(value.getString("name"), "attachments")
        return NoteAttachment(id, "attachments/$id", value.getString("filename"), value.getString("type"),
            value.optString("size", "0").toLong(), externalLink = value.optString("externalLink").takeIf { it.isNotBlank() }, uploaded = true)
    }

    private suspend fun json(path: String, method: String = "GET", query: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject =
        request(endpoint(path, query), method, body?.toString()?.toByteArray(StandardCharsets.UTF_8), true) { response, context ->
                val bytes = ByteArrayOutputStream()
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(16_384)
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > 64 * 1_048_576) throw IOException("The server response is too large. Sync stopped safely.")
                        bytes.write(buffer, 0, count)
                    }
                }
                try { JSONObject(bytes.toString(StandardCharsets.UTF_8).ifBlank { "{}" }) }
                catch (_: JSONException) { throw IOException("Memos returned an invalid response. Sync stopped safely.") }
        }

    private fun endpoint(path: String, query: Map<String, String>, api: Boolean = true): URI {
        require(path.none { it == '?' || it == '#' } && !path.startsWith('/') && !path.split('/').contains(".."))
        val encoded = query.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return URI.create("${server.toASCIIString()}/${if (api) "api/v1/" else ""}$path${if (encoded.isEmpty()) "" else "?$encoded"}")
    }

    private suspend fun <T> request(uri: URI, method: String, body: ByteArray?, authenticated: Boolean,
        consume: (Response, CoroutineContext) -> T): T = suspendCancellableCoroutine { continuation ->
        // Even HTTP 503 + Retry-After:0 cannot automatically replay an ambiguous write.
        val payload = body ?: if (method != "GET" && method != "HEAD") ByteArray(0) else null
        val requestBody = payload?.let { bytes -> object : RequestBody() {
            override fun contentType() = JSON
            override fun contentLength(): Long = bytes.size.toLong()
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        } }
        val request = Request.Builder().url(uri.toASCIIString()).method(method, requestBody)
            .header("Accept", "application/json").apply {
            if (authenticated) {
                check(sameOrigin(uri, server)) { "Credentials cannot leave the configured origin." }
                header("Authorization", "Bearer $token")
            }
        }.build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        continuation.context.ensureActive()
                        checkResponse(it)
                        consume(it, continuation.context)
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(result))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun checkResponse(response: Response) {
        val status = response.code
        if (status in 200..299) return
        val message = when (status) {
            in 300..399 -> "The server redirected this request. Use its final HTTPS URL in Settings."
            401 -> "The access token expired or is invalid. Reconnect in Settings."
            403 -> "The server denied this operation. Check the account in Settings."
            404 -> "This item no longer exists on the server."
            413 -> "This file exceeds the server upload limit. The original remains on this device."
            429 -> "The server is busy. Memoize will retry."
            else -> "Memos returned HTTP $status. Saved work remains on this device."
        }
        val retry = response.header("Retry-After")?.let { raw ->
            raw.toLongOrNull()?.let { it.coerceIn(0, Long.MAX_VALUE / 1_000) * 1_000 }
                ?: runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis() }.getOrNull()?.coerceAtLeast(0)
        }
        throw MemosApiException(status, message, retry)
    }

    private fun validateDownload(uri: URI) {
        require(uri.userInfo == null && uri.host != null &&
            (uri.scheme == "https" || (allowLoopbackForTests && uri.scheme == "http" && isLoopback(uri.host)))) {
            "Files require an HTTPS download URL."
        }
    }

    private suspend fun <T> notFound(block: suspend () -> T): T? = try { block() }
    catch (error: MemosApiException) { if (error.status == 404) null else throw error }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        fun normalizedServer(value: String, allowLoopbackForTests: Boolean = false): URI {
            val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("Enter an HTTPS Memos server URL.") }
            val scheme = uri.scheme?.lowercase()
            require(uri.host != null && uri.userInfo == null && uri.port in -1..65535 && uri.port != 0 &&
                (scheme == "https" || (allowLoopbackForTests && scheme == "http" && isLoopback(uri.host)))) {
                "Enter an HTTPS Memos server URL."
            }
            require(uri.rawPath?.split('/')?.none { it == "." || it == ".." || it.contains("%2e", true) } != false) {
                "Enter the server's direct HTTPS URL."
            }
            val port = if ((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) -1 else uri.port
            return URI(scheme, null, uri.host.lowercase(), port, uri.path.orEmpty().trimEnd('/'), null, null)
        }
        fun accountNamespace(server: URI, user: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest("${server.toASCIIString()}\u0000$user".toByteArray(StandardCharsets.UTF_8))
            return "memos-" + digest.joinToString("") { "%02x".format(it) }
        }
        private fun resourceId(value: String, kind: String): String = value.removePrefix("$kind/").also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,35}"))) { "The server returned an invalid $kind identity." }
        }
        private fun isLoopback(host: String): Boolean = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        private fun sameOrigin(left: URI, right: URI): Boolean = left.scheme.equals(right.scheme, true) &&
            left.host.equals(right.host, true) && (if (left.port == -1) defaultPort(left) else left.port) ==
            (if (right.port == -1) defaultPort(right) else right.port)
        private fun defaultPort(uri: URI): Int = if (uri.scheme == "https") 443 else 80
        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
