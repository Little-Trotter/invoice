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

class HttpMailArchiveDownloadAdapterTest {

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

    private fun adapter(token: String = "s3cr3t", url: String = baseUrl) =
        HttpMailArchiveDownloadAdapter(url, Optional.of(token)).also {
            it.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        }

    private fun serve(status: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
        server.createContext("/mail/api/journal/42/archive") { exchange: HttpExchange ->
            lastAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.write(body)
            exchange.close()
        }
    }

    @Test
    fun `200 returns bytes and the announced filename, sending the bearer token`() {
        serve(200, byteArrayOf(1, 2, 3), mapOf("Content-Disposition" to "attachment; filename=\"x.tar.gz\""))

        val found = adapter().findArchive(42L)

        assertNotNull(found)
        assertEquals("x.tar.gz", found!!.filename)
        assertArrayEquals(byteArrayOf(1, 2, 3), found.bytes)
        assertEquals("Bearer s3cr3t", lastAuth.get())
    }

    @Test
    fun `200 without a usable header falls back to the standard filename`() {
        serve(200, byteArrayOf(9))

        assertEquals("mail-journal-42-archive.tar.gz", adapter().findArchive(42L)!!.filename)
    }

    @Test
    fun `404 means no package`() {
        serve(404)
        assertNull(adapter().findArchive(42L))
    }

    @Test
    fun `another status is a dispatch failure`() {
        serve(500)
        assertThrows(MailDispatchException::class.java) { adapter().findArchive(42L) }
    }

    @Test
    fun `missing token and unreachable component are dispatch failures`() {
        assertThrows(MailDispatchException::class.java) { adapter(token = " ").findArchive(42L) }
        server.stop(0)
        assertThrows(MailDispatchException::class.java) { adapter().findArchive(42L) }
    }
}
