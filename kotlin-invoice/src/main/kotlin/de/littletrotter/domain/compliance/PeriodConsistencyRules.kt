package de.littletrotter.domain.compliance

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.shared.b2b.B2bTerm
import java.time.LocalDate

/**
 * A line's service period (BG-26) in checkable form.
 *
 * Deliberately its own JPA-free input type instead of
 * [de.littletrotter.domain.invoice.InvoiceItem]: the rule set should be testable without a
 * persistence context or a half-populated entity, the same pattern
 * [de.littletrotter.domain.compliance.FinalizationGate] applies with its primitive parameters.
 * The bridge to the entity is [Invoice.periodInput].
 */
data class LinePeriodInput(
    val positionId: String,
    /** BT-134. */
    val from: LocalDate?,
    /** BT-135. */
    val to: LocalDate?,
) {
    val hasPeriod: Boolean get() = from != null || to != null
}

/** Header data for [PeriodConsistencyRules.evaluate]. See [LinePeriodInput] for why. */
data class InvoicePeriodInput(
    /** BT-2. */
    val invoiceDate: LocalDate?,
    /** BT-72 — the invoice's *own* delivery date, not the export fallback to BT-74. */
    val deliveryDate: LocalDate?,
    /** BT-73. */
    val periodFrom: LocalDate?,
    /** BT-74. */
    val periodTo: LocalDate?,
    val lines: List<LinePeriodInput> = emptyList(),
) {
    val hasPeriod: Boolean get() = periodFrom != null || periodTo != null
}

/**
 * Two classes of rule, distinguished by prefix:
 *
 * | Rule | Source | Statement |
 * |---|---|---|
 * | `BR-29`   | EN 16931 | BT-74 ≥ BT-73 |
 * | `BR-30`   | EN 16931 | BT-135 ≥ BT-134 (per line) |
 * | `LT-PER-01` | house rule | BT-134 ≥ BT-73 |
 * | `LT-PER-02` | house rule | BT-135 ≤ BT-74 |
 * | `LT-PER-03` | house rule | BT-72 ∈ [BT-73, BT-74] |
 * | `LT-PER-04` | house rule (WARNING) | BG-26 without BG-14 |
 *
 * The `LT-*` rules deliberately go beyond the standard: EN 16931 defines no relationship between
 * BG-14 and BG-26, so a line may formally lie outside the invoice period. For an invoice whose
 * header period names the billing period, a line outside it is a data-entry error, not a valid
 * value — that's the interpretation applied here.
 *
 * Each containment rule checks only the side whose both endpoints are set: a line with BT-134 but
 * no BT-73 therefore never produces an `LT-PER-01` finding, at most `LT-PER-04` — a missing value
 * is not a contradictory one.
 */
object PeriodConsistencyRules {

    const val BR_29 = "BR-29"
    const val BR_30 = "BR-30"
    const val LINE_PERIOD_STARTS_BEFORE_INVOICE_PERIOD = "LT-PER-01"
    const val LINE_PERIOD_ENDS_AFTER_INVOICE_PERIOD = "LT-PER-02"
    const val DELIVERY_DATE_OUTSIDE_INVOICE_PERIOD = "LT-PER-03"
    const val LINE_PERIOD_WITHOUT_INVOICE_PERIOD = "LT-PER-04"

    fun evaluate(input: InvoicePeriodInput): List<ComplianceFinding> = buildList {
        addAll(evaluateHeader(input))
        input.lines.forEach { line -> addAll(evaluateLine(input, line)) }
    }

    private fun evaluateHeader(input: InvoicePeriodInput): List<ComplianceFinding> = buildList {
        val from = input.periodFrom
        val to = input.periodTo

        if (from != null && to != null && to.isBefore(from)) {
            add(
                ComplianceFinding(
                    ruleId = BR_29,
                    severity = ComplianceSeverity.ERROR,
                    messageKey = "compliance.period.invoicePeriodEndBeforeStart",
                    businessTerms = listOf(B2bTerm.BT_73, B2bTerm.BT_74),
                    args = listOf(from, to),
                ),
            )
        }

        val delivery = input.deliveryDate ?: return@buildList
        // Check only against the endpoints that exist: a one-sided open header period ("from
        // 1.3.") doesn't rule out a delivery date after the start.
        val beforeStart = from != null && delivery.isBefore(from)
        val afterEnd = to != null && delivery.isAfter(to)
        if (beforeStart || afterEnd) {
            add(
                ComplianceFinding(
                    ruleId = DELIVERY_DATE_OUTSIDE_INVOICE_PERIOD,
                    severity = ComplianceSeverity.ERROR,
                    messageKey = "compliance.period.deliveryDateOutsideInvoicePeriod",
                    businessTerms = listOf(B2bTerm.BT_72, B2bTerm.BT_73, B2bTerm.BT_74),
                    args = listOf(delivery, from, to),
                ),
            )
        }
    }

