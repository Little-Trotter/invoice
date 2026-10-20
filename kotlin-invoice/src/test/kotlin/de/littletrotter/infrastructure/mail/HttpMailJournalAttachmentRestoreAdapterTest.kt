package de.littletrotter.infrastructure.mail

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

/** The restore adapter against a real local HTTP server and the real JDK client. */
class HttpMailJournalAttachmentRestoreAdapterTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val lastQuery = AtomicReference<String?>()
    private val lastAuth = AtomicReference<String?>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun adapter(token: String = "s3cr3t", url: String = baseUrl) =
        HttpMailJournalAttachmentRestoreAdapter(url, Optional.of(token)).also {
            it.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        }

    private fun serve(status: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
        server.createContext("/mail/api/journal/attachment") { exchange: HttpExchange ->
            lastQuery.set(exchange.requestURI.rawQuery)
            lastAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.write(body)
            exchange.close()
        }
    }

    @Test
    fun `200 returns bytes with journal id and file, sending bearer token and encoded query`() {
        val bytes = "%PDF sent copy".toByteArray()
        serve(200, bytes, mapOf("X-Mail-Journal-Id" to "42", "X-Mail-Journal-File" to "message.eml"))

        val found = adapter().findAttachment("RE 2026/1", "ab".repeat(32))

        assertNotNull(found)
        assertArrayEquals(bytes, found!!.bytes)
        assertEquals(42L, found.journalId)
        assertEquals("message.eml", found.file)
        assertEquals("Bearer s3cr3t", lastAuth.get())
        assertEquals("referenceKey=RE+2026%2F1&sha256=${"ab".repeat(32)}", lastQuery.get())
    }

    @Test
    fun `404 is the ordinary no-copy answer`() {
        serve(404)
        assertNull(adapter().findAttachment("RE-1", "a".repeat(64)))
    }

    @Test
    fun `another status is not restorable`() {
        serve(500)
        assertNull(adapter().findAttachment("RE-1", "a".repeat(64)))
    }

    @Test
    fun `200 without a usable journal id header is ignored`() {
        serve(200, "x".toByteArray())
        assertNull(adapter().findAttachment("RE-1", "a".repeat(64)))
    }

    @Test
    fun `200 without a file header yields an empty file name`() {
        serve(200, "x".toByteArray(), mapOf("X-Mail-Journal-Id" to "7"))
        assertEquals("", adapter().findAttachment("RE-1", "a".repeat(64))!!.file)
    }

    @Test
    fun `a blank token never calls the component`() {
        serve(200, "x".toByteArray(), mapOf("X-Mail-Journal-Id" to "7"))
        assertNull(adapter(token = " ").findAttachment("RE-1", "a".repeat(64)))
        assertNull(lastQuery.get())
    }

    @Test
    fun `an unreachable component is not restorable`() {
        assertNull(adapter(url = "http://127.0.0.1:1").findAttachment("RE-1", "a".repeat(64)))
    }

    @Test
    fun `the fallback bean answers null`() {
        assertNull(MailJournalRestoreUnavailable().findAttachment("RE-1", "a".repeat(64)))
    }
}
