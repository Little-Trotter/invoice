package de.littletrotter.arch.specimen.port.inbound

import de.littletrotter.domain.compliance.ComplianceFinding
import de.littletrotter.domain.invoice.Invoice

/**
 * Test-only specimens for the value-type rule in [de.littletrotter.arch.CommandBoundaryArchitectureTest]:
 * query ports whose signatures carry something other than a value type. Never wired into a
 * production path; the package mirrors `..port.inbound..` so the rule's own scope applies.
 */

/** Query port returning the JPA entity [Invoice]. */
interface SpecimenEntityQueryPort {
    fun findInvoice(id: Long): Invoice?
}

/** Query port returning the entity-free domain data class [ComplianceFinding]. */
interface SpecimenDomainDataQueryPort {
    fun findFindings(id: Long): List<ComplianceFinding>
}
