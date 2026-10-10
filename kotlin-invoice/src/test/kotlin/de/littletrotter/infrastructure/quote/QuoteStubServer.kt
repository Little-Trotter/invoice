package de.littletrotter.infrastructure.quote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.littletrotter.application.quote.QuoteCaller
import de.littletrotter.application.quote.QuoteCallerProvider
import de.littletrotter.application.quote.QuoteWriteGuard
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList

/** One request the stub saw: everything the offer adapters are expected to put on the wire. */
data class QuoteStubRequest(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * A local stand-in for the offer component on an ephemeral port, shared by the HTTP adapter tests
 * so each of them exercises the real JDK `HttpClient`, the real [QuoteApiClient] and the contract codec.
 */
class QuoteStubServer : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    val requests = CopyOnWriteArrayList<QuoteStubRequest>()
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    /** Answers every request on [path] with [status] and the JSON [body]. */
    fun respond(path: String, status: Int, body: String) =
        respondBytes(path, status, body.toByteArray(Charsets.UTF_8), "application/json")

    /** Answers every request on [path] with [status], raw [body] bytes and the given headers. */
    fun respondBytes(path: String, status: Int, body: ByteArray, contentType: String, extraHeaders: Map<String, String> = emptyMap()) {
        server.createContext(path) { exchange: HttpExchange ->
            requests += QuoteStubRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.rawPath,
                query = exchange.requestURI.rawQuery,
                headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            exchange.responseHeaders.add("Content-Type", contentType)
            extraHeaders.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            if (status == NO_CONTENT) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.write(body)
            }
            exchange.close()
        }
    }

    fun last(): QuoteStubRequest = requests.last()

    override fun close() = server.stop(0)

    companion object {
        private const val NO_CONTENT = 204
        val CALLER = QuoteCaller("anna", listOf("quoting", "admin"))

        /** A [QuoteApiClient] pointed at [baseUrl] whose acting user is [caller]. */
        fun client(
            baseUrl: String,
            caller: QuoteCaller = CALLER,
            token: Optional<String> = Optional.of("s3cr3t"),
            guard: QuoteWriteGuard? = null,
        ): QuoteApiClient =
            QuoteApiClient(baseUrl, token, QuoteCallerProvider { caller }, guard).also {
                it.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            }
    }
}
