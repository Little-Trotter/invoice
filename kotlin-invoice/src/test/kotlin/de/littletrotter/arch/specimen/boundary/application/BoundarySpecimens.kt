package de.littletrotter.arch.specimen.boundary.application

import de.littletrotter.arch.specimen.boundary.application.port.inbound.SpecimenQueryPort
import jakarta.enterprise.context.ApplicationScoped

/**
 * Test-only specimens for [de.littletrotter.arch.PortBoundaryArchitectureTest]. The package
 * mirrors `application` so the rules' own scope applies; nothing here is wired into production.
 */

/** A container-managed service: a view must reach it through an input port, never directly. */
@ApplicationScoped
class SpecimenAppService {
    fun work(): String = "work"
}

/** A service contract with a container-managed implementation: equally off limits for a view. */
interface SpecimenServiceContract {
    fun work(): String
}

@ApplicationScoped
class SpecimenServiceContractBean : SpecimenServiceContract {
    override fun work(): String = "work"
}

/** Implementation behind the input port; the port itself is what a view may use. */
@ApplicationScoped
class SpecimenQueries : SpecimenQueryPort {
    override fun find(id: Long): String? = id.toString()
}

/** A plain value carrier in the application package: data, not a service, so a view may use it. */
data class SpecimenValueCarrier(val value: String)

/** A contract nobody implements with a container-managed bean (e.g. a marker): not a service. */
interface SpecimenMarkerContract

/** Implemented by a value carrier only. */
data class SpecimenMarkedCarrier(val value: String) : SpecimenMarkerContract

/** Loose contract implemented by an infrastructure adapter although it sits outside `port.outbound`. */
interface SpecimenLooseRepository {
    fun load(id: Long): String?
}

/** Contract implemented inside the application only: rule (a) stays silent. */
interface SpecimenInternalContract {
    fun compute(): Int
}

class SpecimenInternalContractImpl : SpecimenInternalContract {
    override fun compute(): Int = 1
}
