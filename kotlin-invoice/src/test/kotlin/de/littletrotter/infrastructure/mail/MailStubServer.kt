package de.littletrotter.infrastructure.mail

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.littletrotter.application.mail.port.outbound.MailCaller
import de.littletrotter.application.mail.port.outbound.MailCallerProvider
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList

/** One request the stub saw: everything the mail adapters are expected to put on the wire. */
data class StubRequest(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * A local stand-in for the mail component on an ephemeral port, shared by the HTTP adapter tests so
 * each of them exercises the real JDK `HttpClient`, the real [MailApiClient] and the contract codec.
 */
class MailStubServer : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    val requests = CopyOnWriteArrayList<StubRequest>()
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    /** Answers every request on [path] with [status] and [body]. */
    fun respond(path: String, status: Int, body: String) {
        server.createContext(path) { exchange: HttpExchange ->
            requests += StubRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                query = exchange.requestURI.rawQuery,
                headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
    }

    fun last(): StubRequest = requests.last()

    override fun close() = server.stop(0)

    companion object {
        val CALLER = MailCaller("anna", listOf("invoicing", "admin"), "anna@example.com")

        /** A [MailApiClient] pointed at [baseUrl] whose acting user is [caller]. */
        fun client(
            baseUrl: String,
            caller: MailCaller = CALLER,
            token: Optional<String> = Optional.of("s3cr3t"),
        ): MailApiClient =
            MailApiClient(baseUrl, token, MailCallerProvider { caller }).also {
                it.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            }
    }
}
