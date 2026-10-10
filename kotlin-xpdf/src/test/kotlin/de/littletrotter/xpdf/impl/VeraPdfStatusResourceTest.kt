package de.littletrotter.xpdf.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import jakarta.ws.rs.core.Response

/**
 * `kotlin-invoice`'s `DockerStatusService` reads this endpoint to learn
 * whether the veraPDF CLI is reachable *inside this worker*. Risk focus: veraPDF unavailable must
 * map to a degraded status payload, never HTTP 500 — a worker without the CLI installed is a
 * normal, expected state (see [VeraPdfCliValidator]'s own KDoc), not a server error.
 */
class VeraPdfStatusResourceTest {

    /** `/bin/echo --version` exits 0 with non-empty output on every Mac/Linux worker, so
     *  [VeraPdfCliValidator.installedVersion] resolves to a non-null string without needing a real
     *  veraPDF install — exercising the "CLI reachable" path deterministically. */
    private fun resourceWithReachableCli() =
        VeraPdfStatusResource(VeraPdfCliValidator(cliPath = "/bin/echo", pinnedVersion = "1.30.2"))

    private fun resourceWithUnreachableCli() =
        VeraPdfStatusResource(VeraPdfCliValidator(cliPath = "verapdf-does-not-exist-anywhere", pinnedVersion = "1.30.2"))

    private fun payloadOf(response: Response): Map<String, Any?> {
        val entity = response.entity
        check(entity is Map<*, *>) { "expected a Map response entity, got ${entity?.javaClass}" }
        return entity.entries.associate { (key, value) -> key.toString() to value }
    }

    @Test
    fun `happy path - veraPDF reachable reports available true with a non-blank version`() {
        val response = resourceWithReachableCli().status()
        assertEquals(Response.Status.OK.statusCode, response.status)

        val payload = payloadOf(response)
        assertEquals(true, payload["available"])
        assertTrue((payload["version"] as String).isNotBlank())
    }

    @Test
    fun `boundary - veraPDF unreachable reports available false with an empty version, never throws`() {
        val response = resourceWithUnreachableCli().status()

        val payload = payloadOf(response)
        assertEquals(false, payload["available"])
        assertEquals("", payload["version"])
    }

    @Test
    fun `boundary - veraPDF unreachable still maps to HTTP 200, never 500`() {
        val response = resourceWithUnreachableCli().status()
        assertEquals(Response.Status.OK.statusCode, response.status)
    }

    @Test
    fun `boundary - the payload always carries both keys regardless of availability`() {
        val payload = payloadOf(resourceWithUnreachableCli().status())
        assertTrue(payload.containsKey("available"))
        assertTrue(payload.containsKey("version"))
        assertFalse(payload["version"] == null)
    }
}
