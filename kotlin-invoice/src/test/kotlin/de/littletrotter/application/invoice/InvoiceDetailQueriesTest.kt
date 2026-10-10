package de.littletrotter.application.invoice

import de.littletrotter.application.dto.B2bFindingDto
import de.littletrotter.application.dto.CompletenessCheckDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ModelReport
import de.littletrotter.application.dto.PartySnapshotDiffDto
import de.littletrotter.application.dto.SnapshotFieldChangeDto
import de.littletrotter.domain.compliance.ComplianceFinding
import de.littletrotter.domain.compliance.ComplianceSeverity
import de.littletrotter.domain.compliance.FindingTarget
import de.littletrotter.domain.compliance.CoreCompletenessRules
import de.littletrotter.domain.exception.GenerationBlockReason
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.domain.invoice.PartySnapshotFieldDiff
import de.littletrotter.domain.invoice.PartySnapshotRefreshPreview
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceLlmAnalysisRepository
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * [InvoiceDetailQueries]'s own tests: the compliance/completeness/LLM/action-gate reads behind
 * [de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort].
 */
class InvoiceDetailQueriesTest {

    private fun queries(
        invoiceReadRepository: InvoiceReadRepository = mock(),
        invoiceLifecycleService: InvoiceLifecycleService = mock(),
        invoiceStornoService: InvoiceStornoService = mock(),
        generationJobRepository: GenerationJobRepository = mock(),
        llmAnalysisRepository: InvoiceLlmAnalysisRepository = mock(),
        b2bProfileRuleSet: B2bProfileRuleSet? = null,
    ) = InvoiceDetailQueries(invoiceReadRepository, invoiceLifecycleService, invoiceStornoService, generationJobRepository, llmAnalysisRepository, b2bProfileRuleSet)

    @Test
    fun `happy path - findInvoiceDetailDto delegates to InvoiceReadRepository`() {
        val dto = mock<InvoiceDetailDto>()
        val invoiceReadRepository = mock<InvoiceReadRepository> { on { findInvoiceDetailDto(1L) }.thenReturn(dto) }
        val queries = queries(invoiceReadRepository = invoiceReadRepository)

        assertEquals(dto, queries.findInvoiceDetailDto(1L))
    }

    @Test
    fun `findB2bComplianceFindings ist leer ohne konfiguriertes B2B-Profil-Regelwerk`() {
        val lifecycleService = mock<InvoiceLifecycleService>()
        val queries = queries(invoiceLifecycleService = lifecycleService, b2bProfileRuleSet = null)

        assertTrue(queries.findB2bComplianceFindings(1L).isEmpty())
        verify(lifecycleService, never()).findById(any())
    }