    // Each check below returns the ComplianceFinding it would add, or null — extracted out of
    // evaluateLine (CognitiveComplexMethod) so that method is just the four checks' own
    // applicability order, not their individual conditions.
    private fun evaluateLine(input: InvoicePeriodInput, line: LinePeriodInput): List<ComplianceFinding> = buildList {
        lineEndBeforeStartFinding(line)?.let { add(it) }

        if (!line.hasPeriod) return@buildList

        if (!input.hasPeriod) {
            // Without a header period there's nothing for the line to lie within — the two
            // containment rules are skipped rather than checked against null.
            add(linePeriodWithoutInvoicePeriodFinding(line))
            return@buildList
        }

        lineStartsBeforeInvoicePeriodFinding(input, line)?.let { add(it) }
        lineEndsAfterInvoicePeriodFinding(input, line)?.let { add(it) }
    }

    private fun lineEndBeforeStartFinding(line: LinePeriodInput): ComplianceFinding? {
        val lineFrom = line.from
        val lineTo = line.to
        if (lineFrom == null || lineTo == null || !lineTo.isBefore(lineFrom)) return null
        return ComplianceFinding(
            ruleId = BR_30,
            severity = ComplianceSeverity.ERROR,
            messageKey = "compliance.period.linePeriodEndBeforeStart",
            businessTerms = listOf(B2bTerm.BT_134, B2bTerm.BT_135),
            target = FindingTarget.Position(line.positionId),
            args = listOf(lineFrom, lineTo),
        )
    }

    private fun linePeriodWithoutInvoicePeriodFinding(line: LinePeriodInput): ComplianceFinding =
        ComplianceFinding(
            ruleId = LINE_PERIOD_WITHOUT_INVOICE_PERIOD,
            severity = ComplianceSeverity.WARNING,
            messageKey = "compliance.period.linePeriodWithoutInvoicePeriod",
            businessTerms = listOf(B2bTerm.BT_73, B2bTerm.BT_74, B2bTerm.BT_134, B2bTerm.BT_135),
            target = FindingTarget.Position(line.positionId),
        )

    private fun lineStartsBeforeInvoicePeriodFinding(input: InvoicePeriodInput, line: LinePeriodInput): ComplianceFinding? {
        val headerFrom = input.periodFrom
        val lineFrom = line.from
        if (headerFrom == null || lineFrom == null || !lineFrom.isBefore(headerFrom)) return null
        return ComplianceFinding(
            ruleId = LINE_PERIOD_STARTS_BEFORE_INVOICE_PERIOD,
            severity = ComplianceSeverity.ERROR,
            messageKey = "compliance.period.linePeriodStartsBeforeInvoicePeriod",
            businessTerms = listOf(B2bTerm.BT_73, B2bTerm.BT_134),
            target = FindingTarget.Position(line.positionId),
            args = listOf(lineFrom, headerFrom),
        )
    }

    private fun lineEndsAfterInvoicePeriodFinding(input: InvoicePeriodInput, line: LinePeriodInput): ComplianceFinding? {
        val headerTo = input.periodTo
        val lineTo = line.to
        if (headerTo == null || lineTo == null || !lineTo.isAfter(headerTo)) return null
        return ComplianceFinding(
            ruleId = LINE_PERIOD_ENDS_AFTER_INVOICE_PERIOD,
            severity = ComplianceSeverity.ERROR,
            messageKey = "compliance.period.linePeriodEndsAfterInvoicePeriod",
            businessTerms = listOf(B2bTerm.BT_74, B2bTerm.BT_135),
            target = FindingTarget.Position(line.positionId),
            args = listOf(lineTo, headerTo),
        )
    }
}

/**
 * Reads [Invoice.deliveryDate] raw — **without** the export fallback to BT-74/BT-2: for this
 * check, "no delivery date recorded" is different from "delivery date equals period end", and a
 * BT-72 derived from BT-74 would trivially always be valid against the header period.
 */
fun Invoice.periodInput(): InvoicePeriodInput = InvoicePeriodInput(
    invoiceDate = invoiceDate,
    deliveryDate = deliveryDate,
    periodFrom = servicePeriodFrom,
    periodTo = servicePeriodTo,
    lines = itemList.map { LinePeriodInput(it.positionId, it.servicePeriodFrom, it.servicePeriodTo) },
)

fun Invoice.evaluatePeriodConsistency(): List<ComplianceFinding> =
    PeriodConsistencyRules.evaluate(periodInput())

/**
 * The evaluation itself stays a pure function on [InvoicePeriodInput]; this adapter only adds
 * level and id. Deliberate split: the rules are testable without an entity, and a caller that
 * already has data instead of an `Invoice` (import, preview) can use them directly.
 */
object PeriodConsistencyRuleSet : ComplianceRuleSet {
    override val id = "PERIOD"
    override val levels = setOf(ComplianceLevel.INVOICE)

    override fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding> {
        val invoice = (subject as? ComplianceSubject.OfInvoice)?.invoice ?: return emptyList()
        return PeriodConsistencyRules.evaluate(invoice.periodInput())
    }
}
