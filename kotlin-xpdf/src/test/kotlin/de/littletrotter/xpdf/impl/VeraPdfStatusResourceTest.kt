package de.littletrotter.xpdf.impl

import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * `kotlin-invoice`'s `DockerStatusService` reads this endpoint to learn whether the veraPDF service
 * is reachable from this worker. An unreachable service must map to a degraded status payload,
 * never HTTP 500: a stack without the service is a normal, expected state, not a server error.
 */
class VeraPdfStatusResourceTest {

    private fun resource(version: String?): VeraPdfStatusResource {
        val client: VeraPdfRestClient = mock()
        whenever(client.serviceVersion()).thenReturn(version)
        return VeraPdfStatusResource(client)
    }

    private fun payloadOf(response: Response): Map<String, Any?> {
        val entity = response.entity
        check(entity is Map<*, *>) { "expected a Map response entity, got ${entity?.javaClass}" }
        return entity.entries.associate { (key, value) -> key.toString() to value }
    }

    @Test
    fun `a reachable service reports available with its version`() {
        val response = resource("1.30.2").status()

        assertEquals(Response.Status.OK.statusCode, response.status)
        val payload = payloadOf(response)
        assertEquals(true, payload["available"])
        assertEquals("1.30.2", payload["version"])
    }

    @Test
    fun `an unreachable service reports unavailable with an empty version and still answers 200`() {
        val response = resource(null).status()

        assertEquals(Response.Status.OK.statusCode, response.status)
        val payload = payloadOf(response)
        assertEquals(false, payload["available"])
        assertEquals("", payload["version"])
        assertTrue(payload.containsKey("version"))
    }
}
