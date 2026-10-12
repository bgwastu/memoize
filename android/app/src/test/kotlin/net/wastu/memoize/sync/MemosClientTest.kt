package net.wastu.memoize.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import net.wastu.memoize.data.MemoSnapshot
import net.wastu.memoize.data.NoteAttachment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemosClientTest {
    @Test
    fun listCompletesEveryPageOfActiveAndArchivedNotes() = runTest {
        val requests = CopyOnWriteArrayList<String>()
        withServer { server ->
            server.createContext("/api/v1/memos") { exchange ->
                val params = query(exchange)
                val archived = params["state"] == "ARCHIVED"
                requests += "${params["state"]}:${params["pageToken"]}"
                assertEquals("creator == \"users/synthetic\"", params["filter"])
                val second = params["pageToken"] == "next"
                val id = if (archived) if (second) "archived-two" else "archived-one" else if (second) "active-two" else "active-one"
                exchange.respond(200, JSONObject().put("memos", JSONArray().put(memo(id, archived)))
                    .put("nextPageToken", if (second) "" else "next"))
            }
            server.start()
            val result = MemosClient(url(server), "synthetic-token", "users/synthetic", true, pageSize = 1).list()
            assertEquals(setOf("memos/active-one", "memos/active-two", "memos/archived-one", "memos/archived-two"), result.map { it.remoteName }.toSet())
            assertEquals(listOf("NORMAL:", "NORMAL:next", "ARCHIVED:", "ARCHIVED:next"), requests)
        }
    }

    @Test
    fun aRepeatedPageTokenAndAFailedArchivedListNeverReturnPartialNotes() = runTest {
        withServer { server ->
            server.createContext("/api/v1/memos") { exchange ->
                exchange.respond(200, JSONObject().put("memos", JSONArray().put(memo("saved", false))).put("nextPageToken", "repeated"))
            }
            server.start()
            var failed = false
            try { MemosClient(url(server), "synthetic-token", "users/synthetic", true).list() }
            catch (_: IOException) { failed = true }
            assertTrue("A repeated page must reject the entire list", failed)
        }
        withServer { server ->
            server.createContext("/api/v1/memos") { exchange ->
                if (query(exchange)["state"] == "ARCHIVED") exchange.respond(503, JSONObject())
                else exchange.respond(200, JSONObject().put("memos", JSONArray().put(memo("saved", false))))
            }
            server.start()
            var status: Int? = null
            try { MemosClient(url(server), "synthetic-token", "users/synthetic", true).list() }
            catch (error: MemosApiException) { status = error.status }
            assertEquals(503, status)
        }
    }

    @Test
    fun anAuthenticatedRedirectDoesNotReachTheOtherOrigin() = runTest {
        withServer { other ->
            val received = CopyOnWriteArrayList<String>()
            other.createContext("/") { exchange ->
                received += exchange.requestHeaders.getFirst("Authorization") ?: "no authorization"
                exchange.respond(200, JSONObject())
            }
            other.start()
            withServer { server ->
                server.createContext("/api/v1/auth/me") { exchange ->
                    exchange.responseHeaders.add("Location", url(other) + "/credential-capture")
                    exchange.respond(302, JSONObject())
                }
                server.start()
                var status: Int? = null
                try { MemosClient(url(server), "synthetic-secret", allowLoopbackForTests = true).authenticate() }
                catch (error: MemosApiException) { status = error.status }
                assertEquals(302, status)
                assertTrue(received.isEmpty())
            }
        }
    }

    @Test
    fun productionSetupRequiresHttpsAndEquivalentOriginsShareOneNamespace() {
        assertEquals("https://example.com/memos", MemosClient.normalizedServer(" HTTPS://Example.com:443/memos/?token=ignored#fragment ").toASCIIString())
        val one = MemosClient.normalizedServer("https://example.com")
        val equivalent = MemosClient.normalizedServer("https://EXAMPLE.com:443/")
        assertEquals(MemosClient.accountNamespace(one, "users/a"), MemosClient.accountNamespace(equivalent, "users/a"))
        assertFalse(MemosClient.accountNamespace(one, "users/a") == MemosClient.accountNamespace(one, "users/b"))
        for (url in listOf("http://127.0.0.1:8081", "http://example.com", "https://user:password@example.com", "https://example.com/../other")) {
            assertTrue("Invalid production origin: $url", runCatching { MemosClient.normalizedServer(url) }.isFailure)
        }
    }

    @Test
    fun aBusyWriteReturnsRetryAfterWithoutAutomaticallyRepeatingTheRevision() = runTest {
        withServer { server ->
            val calls = AtomicInteger()
            server.createContext("/api/v1/memos") { exchange ->
                calls.incrementAndGet()
                exchange.requestBody.use { it.readBytes() }
                exchange.responseHeaders.add("Retry-After", "0")
                exchange.respond(503, JSONObject())
            }
            server.start()
            var status: Int? = null
            try {
                MemosClient(url(server), "synthetic-token", "users/synthetic", true).create("write-probe",
                    MemoSnapshot(content = "Exact single attempt", createdAt = 1_800_000_000_000, updatedAt = 1_800_000_000_000))
            } catch (error: MemosApiException) { status = error.status }
            assertEquals(503, status)
            assertEquals("Durable sync decides when to retry a sent revision", 1, calls.get())
        }
    }

    @Test
    fun externalDownloadsNeverReceiveTheConfiguredBearerCredential() = runTest {
        val directory = Files.createTempDirectory("memoize-download-contract").toFile()
        try {
            withServer { other ->
                val received = CopyOnWriteArrayList<String>()
                other.createContext("/external") { exchange ->
                    received += exchange.requestHeaders.getFirst("Authorization") ?: "no authorization"
                    exchange.respond(200, JSONObject().put("synthetic", "complete bytes"))
                }
                other.start()
                withServer { server ->
                    server.start()
                    val client = MemosClient(url(server), "synthetic-secret", allowLoopbackForTests = true)
                    val attachment = NoteAttachment(id = "external-probe", filename = "external.json", mimeType = "application/json", size = 0,
                        externalLink = url(other) + "/external", uploaded = true)
                    val file = File(directory, "complete")
                    client.download(attachment, file)
                    assertEquals(listOf("no authorization"), received)
                    assertEquals("complete bytes", JSONObject(file.readText()).getString("synthetic"))
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun aTruncatedDownloadPreservesThePreviousCompleteCache() = runTest {
        val directory = Files.createTempDirectory("memoize-partial-contract").toFile()
        try {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                kotlin.concurrent.thread(isDaemon = true, name = "synthetic-truncated-response") {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val request = socket.getInputStream().bufferedReader()
                        while (request.readLine()?.isNotEmpty() == true) { }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 128\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                            write(byteArrayOf(1, 2, 3)); flush()
                        }
                        socket.shutdownOutput()
                    }
                }
                val file = File(directory, "complete").apply { writeText("Existing complete original") }
                val attachment = NoteAttachment(id = "partial-probe", filename = "partial.bin", mimeType = "application/octet-stream", size = 128)
                val result = runCatching { MemosClient("http://127.0.0.1:${server.localPort}", "synthetic-secret", allowLoopbackForTests = true).download(attachment, file) }
                assertTrue(result.isFailure)
                assertEquals("Existing complete original", file.readText())
                assertEquals(listOf("complete"), directory.listFiles()!!.map { it.name })
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun cancellationStopsAWaitingDownloadAndNeverPromotesPartialBytes() = runTest {
        val directory = Files.createTempDirectory("memoize-cancel-contract").toFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            withServer { server ->
                server.createContext("/file/attachments/cancel-probe") { exchange ->
                    exchange.sendResponseHeaders(200, 1_000_000)
                    runCatching {
                        exchange.responseBody.use {
                            it.write(ByteArray(65_536)); it.flush(); started.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                    }
                    exchange.close()
                }
                server.start()
                val file = File(directory, "complete")
                val attachment = NoteAttachment(id = "cancel-probe", filename = "cancel.bin", mimeType = "application/octet-stream", size = 1_000_000)
                val job = launch(Dispatchers.Default) {
                    MemosClient(url(server), "synthetic-secret", allowLoopbackForTests = true).download(attachment, file)
                }
                try {
                    assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
                    job.cancel()
                    withContext(Dispatchers.Default) { withTimeout(3_000) { job.join() } }
                    assertFalse(file.exists())
                    assertTrue(directory.listFiles()!!.isEmpty())
                } finally { release.countDown(); job.cancel() }
            }
        } finally { release.countDown(); directory.deleteRecursively() }
    }

    private suspend fun withServer(block: suspend (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try { block(server) } finally { server.stop(0) }
    }
    private fun url(server: HttpServer): String = "http://127.0.0.1:${server.address.port}"
    private fun query(exchange: HttpExchange): Map<String, String> = exchange.requestURI.rawQuery.orEmpty().split('&').associate { part ->
        val pair = part.split('=', limit = 2)
        URLDecoder.decode(pair[0], StandardCharsets.UTF_8) to URLDecoder.decode(pair.getOrElse(1) { "" }, StandardCharsets.UTF_8)
    }
    private fun memo(id: String, archived: Boolean): JSONObject = JSONObject().put("name", "memos/$id")
        .put("content", "Synthetic server body").put("createTime", "2026-10-01T00:00:00Z")
        .put("state", if (archived) "ARCHIVED" else "NORMAL").put("visibility", "PRIVATE")
    private fun HttpExchange.respond(status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }
}
