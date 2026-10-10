package de.littletrotter.domain.repository

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import java.time.OffsetDateTime

/**
 * Row-level filter (S107) of [InvoiceAuditJournalRepository.findPage]/
 * [InvoiceAuditJournalRepository.countPage]. Every field is an exact-match or range bound; `null`
 * means "no restriction on this dimension". [occurredFrom] is inclusive, [occurredTo] EXCLUSIVE
 * (the caller passes the start of the day after the picked "to" date). A read-only query value:
 * it never changes what the journal persists, orders or hashes.
 */
data class InvoiceAuditRowFilter(
    val invoiceNumber: String? = null,
    val actor: String? = null,
    val event: InvoiceAuditEvent? = null,
    val occurredFrom: OffsetDateTime? = null,
    val occurredTo: OffsetDateTime? = null,
) {
    companion object {
        val EMPTY = InvoiceAuditRowFilter()
    }
}

/**
 * Business-action filter (S107) of [InvoiceAuditJournalRepository.findActionGroupsPage]/
 * [InvoiceAuditJournalRepository.countActionGroupsPage]. [actions] is an allow-list (empty = no
 * filter); the other fields follow [InvoiceAuditRowFilter]'s null/inclusive/exclusive conventions.
 */
data class InvoiceAuditActionFilter(
    val actions: Set<InvoiceAuditAction> = emptySet(),
    val invoiceNumber: String? = null,
    val actor: String? = null,
    val occurredFrom: OffsetDateTime? = null,
    val occurredTo: OffsetDateTime? = null,
) {
    companion object {
        val EMPTY = InvoiceAuditActionFilter()
    }
}
