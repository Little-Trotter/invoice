package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.Invoice
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Order
import jakarta.persistence.criteria.Root
import java.time.LocalDate

/**
 * The invoice overview's default deterministic order: newest invoice date
 * first, ties broken by descending id, so a page boundary never depends on the database's own
 * (otherwise unspecified) row-return order. Shared by the two query paths that both apply it
 * verbatim: [InvoiceOverviewQueryServiceImpl.findOverviewAfterCursor] (the scalar DTO projection)
 * and [de.littletrotter.infrastructure.persistence.PanacheInvoiceRepository.findOverviewPage] (the
 * full-entity load) — the two are deliberately mirrored (see that repository method's own KDoc),
 * but the order expression itself had been duplicated rather than shared.
 */
fun invoiceOverviewDefaultOrder(cb: CriteriaBuilder, i: Root<Invoice>): List<Order> =
    listOf(cb.desc(i.get<LocalDate>("invoiceDate")), cb.desc(i.get<Long>("id")))
