package de.littletrotter.domain.compliance

import java.time.LocalDate
import de.littletrotter.domain.invoice.DueDateWarning
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.dueDateWarning
import de.littletrotter.domain.invoice.resolveServiceEnd
import de.littletrotter.shared.b2b.B2bTerm

/**
 * The due date lies before the invoice date, or before the resolved service end. Deliberately
 * [ComplianceSeverity.WARNING], never [ComplianceSeverity.ERROR]: unlike the invoice date itself
 * (rule 1, [de.littletrotter.domain.invoice.ensureInvoiceDateNotBeforeServiceEnd]), the due date
 * carries no statutory weight, so THIS finding — the finalization-checklist entry — never blocks
 * a save or a finalization on its own.
 *
 * date invariants (PO 2026-09-02) — the `dueDate < invoiceDate` case ([DueDateWarning
 * .BEFORE_INVOICE_DATE]) is, since that decision, ALSO independently rejected as a hard ERROR by
 * [de.littletrotter.domain.invoice.Invoice.validate] itself
 * ([de.littletrotter.domain.exception.InvoiceValidationError.DUE_DATE_BEFORE_INVOICE_DATE]) at
 * every save/edit — a second, stricter net this rule set's own WARNING does not replace or
 * duplicate: this one stays reachable only for a row that predates that invariant (e.g. one
 * written before this decision, or by direct SQL). [DueDateWarning.BEFORE_SERVICE_END] has no such
 * counterpart and is genuinely still non-blocking in every case.
 *
 * The pure comparison itself lives in `domain/invoice` ([dueDateWarning],
 * [de.littletrotter.domain.invoice.resolveServiceEnd]) beside the send gate it shares "service
 * end" with; this rule set is only the thin [ComplianceRuleSet] adapter that turns that result
 * into a [ComplianceFinding] the same way [PeriodConsistencyRuleSet] adapts
 * [PeriodConsistencyRules] — added to [ComplianceRuleSets.DEFAULT] so the existing
 * GenerationJobService → ComplianceFindingMapper → BUSINESS validation-stage report already
 * surfaces it, with no separate UI mechanism needed (WARNING findings never flip that stage's
 * outcome to INVALID, see [de.littletrotter.application.compliance.ComplianceFindingMapper]'s own
 * KDoc).
 */
object DueDateRules : ComplianceRuleSet {

    const val DUE_DATE_BEFORE_INVOICE_DATE = "LT-DUE-01"
    const val DUE_DATE_BEFORE_SERVICE_END = "LT-DUE-02"

    override val id = "DUE_DATE"
    override val levels = setOf(ComplianceLevel.INVOICE)

    override fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding> {
        val invoice = (subject as? ComplianceSubject.OfInvoice)?.invoice ?: return emptyList()
        return evaluate(invoice)
    }

    fun evaluate(invoice: Invoice): List<ComplianceFinding> {
        val serviceEnd = invoice.resolveServiceEnd()
        return when (dueDateWarning(invoice.invoiceDate, serviceEnd, invoice.dueDate)) {
            DueDateWarning.NONE -> emptyList()

            DueDateWarning.BEFORE_INVOICE_DATE -> listOf(
                ComplianceFinding(
                    ruleId = DUE_DATE_BEFORE_INVOICE_DATE,
                    severity = ComplianceSeverity.WARNING,
                    messageKey = "compliance.dueDate.beforeInvoiceDate",
                    businessTerms = listOf(B2bTerm.BT_9, B2bTerm.BT_2),
                    args = listOf(invoice.dueDate, invoice.invoiceDate),
                ),
            )

            DueDateWarning.BEFORE_SERVICE_END -> listOf(
                ComplianceFinding(
                    ruleId = DUE_DATE_BEFORE_SERVICE_END,
                    severity = ComplianceSeverity.WARNING,
                    messageKey = "compliance.dueDate.beforeServiceEnd",
                    businessTerms = listOf(B2bTerm.BT_9, B2bTerm.BT_72, B2bTerm.BT_74),
                    args = listOf(invoice.dueDate, serviceEnd),
                ),
            )
        }
    }
}
