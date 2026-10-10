package de.littletrotter.infrastructure.http

import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)

/**
 * Builds one service-to-service call: [headers] carry the bearer token and the acting user's
 * identity, and a body is always JSON.
 */
internal fun serviceRequest(
    url: String,
    method: String,
    body: String?,
    headers: List<Pair<String, String>>,
): HttpRequest {
    val builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(REQUEST_TIMEOUT)
    headers.forEach { (name, value) -> builder.header(name, value) }
    if (body != null) builder.header("Content-Type", "application/json")
    return builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build()
}
