package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.InvoiceIdByNumberQueryPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [InvoiceIdByNumberQueryPort] with a single exact-number read. */
@ApplicationScoped
class InvoiceIdByNumberQueries @Inject constructor(
    private val invoiceNumberReadRepository: InvoiceNumberReadRepository,
) : InvoiceIdByNumberQueryPort {

    override fun findIdsByNumbers(numbers: Set<String>): Map<String, Long> {
        val assigned = numbers.filterTo(mutableSetOf()) { it.isNotBlank() }
        return if (assigned.isEmpty()) emptyMap() else invoiceNumberReadRepository.findIdsByNumbers(assigned)
    }
}
