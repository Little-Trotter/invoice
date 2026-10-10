package de.littletrotter.infrastructure.mail

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.littletrotter.application.mail.MailDispatchException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

class HttpMailDraftAttachmentAdapterTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
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

    private fun adapter(token: String = "s3cr3t") =
        HttpMailDraftAttachmentAdapter(baseUrl, Optional.of(token)).also {
            it.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        }

    private fun serve(status: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
        server.createContext("/mail/api/compose/d-1/attachments/0") { exchange: HttpExchange ->
            lastAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.write(body)
            exchange.close()
        }
    }

    @Test
    fun `200 returns bytes, content type and the exact filename, sending the bearer token`() {
        serve(
            200,
            byteArrayOf(1, 2, 3),
            mapOf(
                "Content-Type" to "application/pdf",
                "Content-Disposition" to "inline; filename=\"Rechnung ___.pdf\"; filename*=UTF-8''Rechnung%20%C3%84%2B.pdf",
            ),
        )

        val found = adapter().findAttachment("d-1", 0)

        assertNotNull(found)
        assertEquals("Rechnung Ä+.pdf", found!!.filename)
        assertEquals("application/pdf", found.contentType)
        assertArrayEquals(byteArrayOf(1, 2, 3), found.bytes)
        assertEquals("Bearer s3cr3t", lastAuth.get())
    }

    @Test
    fun `the quoted filename is the fallback and a missing header yields a generic name`() {
        serve(200, byteArrayOf(1), mapOf("Content-Type" to "application/xml", "Content-Disposition" to "inline; filename=\"x.xml\""))
        assertEquals("x.xml", adapter().findAttachment("d-1", 0)!!.filename)
        server.removeContext("/mail/api/compose/d-1/attachments/0")

        serve(200, byteArrayOf(1))
        assertEquals("attachment-0", adapter().findAttachment("d-1", 0)!!.filename)
    }

    @Test
    fun `404 means the attachment is gone`() {
        serve(404)
        assertNull(adapter().findAttachment("d-1", 0))
    }

    @Test
    fun `another status is a dispatch failure`() {
        serve(500)
        assertThrows(MailDispatchException::class.java) { adapter().findAttachment("d-1", 0) }
    }

    @Test
    fun `missing token and unreachable component are dispatch failures`() {
        assertThrows(MailDispatchException::class.java) { adapter(token = " ").findAttachment("d-1", 0) }
        server.stop(0)
        assertThrows(MailDispatchException::class.java) { adapter().findAttachment("d-1", 0) }
    }
}
