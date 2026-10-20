package de.littletrotter.xpdf.impl

import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Lets the operator status page learn whether the Typst CLI is reachable *inside this worker* —
 * the same role [VeraPdfStatusResource] plays for veraPDF: a subprocess of this JVM is invisible to
 * any TCP probe from `kotlin-invoice`. Typst is this product's only layout engine.
 *
 * An unreachable CLI is still a real fault (Typst is mandatory), so it maps to a degraded payload,
 * never to HTTP 500 — the status page, not an exception, is where an operator learns about it.
 */
@Path("/status/typst")
class TypstStatusResource @Inject constructor(
    private val compiler: TypstCompiler,
) {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun status(): Response {
        val version = compiler.installedVersion()
        val payload = mapOf(
            "available" to (version != null),
            "version" to (version ?: ""),
            "pinnedVersion" to compiler.pinnedVersion(),
        )
        return Response.ok(payload).build()
    }
}
