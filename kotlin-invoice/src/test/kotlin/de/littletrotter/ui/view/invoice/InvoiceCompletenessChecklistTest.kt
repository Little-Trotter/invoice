package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.CompletenessCheckDto
import de.littletrotter.domain.compliance.CoreCompletenessRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The checklist mapping on its own: a rule without a checklist label must drop out (the
 * `mapNotNull` branch) instead of surfacing as a raw rule id, and a labelled rule keeps its
 * satisfaction flag.
 */
class InvoiceCompletenessChecklistTest {

    private fun check(ruleId: String, satisfied: Boolean = true) = CompletenessCheckDto(ruleId = ruleId, satisfied = satisfied)

    @Test
    fun `a rule with no checklist label is silently dropped, not shown as a raw ruleId`() {
        val checks = listOf(check("SOME.UNLABELLED.RULE"))
        assertEquals(emptyList<InvoiceCompletenessCheck>(), checks.taxRelevantCompletenessChecks())
    }

    @Test
    fun `a labelled rule maps to its translation key and satisfaction flag`() {
        val checks = listOf(check(CoreCompletenessRules.INVOICE_NUMBER, satisfied = false))
        val result = checks.taxRelevantCompletenessChecks()
        assertEquals(1, result.size)
        assertEquals("invoiceDetail.summary.checklist.invoiceNumber", result[0].translationKey)
        assertEquals(false, result[0].isComplete)
    }

    @Test
    fun `every mapped label is distinct and non-blank`() {
        val checks = listOf(
            check(CoreCompletenessRules.INVOICE_NUMBER),
            check(CoreCompletenessRules.INVOICE_DATE),
            check(CoreCompletenessRules.ITEMS),
        )
        val labels = checks.taxRelevantCompletenessChecks().map { it.translationKey }
        assertEquals(3, labels.toSet().size)
        assertTrue(labels.all { it.isNotBlank() })
    }
}
