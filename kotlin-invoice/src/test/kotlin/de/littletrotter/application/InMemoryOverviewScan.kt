package de.littletrotter.application

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus

/**
 * The free-text overview scan [de.littletrotter.domain.repository.InvoiceRepository] used
 * to provide as a default method, inherited unnoticed by every test double and silently overridden
 * with real JPQL only in [de.littletrotter.infrastructure.persistence.PanacheInvoiceRepository].
 *
 * A domain port describes what is fetched, not how it is searched, so the port itself no longer
 * carries this algorithm (arc-improvements AR7). Test doubles that need overview paging/counting
 * call these functions explicitly instead.
 */
object InMemoryOverviewScan {

    fun matches(invoice: Invoice, filterText: String): Boolean {
        val q = filterText.lowercase()
        return invoice.invoiceNumber.value.lowercase().contains(q) ||
            (invoice.recipient?.name?.lowercase()?.contains(q) == true) ||
            ((invoice.selectedSeller?.name ?: invoice.senderProfile?.name ?: "").lowercase().contains(q))
    }

    fun findOverviewPage(
        all: List<Invoice>,
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        status: InvoiceStatus?,
    ): List<Invoice> {
        if (pageIndex < 0 || pageSize <= 0) return emptyList()
        val filtered = all
            .let { list -> if (status != null) list.filter { it.status == status } else list }
            .let { list -> if (!filterText.isNullOrBlank()) list.filter { matches(it, filterText) } else list }
        val from = pageIndex * pageSize
        if (from >= filtered.size) return emptyList()
        return filtered.subList(from, minOf(filtered.size, from + pageSize))
    }

    fun countForOverview(all: List<Invoice>, filterText: String?, status: InvoiceStatus?): Long {
        val filtered = all
            .let { list -> if (status != null) list.filter { it.status == status } else list }
            .let { list -> if (!filterText.isNullOrBlank()) list.filter { matches(it, filterText) } else list }
        return filtered.size.toLong()
    }
}
