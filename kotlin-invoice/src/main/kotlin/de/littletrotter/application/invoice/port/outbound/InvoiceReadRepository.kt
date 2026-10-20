package de.littletrotter.application.invoice.port.outbound

import de.littletrotter.application.dto.InvoiceDetailDto

/**
 * Deliberately placed in `application`, not `domain.repository`: this is the CQRS read side.
 * It returns a UI-shaped [InvoiceDetailDto] rather than a domain [de.littletrotter.domain.invoice.Invoice],
 * so it belongs with the other read/query services in this package, not with the write-side
 * aggregate ports in `domain.repository` (arc-improvements AR11.8).
 */
fun interface InvoiceReadRepository {
    fun findInvoiceDetailDto(id: Long): InvoiceDetailDto?
}
