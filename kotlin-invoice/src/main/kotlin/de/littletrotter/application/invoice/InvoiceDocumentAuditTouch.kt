package de.littletrotter.application.invoice

import de.littletrotter.application.shared.AuditActionOverride
import de.littletrotter.domain.compliance.InvoiceAuditAction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager

/**
 * Journals an event that changes no business column of an `invoice_document` row (a rejected
 * send, a restore of an already valid row). The audit trigger only fires on a row change, so the
 * row's optimistic-lock version is bumped with a native update; the version is stripped from the
 * journal detail, which therefore carries only the row snapshot and the event context.
 *
 * Must run inside an active transaction.
 */
@ApplicationScoped
class InvoiceDocumentAuditTouch @Inject constructor(
    private val em: EntityManager,
    private val auditActionOverride: AuditActionOverride,
) {
    fun touch(documentId: Long, action: InvoiceAuditAction, context: Map<String, Any?>) {
        auditActionOverride.withAction(action, context) { bumpVersion(documentId) }
    }

    /** Native bump so a managed copy of the row is not marked dirty. Returns the rows changed. */
    fun bumpVersion(documentId: Long): Int =
        em.createNativeQuery("UPDATE invoice_document SET version = version + 1 WHERE id = :id")
            .setParameter("id", documentId)
            .executeUpdate()
}
