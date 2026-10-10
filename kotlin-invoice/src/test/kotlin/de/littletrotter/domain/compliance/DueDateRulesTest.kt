package de.littletrotter.domain.compliance

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.test.FIXED_TODAY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * PO decision 2026-09-01, rule 3 — [DueDateRules] as the
 * [ComplianceRuleSet] adapter around [de.littletrotter.domain.invoice.dueDateWarning]. The
 * comparison logic itself is pinned exhaustively by `InvoiceDateRulesTest`; this file only checks
 * that the adapter wires the result into a [ComplianceFinding] correctly (ruleId, WARNING
 * severity, messageKey, args) and dispatches like every other [ComplianceRuleSet].
 */
class DueDateRulesTest {

    @Test
    fun `no warning condition - empty findings`() {
        val invoice = Invoice().apply {
            invoiceDate = LocalDate.of(2026, 9, 1)
            servicePeriodTo = LocalDate.of(2026, 8, 31)
            dueDate = LocalDate.of(2026, 9, 15)
        }

        assertEquals(emptyList<ComplianceFinding>(), DueDateRules.evaluate(invoice))
    }

    @Test
    fun `due date before invoice date - single WARNING finding with the ruleId, args and severity`() {
        val invoice = Invoice().apply {
            invoiceDate = LocalDate.of(2026, 9, 1)
            dueDate = LocalDate.of(2026, 8, 31)
        }

        val findings = DueDateRules.evaluate(invoice)

        assertEquals(1, findings.size)
        val finding = findings.single()
        assertEquals(DueDateRules.DUE_DATE_BEFORE_INVOICE_DATE, finding.ruleId)
        assertEquals(ComplianceSeverity.WARNING, finding.severity)
        assertTrue(finding.severity != ComplianceSeverity.ERROR, "rule 3 must never block")
        assertEquals("compliance.dueDate.beforeInvoiceDate", finding.messageKey)
        assertEquals(FindingTarget.Header, finding.target)
        assertEquals(listOf(invoice.dueDate, invoice.invoiceDate), finding.args)
    }

    @Test
    fun `due date before service end - single WARNING finding with the ruleId, args and severity`() {
        val invoice = Invoice().apply {
            invoiceDate = LocalDate.of(2026, 9, 1)
            servicePeriodTo = LocalDate.of(2026, 9, 5)
            dueDate = LocalDate.of(2026, 9, 3)
        }

        val findings = DueDateRules.evaluate(invoice)

        assertEquals(1, findings.size)
        val finding = findings.single()
        assertEquals(DueDateRules.DUE_DATE_BEFORE_SERVICE_END, finding.ruleId)
        assertEquals(ComplianceSeverity.WARNING, finding.severity)
        assertEquals("compliance.dueDate.beforeServiceEnd", finding.messageKey)
        assertEquals(listOf(invoice.dueDate, invoice.servicePeriodTo), finding.args)
    }

    @Test
    fun `evaluate(subject) dispatches for an invoice subject the same as evaluate(invoice)`() {
        val invoice = Invoice().apply {
            invoiceDate = LocalDate.of(2026, 9, 1)
            dueDate = LocalDate.of(2026, 8, 31)
        }

        val viaSubject = DueDateRules.evaluate(ComplianceSubject.OfInvoice(invoice), FIXED_TODAY)
        val viaInvoice = DueDateRules.evaluate(invoice)

        assertEquals(viaInvoice, viaSubject)
    }

    @Test
    fun `evaluate(subject) returns empty for a subject this rule set is not responsible for`() {
        val findings = DueDateRules.evaluate(ComplianceSubject.OfArticle(Article().apply { name = "Beratung" }), FIXED_TODAY)

        assertEquals(emptyList<ComplianceFinding>(), findings)
    }

    @Test
    fun `id and level match every other invoice-level rule set`() {
        assertEquals("DUE_DATE", DueDateRules.id)
        assertEquals(setOf(ComplianceLevel.INVOICE), DueDateRules.levels)
    }
}
