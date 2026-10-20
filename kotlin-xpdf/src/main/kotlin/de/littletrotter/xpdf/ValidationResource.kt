package de.littletrotter.xpdf

import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidatorPort
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/validate")
class ValidationResource @Inject constructor(
    private val validator: KositValidatorPort,
) {

    @POST
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_JSON)
    fun validate(xml: ByteArray): Response {
        val result = validator.validate(String(xml, Charsets.UTF_8))
        val ok = result.status == KositOutcome.VALID
        val payload = mapOf(
            "ok" to ok,
            "messages" to result.details.map { it.message },
            "status" to result.status.name,
            "validatorVersion" to (result.validatorVersion ?: ""),
        )
        return if (ok) Response.ok(payload).build() else Response.status(Response.Status.BAD_REQUEST).entity(payload).build()
    }
}
