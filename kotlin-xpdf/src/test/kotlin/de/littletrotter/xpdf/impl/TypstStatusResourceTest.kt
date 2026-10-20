package de.littletrotter.xpdf.impl

import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The operator status page reads this endpoint. Risk focus: an unreachable Typst CLI is a real
 * fault (Typst is this product's only layout engine), so it must still map to a degraded payload,
 * never HTTP 500 — the status page, not an exception, is where an operator learns about it.
 */
class TypstStatusResourceTest {

    /** `/bin/echo --version` exits 0 with output on every Mac/Linux worker — the "reachable" path
     *  without a real Typst install. */
    private fun reachable() =
        TypstStatusResource(TypstCompiler(cliPath = "/bin/echo", pinnedVersion = "0.15.1"))

    private fun unreachable() =
        TypstStatusResource(TypstCompiler(cliPath = "typst-does-not-exist-anywhere", pinnedVersion = "0.15.1"))

    private fun payloadOf(response: Response): Map<String, Any?> {
        val entity = response.entity
        check(entity is Map<*, *>) { "expected a Map response entity, got ${entity?.javaClass}" }
        return entity.entries.associate { (key, value) -> key.toString() to value }
    }

    @Test
    fun `happy path - a reachable CLI reports available with a version`() {
        val response = reachable().status()

        assertEquals(Response.Status.OK.statusCode, response.status)
        val payload = payloadOf(response)
        assertEquals(true, payload["available"])
        assertTrue((payload["version"] as String).isNotBlank())
        assertEquals("0.15.1", payload["pinnedVersion"])
    }

    @Test
    fun `boundary - an unreachable CLI reports available false with an empty version, HTTP 200`() {
        val response = unreachable().status()

        assertEquals(Response.Status.OK.statusCode, response.status)
        val payload = payloadOf(response)
        assertEquals(false, payload["available"])
        assertEquals("", payload["version"])
        assertEquals("0.15.1", payload["pinnedVersion"])
    }

    @Test
    fun `boundary - the payload always carries all three keys`() {
        val payload = payloadOf(unreachable().status())

        assertEquals(setOf("available", "version", "pinnedVersion"), payload.keys)
    }
}
