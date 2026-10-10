package de.littletrotter.xpdf.impl

import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Lets `kotlin-invoice`'s `DockerStatusService` learn whether the
 * veraPDF CLI is actually reachable *inside this worker*, something it cannot determine on its
 * own: [VeraPdfCliValidator] runs the CLI as a local subprocess of this JVM (`ProcessBuilder`),
 * not as a networked dependency `DockerStatusService`'s TCP probing can see. Without this
 * endpoint, "switch on, component absent" (the System-Status consistency hint the operator status
 * page needs) would be undetectable from `kotlin-invoice`'s side.
 *
 * Read-only, no side effect — mirrors [de.littletrotter.xpdf.ValidationResource]'s existing pattern of injecting the
 * concrete class (not its port), since `installedVersion()` isn't part of [de.littletrotter.shared.VeraPdfValidatorPort].
 */
@Path("/status/verapdf")
class VeraPdfStatusResource @Inject constructor(
    private val validator: VeraPdfCliValidator,
) {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun status(): Response {
        val version = validator.installedVersion()
        val payload = mapOf(
            "available" to (version != null),
            "version" to (version ?: ""),
        )
        return Response.ok(payload).build()
    }
}
