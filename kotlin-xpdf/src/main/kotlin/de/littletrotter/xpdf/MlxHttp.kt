package de.littletrotter.xpdf

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration

/** TCP connect budget — short since a local/LAN oMLX server either accepts immediately or is
 *  down; the long request timeouts ([MlxClient]/[MlxVisionClient]) cover model inference time. */
private const val CONNECT_TIMEOUT_SECONDS = 10L

/** Shared oMLX HTTP plumbing for [MlxClient] and [MlxVisionClient]. */
internal fun newOmlxHttpClient(): HttpClient =
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

/**
 * Builds the OpenAI-compatible `/chat/completions` POST request. Callers guard against
 * null/blank endpoint and apiKey via their `isAvailable()` check before calling.
 */
internal fun buildChatCompletionRequest(
    endpoint: String?,
    apiKey: String?,
    requestBody: String,
    timeoutSec: Long,
): HttpRequest =
    HttpRequest.newBuilder()
        .uri(URI.create("$endpoint/chat/completions"))
        .timeout(Duration.ofSeconds(timeoutSec))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer $apiKey")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build()
