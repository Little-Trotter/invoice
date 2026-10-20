package de.littletrotter.xpdf.impl

import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Lets `kotlin-invoice`'s `DockerStatusService` learn whether the veraPDF service is reachable
 * from this worker, something it cannot determine on its own: the service sits on the internal
 * network behind the worker, not behind anything the invoice application probes. Without this
 * endpoint, "switch on, component absent" (the System-Status consistency hint the operator status
 * page needs) would be undetectable from `kotlin-invoice`'s side.
 *
 * Read-only, no side effect.
 */
@Path("/status/verapdf")
class VeraPdfStatusResource @Inject constructor(
    private val client: VeraPdfRestClient,
) {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun status(): Response {
        val version = client.serviceVersion()
        val payload = mapOf(
            "available" to (version != null),
            "version" to (version ?: ""),
        )
        return Response.ok(payload).build()
    }
}
