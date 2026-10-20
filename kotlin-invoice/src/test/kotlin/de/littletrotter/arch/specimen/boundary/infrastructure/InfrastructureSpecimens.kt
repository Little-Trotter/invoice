package de.littletrotter.arch.specimen.boundary.infrastructure

import de.littletrotter.arch.specimen.boundary.application.SpecimenLooseRepository
import de.littletrotter.arch.specimen.boundary.application.port.inbound.SpecimenReadModelQueryPort
import de.littletrotter.arch.specimen.boundary.application.port.outbound.SpecimenOutboundPort
import jakarta.enterprise.context.ApplicationScoped

/** Adapter for a loose contract: rule (a) must report the contract. */
@ApplicationScoped
class SpecimenLooseRepositoryAdapter : SpecimenLooseRepository {
    override fun load(id: Long): String? = id.toString()
}

/** Adapter for a contract in `port.outbound`: rule (a) stays silent. */
@ApplicationScoped
class SpecimenOutboundAdapter : SpecimenOutboundPort {
    override fun store(value: String) = Unit
}

/** Read-model adapter behind an input port: rule (a) stays silent. */
@ApplicationScoped
class SpecimenReadModelAdapter : SpecimenReadModelQueryPort {
    override fun count(): Long = 0L
}
