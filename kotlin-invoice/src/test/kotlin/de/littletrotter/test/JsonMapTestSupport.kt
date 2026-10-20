package de.littletrotter.test

/**
 * Test-only helpers for drilling into a Jackson-decoded JSON tree (`Map<String, Any?>`,
 * as `readValue<Map<String, Any?>>()` returns it) without a generic `as`/`as?` cast — and its
 * UNCHECKED_CAST finding — at every call site. Used by tests that read job/result payloads back
 * off NATS (`DocumentJobPublisherIT`, `InvoiceReceiptAssignmentIT`).
 */
fun Any?.asJsonMap(): Map<String, Any?>? {
    if (this !is Map<*, *>) return null
    return entries.associate { (key, value) -> key.toString() to value }
}

fun Any?.asJsonMapList(): List<Map<String, Any?>>? {
    if (this !is List<*>) return null
    val result = ArrayList<Map<String, Any?>>(size)
    for (element in this) {
        result.add(element.asJsonMap() ?: return null)
    }
    return result
}

/** Same as [asJsonMap], but fails fast with a descriptive message instead of returning null —
 *  for call sites that treated a wrong shape as a hard test failure already (a non-null `as`). */
fun Any?.requireJsonMap(): Map<String, Any?> {
    check(this is Map<*, *>) { "expected a JSON object (Map), got ${this?.javaClass}" }
    return entries.associate { (key, value) -> key.toString() to value }
}

/** Same as [asJsonMapList], but fails fast — see [requireJsonMap]. */
fun Any?.requireJsonMapList(): List<Map<String, Any?>> {
    check(this is List<*>) { "expected a JSON array (List), got ${this?.javaClass}" }
    return map { it.requireJsonMap() }
}
