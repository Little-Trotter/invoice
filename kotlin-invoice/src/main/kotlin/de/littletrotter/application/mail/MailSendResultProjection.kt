package de.littletrotter.application.mail

import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.repository.InvoiceRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.ZoneOffset

/**
 * Projects a [MailDeliveryReport] onto the invoice's two read-model columns
 * ([de.littletrotter.domain.invoice.Invoice.mailSent] / `mailSentAt`).
 *
 * This used to consume the contract's `MailSendResultEvent`/`MailSendOutcome`
 * directly. That decoding step moved to `de.littletrotter.infrastructure.messaging.MailSendResultConsumer`
 * plus `de.littletrotter.infrastructure.messaging.acl.MailSendResultTranslator`, which turn the wire
 * event into this application's own [MailDeliveryReport] before calling [project] — nothing in
 * `application.mail` imports `de.littletrotter.shared.mail..` any more.
 *
 * **This is a read model, not a second source of truth.** The mail component's append-only journal
 * remains the record of what was sent; these two columns exist so the invoice list and the invoice
 * detail view can show "versendet am …" without a synchronous call into a component that may be
 * absent, stopped or upgrading. Nothing here is evidence for an audit — the mail journal view is.
 *
 * Three behaviours the consumer above it relies on, all of them decided here rather than at the
 * NATS boundary:
 *
 * - **Idempotent.** The same event delivered twice changes nothing: JetStream guarantees
 *   at-least-once, so a redelivery after an ack that never made it back is normal traffic, not an
 *   error. [MailProjectionOutcome.DUPLICATE] says so explicitly instead of the caller having to
 *   infer it from an unchanged row.
 * - **Tolerant of an unknown [MailDeliveryReport.referenceKey].** The key is opaque to the
 *   component and is echoed back verbatim; an invoice may have been deleted, or the mail may
 *   simply not be about an invoice at all. That is logged and acknowledged
 *   ([MailProjectionOutcome.UNKNOWN_REFERENCE]) — never a crash and never a redelivery loop.
 * - **[MailDeliveryOutcome.FAILED] writes nothing.** PO decision 6 gives this projection exactly two
 *   columns and no failure state; a failed attempt lives in the component's journal, where the
 *   retry that follows it lives too. Recording it here would need a third column and would make
 *   "not sent" ambiguous between "never tried" and "tried and failed".
 */
@ApplicationScoped
class MailSendResultProjection @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
) {

    private val log = Logger.getLogger(MailSendResultProjection::class.java)

    /** Applies [report] and reports what it did; see the class KDoc for each outcome's meaning. */
    @Transactional
    fun project(report: MailDeliveryReport): MailProjectionOutcome {
        if (report.outcome == MailDeliveryOutcome.FAILED) {
            log.infof(
                "Mail journal %d reports FAILED for reference '%s' — the component's journal keeps the detail, no invoice column is written",
                report.journalId, report.referenceKey,
            )
            return MailProjectionOutcome.FAILURE_NOT_PROJECTED
        }

        // A key that is no invoice number belongs to some other document family; it must never
        // become an ERROR plus a terminated message per delivery.
        if (!isInvoiceNumber(report.referenceKey)) {
            log.warnf(
                "Mail send result with reference '%s' (journal %d) is not an invoice number — ignored",
                report.referenceKey, report.journalId,
            )
            return MailProjectionOutcome.INVALID_REFERENCE
        }

        val invoice = invoiceRepository.findByInvoiceNumber(report.referenceKey)
        if (invoice == null) {
            log.warnf(
                "Mail send result for unknown reference '%s' (journal %d) — acknowledged, nothing projected",
                report.referenceKey, report.journalId,
            )
            return MailProjectionOutcome.UNKNOWN_REFERENCE
        }

        val sentAt = report.sentAt.atOffset(ZoneOffset.UTC)
        if (invoice.mailSent == true && invoice.mailSentAt?.toInstant() == report.sentAt) {
            log.debugf("Mail send result for '%s' already projected (journal %d) — no-op", report.referenceKey, report.journalId)
            return MailProjectionOutcome.DUPLICATE
        }

        invoice.mailSent = true
        invoice.mailSentAt = sentAt
        invoiceRepository.save(invoice)
        log.infof("Invoice '%s' marked as mailed at %s (journal %d)", report.referenceKey, sentAt, report.journalId)
        return MailProjectionOutcome.APPLIED
    }

    private fun isInvoiceNumber(key: String): Boolean =
        try {
            InvoiceNumber(key)
            true
        } catch (_: InvoiceValidationException) {
            false
        }
}

/**
 * What [MailSendResultProjection.project] did with one report. Returned rather than logged only, so
 * the consumer's tests can assert "this delivery changed nothing" without reading log output.
 */
enum class MailProjectionOutcome {
    /** The invoice's flag and timestamp were written. */
    APPLIED,

    /** A redelivery of an already-projected report — the row was left untouched. */
    DUPLICATE,

    /** No invoice carries that reference key; logged and acknowledged. */
    UNKNOWN_REFERENCE,

    /** The reference key is not a valid invoice number, so it cannot name one of our invoices. */
    INVALID_REFERENCE,

    /** The result belongs to another document kind (e.g. an offer mail); never looked up here. */
    OTHER_DOCUMENT_KIND,

    /** A [MailDeliveryOutcome.FAILED] report; by design this projection has no column for it. */
    FAILURE_NOT_PROJECTED,
}
