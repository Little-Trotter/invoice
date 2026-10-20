package de.littletrotter.shared

/**
 * Normalizes server lists for the NATS Java client (io.nats:jnats).
 *
 * Accepts inputs like:
 * - "nats:4222"
 * - "nats://nats:4222"
 * - "nats:4222,localhost:4222"
 *
 * Returns a comma-separated list of "nats://host:port" URLs, as expected by
 * io.nats.client.Options.Builder#servers.
 */
fun normalizeNatsServers(raw: String?): String {
    val defaultValue = "nats://localhost:4222"
    if (raw.isNullOrBlank()) return defaultValue

    val entries = raw
        .split(',', ';')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { normalizeSingleNatsEntry(it) }
        .filter { it.isNotBlank() }
        .toList()

    return if (entries.isEmpty()) defaultValue else entries.joinToString(",")
}

private fun normalizeSingleNatsEntry(entry: String): String {
    val hostPort = entry.substringAfter("://", entry).substringBefore('/').trim()
    return if (hostPort.isBlank()) "" else "nats://$hostPort"
}
