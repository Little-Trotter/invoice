package de.littletrotter.application.invoice

import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.reference.B2bProfileFieldDefinitionService
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.compliance.CoreCompletenessRules
import de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.ValidationProfileRepository
import de.littletrotter.shared.time.ClockTimeSource
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.Instant

/**
 * PO decision 2026-08-31 (Variante b) — [FinalizationPolicy.enforceComplianceRules]'s per-rule
 * escalation: `littletrotter.compliance-rules.blocking-rules` names [de.littletrotter.domain
 * .compliance.ComplianceFinding.ruleId] values that block regardless of the global
 * `littletrotter.compliance-rules.stage`. Exercised against [CoreCompletenessRules
 * .SERVICE_PERIOD_XOR] — a real, already-shipped-blocking-by-default rule — rather than a fake
 * rule id, so this pins the actual shipped default end to end.
 */
class FinalizationPolicyBlockingRulesTest {

    private val time = ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z"))
    private val validationProfileRepo = mock<ValidationProfileRepository>()
    private val generationJobRepo = mock<GenerationJobRepository>()
    private val b2bProfileRuleSet = mock<B2bProfileRuleSet>()
    private val b2bFieldValueService = mock<B2bFieldValueService>()
    private val b2bProfileFieldDefinitionService = mock<B2bProfileFieldDefinitionService>()
    private val featureSettingsGate = mock<FeatureSettingsGate>()

    private fun policy(complianceStage: String = "OBSERVE", blockingRules: String = "") = FinalizationPolicy(
        time, validationProfileRepo, generationJobRepo, b2bProfileRuleSet, b2bFieldValueService, b2bProfileFieldDefinitionService,
        featureSettingsGate,
        "OBSERVE", complianceStage, blockingRules,
    )

    /** No service date anywhere and no items — fails CORE.SERVICE_PERIOD_XOR (ERROR severity),
     *  among others; this test suite only cares that ruleId shows up in the blocking findings. */
    private fun invoiceMissingServiceDate(): Invoice = Invoice().apply {
        id = 1L
        invoiceNumber = InvoiceNumber("TEST-2026-0001")
        senderProfile = SenderProfile().apply { id = 1L }
    }

    @Test
    fun `unlisted blocking-rules value, OBSERVE stage - stays OBSERVE, does not throw`() {
        val p = policy(complianceStage = "OBSERVE", blockingRules = "")
        assertDoesNotThrow { p.enforceComplianceRules(invoiceMissingServiceDate()) }
    }

    @Test
    fun `CORE_SERVICE_PERIOD_XOR named in blocking-rules, OBSERVE stage - escalates to blocking`() {
        val p = policy(complianceStage = "OBSERVE", blockingRules = CoreCompletenessRules.SERVICE_PERIOD_XOR)

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            p.enforceComplianceRules(invoiceMissingServiceDate())
        }
        assertTrue(ex.findings.any { it.ruleId == CoreCompletenessRules.SERVICE_PERIOD_XOR })
    }

    /**
     * finding-fixes (2026-08-31) — regression for the bug FinalizationGateIT caught: [invoiceMissingServiceDate]
     * fails many CORE rules at once (BUYER_VAT_ID, BUYER_ADDRESS, SELLER_* , ITEMS, ...), all of them
     * still legitimately OBSERVE-only since only [CoreCompletenessRules.SERVICE_PERIOD_XOR] is
     * named in blocking-rules here. The thrown exception must report ONLY the forced finding(s) —
     * an unlisted rule must never ride along into a block it did not itself cause.
     */
    @Test
    fun `only the forced rule's own finding is reported - unlisted blocking findings never ride along`() {
        val p = policy(complianceStage = "OBSERVE", blockingRules = CoreCompletenessRules.SERVICE_PERIOD_XOR)

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            p.enforceComplianceRules(invoiceMissingServiceDate())
        }

        assertEquals(
            listOf(CoreCompletenessRules.SERVICE_PERIOD_XOR),
            ex.findings.map { it.ruleId },
            "unlisted findings (e.g. CORE.BUYER_VAT_ID, CORE.BUYER_ADDRESS) must not appear in a per-rule-forced block",
        )
    }

    @Test
    fun `a different, unrelated ruleId in blocking-rules leaves SERVICE_PERIOD_XOR at OBSERVE`() {
        val p = policy(complianceStage = "OBSERVE", blockingRules = "CORE.SOME_OTHER_RULE_NEVER_TRIGGERED")
        assertDoesNotThrow { p.enforceComplianceRules(invoiceMissingServiceDate()) }
    }

    @Test
    fun `multiple comma-separated rule ids, whitespace tolerated - the matching one still escalates`() {
        val p = policy(
            complianceStage = "OBSERVE",
            blockingRules = " CORE.SOME_OTHER_RULE , ${CoreCompletenessRules.SERVICE_PERIOD_XOR} ,",
        )

        assertThrows(InvoiceFinalizationBlockedException::class.java) {
            p.enforceComplianceRules(invoiceMissingServiceDate())
        }
    }

    @Test
    fun `WARN stage without a matching blocking-rules entry - still does not throw`() {
        val p = policy(complianceStage = "WARN", blockingRules = "")
        assertDoesNotThrow { p.enforceComplianceRules(invoiceMissingServiceDate()) }
    }

    @Test
    fun `BLOCK stage throws exactly as before, independent of blocking-rules being empty`() {
        val p = policy(complianceStage = "BLOCK", blockingRules = "")
        assertThrows(InvoiceFinalizationBlockedException::class.java) {
            p.enforceComplianceRules(invoiceMissingServiceDate())
        }
    }

    @Test
    fun `BLOCK stage throws even when blocking-rules also names the same rule - unchanged behavior`() {
        val p = policy(complianceStage = "BLOCK", blockingRules = CoreCompletenessRules.SERVICE_PERIOD_XOR)
        assertThrows(InvoiceFinalizationBlockedException::class.java) {
            p.enforceComplianceRules(invoiceMissingServiceDate())
        }
    }
}
