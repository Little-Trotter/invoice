package de.littletrotter.application.invoice

import de.littletrotter.application.dto.B2bFindingDto
import de.littletrotter.application.dto.CompletenessCheckDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ModelReport
import de.littletrotter.application.dto.PartySnapshotDiffDto
import de.littletrotter.application.dto.SnapshotFieldChangeDto
import de.littletrotter.application.dto.CancellationRelationDto
import de.littletrotter.application.dto.CancellationRole
import de.littletrotter.application.invoice.port.outbound.InvoiceReadRepository
import de.littletrotter.domain.compliance.ComplianceFinding
import de.littletrotter.domain.compliance.ComplianceSeverity
import de.littletrotter.domain.compliance.CoreCompletenessRules
import de.littletrotter.domain.compliance.FindingTarget
import de.littletrotter.domain.exception.GenerationBlockReason
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.domain.invoice.PartySnapshotFieldDiff
import de.littletrotter.domain.invoice.PartySnapshotRefreshPreview
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.application.invoice.port.outbound.InvoiceLlmAnalysisReadRepository
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.domain.repository.InvoiceRepository
import  de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceLlmAnalysis
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
        invoiceCancellationService: InvoiceCancellationService = mock(),
        generationJobRepository: GenerationJobRepository = mock(),
        llmAnalysisReadRepository: InvoiceLlmAnalysisReadRepository = mock(),
        invoiceRepository: InvoiceRepository = mock(),
        invoiceLookup: InvoiceLookupRepository = mock(),
        b2bProfileRuleSet: B2bProfileRuleSet? = null,
    ) = InvoiceDetailQueries(invoiceReadRepository, invoiceLifecycleService, invoiceCancellationService, generationJobRepository, llmAnalysisReadRepository, invoiceRepository, invoiceLookup, b2bProfileRuleSet)

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
        val invoice = anInvoice(id = 2L)
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
        val invoice = anInvoice(id = 3L)
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(3L) }.thenReturn(invoice) }
        val queries = queries(invoiceLifecycleService = lifecycleService)

        val result = queries.findCompletenessChecks(3L)

        assertEquals(CoreCompletenessRules.checks(invoice).map { CompletenessCheckDto(it.ruleId, it.isSatisfied) }, result)
    }

    @Test
    fun `happy path - findLlmAnalyses maps every analysis for an existing invoiceId`() {
        val analysis = anInvoiceLlmAnalysis {
            modelName = "gpt-test"
            status = LlmAnalysisStatus.COMPLETED
        }
        val llmAnalysisReadRepository = mock<InvoiceLlmAnalysisReadRepository> { on { findReports(7L) }.thenReturn(listOf(ModelReport.from(analysis))) }
        val queries = queries(llmAnalysisReadRepository = llmAnalysisReadRepository)

        val result = queries.findLlmAnalyses(7L)

        assertEquals(listOf(ModelReport.from(analysis)), result)
        verify(llmAnalysisReadRepository).findReports(7L)
    }

    @Test
    fun `boundary - findLlmAnalyses is empty for a null invoiceId without touching the repository`() {
        val llmAnalysisReadRepository = mock<InvoiceLlmAnalysisReadRepository>()
        val queries = queries(llmAnalysisReadRepository = llmAnalysisReadRepository)

        assertTrue(queries.findLlmAnalyses(null).isEmpty())
        verify(llmAnalysisReadRepository, never()).findReports(any())
    }

    @Test
    fun `canCreateCreditNote delegates to InvoiceCancellationService for a found invoice`() {
        val invoice = anInvoice(id = 5L)
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(5L) }.thenReturn(invoice) }
        val invoiceCancellationService = mock<InvoiceCancellationService> { on { canCreateCreditNote(invoice) }.thenReturn(true) }
        val queries = queries(invoiceLifecycleService = lifecycleService, invoiceCancellationService = invoiceCancellationService)

        assertTrue(queries.canCreateCreditNote(5L))
        verify(invoiceCancellationService).canCreateCreditNote(invoice)
    }

    @Test
    fun `canCreateCreditNote reflects a status change between two reads of the same request`() {
        val review = anInvoice(id = 7L).also { it.forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val sent = anInvoice(id = 7L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(7L) }.thenReturn(review, sent) }
        val invoiceCancellationService = mock<InvoiceCancellationService> {
            on { canCreateCreditNote(review) }.thenReturn(false)
            on { canCreateCreditNote(sent) }.thenReturn(true)
        }
        val queries = queries(invoiceLifecycleService = lifecycleService, invoiceCancellationService = invoiceCancellationService)

        assertFalse(queries.canCreateCreditNote(7L))
        assertTrue(queries.canCreateCreditNote(7L))
    }

    // The stale read is a persistence-session effect a mock cannot reproduce; pin the remedy instead:
    // every entity-loading gate must run in its own transaction (= own session).
    @Test
    fun `entity-loading reads are transactional so they never reuse the request session cache`() {
        listOf("findB2bComplianceFindings", "findCompletenessChecks", "canCreateCreditNote", "findCancellationRelation", "generationBlockReason").forEach { name ->
            val method = InvoiceDetailQueries::class.java.declaredMethods.first { it.name == name }
            assertTrue(method.isAnnotationPresent(jakarta.transaction.Transactional::class.java), "$name must be @Transactional")
        }
    }

    @Test
    fun `canCreateCreditNote is false for an invoice InvoiceCancellationService rejects`() {
        val invoice = anInvoice(id = 6L)
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(6L) }.thenReturn(invoice) }
        val invoiceCancellationService = mock<InvoiceCancellationService> { on { canCreateCreditNote(invoice) }.thenReturn(false) }
        val queries = queries(invoiceLifecycleService = lifecycleService, invoiceCancellationService = invoiceCancellationService)

        assertFalse(queries.canCreateCreditNote(6L))
    }

    @Test
    fun `canCreateCreditNote is false without throwing when the invoice cannot be found`() {
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(999L) }.thenReturn(null) }
        val invoiceCancellationService = mock<InvoiceCancellationService>()
        val queries = queries(invoiceLifecycleService = lifecycleService, invoiceCancellationService = invoiceCancellationService)

        assertFalse(queries.canCreateCreditNote(999L))
        verify(invoiceCancellationService, never()).canCreateCreditNote(any())
    }

    @Test
    fun `generationBlockReason returns null for a DRAFT invoice`() {
        val invoice = anInvoice(id = 10L).also { it.forceStatusForTest(InvoiceStatus.DRAFT) }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(10L) }.thenReturn(invoice) }
        val jobRepository = mock<GenerationJobRepository>()
        val queries = queries(invoiceLifecycleService = lifecycleService, generationJobRepository = jobRepository)

        assertNull(queries.generationBlockReason(10L))
        verify(jobRepository, never()).hasCompletedIssuedJob(any())
    }

    @Test
    fun `generationBlockReason returns null for an ISSUED invoice with no completed job yet`() {
        val invoice = anInvoice(id = 11L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(11L) }.thenReturn(invoice) }
        val jobRepository = mock<GenerationJobRepository> { on { hasCompletedIssuedJob(11L) }.thenReturn(false) }
        val queries = queries(invoiceLifecycleService = lifecycleService, generationJobRepository = jobRepository)

        assertNull(queries.generationBlockReason(11L))
    }

    @Test
    fun `generationBlockReason returns IMMUTABLE for an ISSUED invoice with a completed job`() {
        val invoice = anInvoice(id = 12L).also { it.forceStatusForTest(InvoiceStatus.ISSUED) }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(12L) }.thenReturn(invoice) }
        val jobRepository = mock<GenerationJobRepository> { on { hasCompletedIssuedJob(12L) }.thenReturn(true) }
        val queries = queries(invoiceLifecycleService = lifecycleService, generationJobRepository = jobRepository)

        assertEquals(GenerationBlockReason.IMMUTABLE, queries.generationBlockReason(12L))
    }

    @Test
    fun `generationBlockReason returns IMMUTABLE for a CANCELLED invoice with a completed job`() {
        val invoice = anInvoice(id = 13L).also { it.forceStatusForTest(InvoiceStatus.CANCELLED) }
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(13L) }.thenReturn(invoice) }
        val jobRepository = mock<GenerationJobRepository> { on { hasCompletedIssuedJob(13L) }.thenReturn(true) }
        val queries = queries(invoiceLifecycleService = lifecycleService, generationJobRepository = jobRepository)

        assertEquals(GenerationBlockReason.IMMUTABLE, queries.generationBlockReason(13L))
    }

    @Test
    fun `generationBlockReason returns VOIDED for a voided invoice regardless of any completed job`() {
        val invoice = anInvoice(id = 14L).also { it.forceStatusForTest(InvoiceStatus.VOIDED) }
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

    @Test
    fun `findCancellationRelation of a cancelled original returns its credit note`() {
        val original = anInvoice(id = 10L, invoiceNumber = InvoiceNumber("INV-2026-03-00042"))
        val creditNote = anInvoice(id = 11L, invoiceNumber = InvoiceNumber("INV-2026-03-00043"), typeCode = InvoiceTypeCode.CORRECTED_INVOICE, originalInvoiceNumber = "INV-2026-03-00042")
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(10L) }.thenReturn(original) }
        val lookup = mock<InvoiceLookupRepository> { on { findByOriginalInvoiceNumber("INV-2026-03-00042") }.thenReturn(listOf(creditNote)) }

        assertEquals(
            CancellationRelationDto(11L, "INV-2026-03-00043", CancellationRole.CREDIT_NOTE),
            queries(invoiceLifecycleService = lifecycleService, invoiceLookup = lookup).findCancellationRelation(10L),
        )
    }

    @Test
    fun `findCancellationRelation of a credit note returns its original`() {
        val original = anInvoice(id = 10L, invoiceNumber = InvoiceNumber("INV-2026-03-00042"))
        val creditNote = anInvoice(id = 11L, invoiceNumber = InvoiceNumber("INV-2026-03-00043"), typeCode = InvoiceTypeCode.CORRECTED_INVOICE, originalInvoiceNumber = "INV-2026-03-00042")
        val lifecycleService = mock<InvoiceLifecycleService> { on { findById(11L) }.thenReturn(creditNote) }
        val repo = mock<InvoiceRepository> { on { findByInvoiceNumber("INV-2026-03-00042") }.thenReturn(original) }

        assertEquals(
            CancellationRelationDto(10L, "INV-2026-03-00042", CancellationRole.ORIGINAL),
            queries(invoiceLifecycleService = lifecycleService, invoiceRepository = repo).findCancellationRelation(11L),
        )
    }

    @Test
    fun `findCancellationRelation is null when nothing resolves`() {
        val plain = anInvoice(id = 12L, invoiceNumber = InvoiceNumber("INV-2026-03-00044"))
        val lifecycleService = mock<InvoiceLifecycleService> {
            on { findById(12L) }.thenReturn(plain)
            on { findById(99L) }.thenReturn(null)
        }
        val lookup = mock<InvoiceLookupRepository> { on { findByOriginalInvoiceNumber("INV-2026-03-00044") }.thenReturn(emptyList()) }
        val q = queries(invoiceLifecycleService = lifecycleService, invoiceLookup = lookup)

        assertNull(q.findCancellationRelation(12L))
        assertNull(q.findCancellationRelation(99L))
    }
}