    @Test
    fun `findB2bComplianceFindings ist leer wenn die Rechnung nicht existiert`() {
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(99L) }.thenReturn(null) }
        val ruleSet = mock<B2bProfileRuleSet>()
        val queries = queries(invoiceLifecycleService = lifecycleService, b2bProfileRuleSet = ruleSet)

        assertTrue(queries.findB2bComplianceFindings(99L).isEmpty())
        verify(ruleSet, never()).evaluate(any<Invoice>())
    }

    @Test
    fun `findB2bComplianceFindings maps the rule set's findings to read models when profile and invoice exist`() {
        val invoice = Invoice().apply { id = 2L }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(2L) }.thenReturn(invoice) }
        val finding = ComplianceFinding(
            ruleId = "B2B.MANDATORY_FIELD",
            severity = ComplianceSeverity.ERROR,
            messageKey = "compliance.b2b.mandatoryFieldMissingIfEmpty",
            businessTerms = listOf(B2bTerm.BT_72, B2bTerm.BT_134),
            target = FindingTarget.Position("3"),
            args = listOf("BT-72", "Lieferdatum"),
        )
        val ruleSet = mock<B2bProfileRuleSet> { on { evaluate(invoice) }.thenReturn(listOf(finding)) }
        val queries = queries(invoiceLifecycleService = lifecycleService, b2bProfileRuleSet = ruleSet)

        assertEquals(
            listOf(B2bFindingDto("B2B.MANDATORY_FIELD", listOf("BT-72", "BT-134"), "Lieferdatum", "3")),
            queries.findB2bComplianceFindings(2L),
        )
    }

    @Test
    fun `findCompletenessChecks ist leer wenn die Rechnung nicht existiert`() {
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(99L) }.thenReturn(null) }
        val queries = queries(invoiceLifecycleService = lifecycleService)

        assertTrue(queries.findCompletenessChecks(99L).isEmpty())
    }

    @Test
    fun `findCompletenessChecks liefert die CoreCompletenessRules-Pruefungen fuer eine vorhandene Rechnung`() {
        val invoice = Invoice().apply { id = 3L }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(3L) }.thenReturn(invoice) }
        val queries = queries(invoiceLifecycleService = lifecycleService)

        val result = queries.findCompletenessChecks(3L)

        assertEquals(CoreCompletenessRules.checks(invoice).map { CompletenessCheckDto(it.ruleId, it.isSatisfied) }, result)
    }

    @Test
    fun `happy path - findLlmAnalyses maps every analysis for an existing invoiceId`() {
        val analysis = InvoiceLlmAnalysis().apply {
            modelName = "gpt-test"
            status = LlmAnalysisStatus.COMPLETED
        }
        val llmAnalysisRepository = mock<InvoiceLlmAnalysisRepository> { on { findByInvoiceId(7L) }.thenReturn(listOf(analysis)) }
        val queries = queries(llmAnalysisRepository = llmAnalysisRepository)

        val result = queries.findLlmAnalyses(7L)

        assertEquals(listOf(ModelReport.from(analysis)), result)
        verify(llmAnalysisRepository).findByInvoiceId(7L)
    }

    @Test
    fun `boundary - findLlmAnalyses is empty for a null invoiceId without touching the repository`() {
        val llmAnalysisRepository = mock<InvoiceLlmAnalysisRepository>()
        val queries = queries(llmAnalysisRepository = llmAnalysisRepository)

        assertTrue(queries.findLlmAnalyses(null).isEmpty())
        verify(llmAnalysisRepository, never()).findByInvoiceId(any())
    }

    @Test
    fun `canCreateCreditNote delegates to InvoiceStornoService for a found invoice`() {
        val invoice = Invoice().apply { id = 5L }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(5L) }.thenReturn(invoice) }
        val invoiceStornoService = mock<InvoiceStornoService> { on { canCreateCreditNote(invoice) }.thenReturn(true) }
        val queries = queries(invoiceLifecycleService = lifecycleService, invoiceStornoService = invoiceStornoService)

        assertTrue(queries.canCreateCreditNote(5L))
        verify(invoiceStornoService).canCreateCreditNote(invoice)
    }

    @Test
    fun `canCreateCreditNote reflects a status change between two reads of the same request`() {
        val review = Invoice().apply { id = 7L }.also { it.forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val sent = Invoice().apply { id = 7L }.also { it.forceStatusForTest(InvoiceStatus.SENT) }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(7L) }.thenReturn(review, sent) }
        val invoiceStornoService = mock<InvoiceStornoService> {
            on { canCreateCreditNote(review) }.thenReturn(false)
            on { canCreateCreditNote(sent) }.thenReturn(true)
        }
        val queries = queries(invoiceLifecycleService = lifecycleService, invoiceStornoService = invoiceStornoService)

        assertFalse(queries.canCreateCreditNote(7L))
        assertTrue(queries.canCreateCreditNote(7L))
    }

    // The stale read is a persistence-session effect a mock cannot reproduce; pin the remedy instead:
    // every entity-loading gate must run in its own transaction (= own session).
    @Test
    fun `entity-loading reads are transactional so they never reuse the request session cache`() {
        listOf("findB2bComplianceFindings", "findCompletenessChecks", "canCreateCreditNote", "generationBlockReason").forEach { name ->
            val method = InvoiceDetailQueries::class.java.declaredMethods.first { it.name == name }
            assertTrue(method.isAnnotationPresent(jakarta.transaction.Transactional::class.java), "$name must be @Transactional")
        }
    }

    @Test
    fun `canCreateCreditNote is false for an invoice InvoiceStornoService rejects`() {
        val invoice = Invoice().apply { id = 6L }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(6L) }.thenReturn(invoice) }
        val invoiceStornoService = mock<InvoiceStornoService> { on { canCreateCreditNote(invoice) }.thenReturn(false) }
        val queries = queries(invoiceLifecycleService = lifecycleService, invoiceStornoService = invoiceStornoService)

        assertFalse(queries.canCreateCreditNote(6L))
    }

    @Test
    fun `canCreateCreditNote is false without throwing when the invoice cannot be found`() {
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(999L) }.thenReturn(null) }
        val invoiceStornoService = mock<InvoiceStornoService>()
        val queries = queries(invoiceLifecycleService = lifecycleService, invoiceStornoService = invoiceStornoService)

        assertFalse(queries.canCreateCreditNote(999L))
        verify(invoiceStornoService, never()).canCreateCreditNote(any())
    }

    @Test
    fun `generationBlockReason returns null for a DRAFT invoice`() {
        val invoice = Invoice().apply { id = 10L }.also { it.forceStatusForTest(InvoiceStatus.DRAFT) }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(10L) }.thenReturn(invoice) }
        val jobRepository = mock<GenerationJobRepository>()
        val queries = queries(invoiceLifecycleService = lifecycleService, generationJobRepository = jobRepository)

        assertNull(queries.generationBlockReason(10L))
        verify(jobRepository, never()).hasCompletedIssuedJob(any())
    }

    @Test
    fun `generationBlockReason returns null for a SENT invoice with no completed job yet`() {
        val invoice = Invoice().apply { id = 11L }.also { it.forceStatusForTest(InvoiceStatus.SENT) }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(11L) }.thenReturn(invoice) }
        val jobRepository = mock<GenerationJobRepository> { on { hasCompletedIssuedJob(11L) }.thenReturn(false) }
        val queries = queries(invoiceLifecycleService = lifecycleService, generationJobRepository = jobRepository)

        assertNull(queries.generationBlockReason(11L))
    }

    @Test
    fun `generationBlockReason returns IMMUTABLE for a SENT invoice with a completed job`() {
        val invoice = Invoice().apply { id = 12L }.also { it.forceStatusForTest(InvoiceStatus.SENT) }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(12L) }.thenReturn(invoice) }
        val jobRepository = mock<GenerationJobRepository> { on { hasCompletedIssuedJob(12L) }.thenReturn(true) }
        val queries = queries(invoiceLifecycleService = lifecycleService, generationJobRepository = jobRepository)

        assertEquals(GenerationBlockReason.IMMUTABLE, queries.generationBlockReason(12L))
    }

    @Test
    fun `generationBlockReason returns IMMUTABLE for a CANCELLED invoice with a completed job`() {
        val invoice = Invoice().apply { id = 13L }.also { it.forceStatusForTest(InvoiceStatus.CANCELLED) }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(13L) }.thenReturn(invoice) }
        val jobRepository = mock<GenerationJobRepository> { on { hasCompletedIssuedJob(13L) }.thenReturn(true) }
        val queries = queries(invoiceLifecycleService = lifecycleService, generationJobRepository = jobRepository)

        assertEquals(GenerationBlockReason.IMMUTABLE, queries.generationBlockReason(13L))
    }

    @Test
    fun `generationBlockReason returns VOIDED for a voided invoice regardless of any completed job`() {
        val invoice = Invoice().apply { id = 14L }.also { it.forceStatusForTest(InvoiceStatus.VOIDED) }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(14L) }.thenReturn(invoice) }
        val jobRepository = mock<GenerationJobRepository> { on { hasCompletedIssuedJob(14L) }.thenReturn(true) }
        val queries = queries(invoiceLifecycleService = lifecycleService, generationJobRepository = jobRepository)

        assertEquals(GenerationBlockReason.VOIDED, queries.generationBlockReason(14L))
        // VOIDED is decided from the status alone -- the completed-job query must not even be
        // consulted for it, mirroring GenerationJobService.createJob's own guard order.
        verify(jobRepository, never()).hasCompletedIssuedJob(any())
    }

    @Test
    fun `generationBlockReason returns null when the invoice cannot be found`() {
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(999L) }.thenReturn(null) }
        val jobRepository = mock<GenerationJobRepository>()
        val queries = queries(invoiceLifecycleService = lifecycleService, generationJobRepository = jobRepository)

        assertNull(queries.generationBlockReason(999L))
        verify(jobRepository, never()).hasCompletedIssuedJob(any())
    }

    @Test
    fun `happy path - previewPartySnapshotRefresh maps the InvoiceLifecycleService preview field by field`() {
        val preview = PartySnapshotRefreshPreview(
            sellerChanges = listOf(PartySnapshotFieldDiff("Name", "Alt GmbH", "Neu GmbH")),
            buyerChanges = listOf(PartySnapshotFieldDiff("USt-IdNr.", null, "DE123456789")),
        )
        val lifecycleService = mock<InvoiceLifecycleService> { on { previewPartySnapshotRefresh(49L) }.thenReturn(preview) }
        val queries = queries(invoiceLifecycleService = lifecycleService)

        val diff = queries.previewPartySnapshotRefresh(49L)

        assertEquals(
            PartySnapshotDiffDto(
                sellerChanges = listOf(SnapshotFieldChangeDto("Name", "Alt GmbH", "Neu GmbH")),
                buyerChanges = listOf(SnapshotFieldChangeDto("USt-IdNr.", null, "DE123456789")),
            ),
            diff,
        )
        assertTrue(diff.hasChanges)
    }
}
