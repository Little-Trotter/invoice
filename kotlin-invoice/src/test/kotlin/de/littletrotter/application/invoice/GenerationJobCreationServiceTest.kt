package de.littletrotter.application.invoice

import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.GenerationJobCreated
import de.littletrotter.domain.exception.InvoiceImmutableGenerationBlockedException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoiceSendAlreadyInProgressException
import de.littletrotter.domain.exception.InvoiceVoidedGenerationBlockedException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Money
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.shared.receipt.ReceiptSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import jakarta.enterprise.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/** Unit tests for the extracted job-creation responsibility. */
class GenerationJobCreationServiceTest {

    private fun validInvoice(id: Long = 1L, status: InvoiceStatus = InvoiceStatus.DRAFT): Invoice {
        val sender = SenderProfile().apply {
            name = "Muster GmbH"
            email = Email("billing@muster.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban("DE89370400440532013000")
        }
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("KD-001")
            name = "Kunde AG"
            email = Email("kunde@kunde-ag.example")
            vatId = VatId("DE987654321")
            iban = Iban("DE02120300000000202051")
        }
        val item = InvoiceItem().apply {
            positionId = "1"; description = "Beratung"
            quantity = BigDecimal("1.00")
            unitPriceNet = Money(BigDecimal("100.00"))
            netAmount = Money(BigDecimal("100.00"))
            vatAmount = Money(BigDecimal("19.00"))
            grossAmount = Money(BigDecimal("119.00"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }
        }
        return Invoice().apply {
            this.id = id
            invoiceNumber = InvoiceNumber("RE-2025-01-${id.toString().padStart(5, '0')}")
            invoiceDate = LocalDate.now()
            forceStatusForTest(status)
            senderProfile = sender
            this.recipient = recipient
            totals.netAmount = Money(BigDecimal("100.00"))
            totals.vatAmount = Money(BigDecimal("19.00"))
            totals.grossAmount = Money(BigDecimal("119.00"))
            itemList.asMutableForTest().add(item.also { it.invoice = this })
        }
    }

    private val generationJobRepository: GenerationJobRepository = mock()
    private val invoiceRepository: InvoiceRepository = mock()
    private val domainEventBus: Event<DomainEvent> = mock()
    private val time = de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z"))
    private val receiptBudgetService: ReceiptBudgetService = mock()
    private val ops = GenerationJobCreationService(generationJobRepository, invoiceRepository, domainEventBus, time, receiptBudgetService)

    init {
        // Every test that does not care about the receipt budget gets an empty, within-budget
        // invoice by default — only the budget-specific tests below override this per invoice id.
        whenever(receiptBudgetService.invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
    }

    @Test
    fun `createJob persists a PENDING job and fires GenerationJobCreated`() {
        val invoice = validInvoice()
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(generationJobRepository.save(org.mockito.kotlin.any())).thenAnswer { it.arguments[0].also { j -> (j as GenerationJob).id = 500L } }

        val job = ops.createJob(invoice, "PDF", DocumentJobLane.BULK)

        assertEquals(GenerationJobStatus.PENDING, job.status)
        assertEquals(invoice, job.invoice)
        verify(domainEventBus).fire(org.mockito.kotlin.any())
    }

    @Test
    fun `createJob stores the given lane on the job and announces it on the created event`() {
        val invoice = validInvoice()
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(generationJobRepository.save(org.mockito.kotlin.any())).thenAnswer { it.arguments[0].also { j -> (j as GenerationJob).id = 501L } }

        val interactive = ops.createJob(invoice, "PRINT_JOB", DocumentJobLane.INTERACTIVE)
        val bulk = ops.createJob(invoice, "PDF", DocumentJobLane.BULK)

        assertEquals(DocumentJobLane.INTERACTIVE, interactive.lane)
        assertEquals(DocumentJobLane.BULK, bulk.lane)
        verify(domainEventBus).fire(GenerationJobCreated(501L, DocumentJobLane.INTERACTIVE))
        verify(domainEventBus).fire(GenerationJobCreated(501L, DocumentJobLane.BULK))
    }

    @Test
    fun `createJob rejects a second concurrent SEND job`() {
        val invoice = validInvoice()
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(generationJobRepository.hasNonTerminalTriggerJob(1L, GenerationJobTrigger.SEND)).thenReturn(true)

        assertThrows<InvoiceSendAlreadyInProgressException> {
            ops.createJob(invoice, "PDF", DocumentJobLane.BULK, trigger = GenerationJobTrigger.SEND)
        }
    }

    @Test
    fun `createJob rejects a second PRINT_JOB of the same variant while one is running`() {
        val invoice = validInvoice()
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(generationJobRepository.hasNonTerminalJob(1L, "PRINT_JOB", invoice.documentEngine, false)).thenReturn(true)

        org.junit.jupiter.api.assertThrows<de.littletrotter.domain.exception.InvoiceGenerationAlreadyRunningException> {
            ops.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK)
        }
        verify(generationJobRepository, org.mockito.kotlin.never()).save(org.mockito.kotlin.any())
    }

    @Test
    fun `createJob allows a PRINT_JOB of a different variant while another runs`() {
        val invoice = validInvoice()
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(generationJobRepository.hasNonTerminalJob(1L, "PRINT_JOB", invoice.documentEngine, false)).thenReturn(true)
        whenever(generationJobRepository.save(org.mockito.kotlin.any())).thenAnswer { it.arguments[0] }

        val job = ops.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK, llmOptimize = true)

        assertEquals(true, job.llmOptimize)
    }

    @Test
    fun `createJob applies the print idempotency guard only to PRINT_JOB`() {
        val invoice = validInvoice()
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(generationJobRepository.hasNonTerminalJob(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(true)
        whenever(generationJobRepository.save(org.mockito.kotlin.any())).thenAnswer { it.arguments[0] }

        val job = ops.createJob(invoice, "PDF", DocumentJobLane.BULK)

        assertEquals(GenerationJobStatus.PENDING, job.status)
    }

    private fun psql(state: String, constraint: String?): org.postgresql.util.PSQLException {
        // Wire format of a backend ErrorResponse: one-char field tag directly followed by the value, NUL-terminated.
        val detail = org.postgresql.util.ServerErrorMessage(
            "SERROR\u0000C$state\u0000Mduplicate key value violates unique constraint" +
                (constraint?.let { "\u0000n$it" } ?: "") + "\u0000",
        )
        return org.postgresql.util.PSQLException(detail)
    }

    private fun saveFailsWith(cause: Throwable): Throwable {
        val invoice = validInvoice()
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        val wrapped = jakarta.persistence.PersistenceException("could not execute statement", cause)
        whenever(generationJobRepository.save(org.mockito.kotlin.any())).thenThrow(wrapped)
        return assertThrows<Throwable> { ops.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK) }
    }

    @Test
    fun `createJob maps SQLState 23505 on the active print job index to the already-running rejection`() {
        val thrown = saveFailsWith(psql("23505", GenerationJobCreationService.ACTIVE_PRINT_JOB_INDEX))

        assertEquals(de.littletrotter.domain.exception.InvoiceGenerationAlreadyRunningException::class, thrown::class)
    }

    @Test
    fun `createJob rethrows a 23505 violation of another constraint`() {
        val thrown = saveFailsWith(psql("23505", "uq_some_other_index"))

        assertEquals(jakarta.persistence.PersistenceException::class, thrown::class)
    }

    @Test
    fun `createJob rethrows another SQLState even when it names the active print job index`() {
        val thrown = saveFailsWith(psql("23503", GenerationJobCreationService.ACTIVE_PRINT_JOB_INDEX))

        assertEquals(jakarta.persistence.PersistenceException::class, thrown::class)
    }

    @Test
    fun `a plain SQLException without server error message falls back to the message match`() {
        val sql = java.sql.SQLException(
            "duplicate key value violates unique constraint \"uq_generation_job_active_print\"", "23505",
        )

        assertEquals(true, GenerationJobCreationService.isActivePrintJobViolation(RuntimeException(sql)))
        assertEquals(false, GenerationJobCreationService.isActivePrintJobViolation(RuntimeException("uq_generation_job_active_print")))
    }

    @Test
    fun `createJob rethrows an unrelated persistence failure unchanged`() {
        val invoice = validInvoice()
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        val other = jakarta.persistence.PersistenceException("some other constraint")
        whenever(generationJobRepository.save(org.mockito.kotlin.any())).thenThrow(other)

        val thrown = org.junit.jupiter.api.assertThrows<jakarta.persistence.PersistenceException> { ops.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK) }

        assertEquals(other, thrown)
    }

    // The SEND guard blocks only another SEND (document-checksum-mismatch spec, overwrite windows).
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(
        "DRAFT,PRINT_JOB",
        "IN_REVIEW,PRINT_JOB",
        "IN_REVIEW,XML_EXPORT",
    )
    fun `a non-SEND job is not blocked while a SEND job of the invoice is non-terminal`(
        status: InvoiceStatus,
        jobType: String,
    ) {
        val invoice = validInvoice(status = status)
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(generationJobRepository.hasNonTerminalTriggerJob(1L, GenerationJobTrigger.SEND)).thenReturn(true)
        whenever(generationJobRepository.save(any())).thenAnswer { it.arguments[0] }

        assertEquals(GenerationJobStatus.PENDING, ops.createJob(invoice, jobType, DocumentJobLane.BULK).status)
    }

    @Test
    fun `createJob rejects a voided invoice`() {
        val invoice = validInvoice(status = InvoiceStatus.VOIDED)
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows<InvoiceVoidedGenerationBlockedException> { ops.createJob(invoice, "PDF", DocumentJobLane.BULK) }
    }

    @ParameterizedTest
    @EnumSource(names = ["MANUAL", "SEND", "MAINTENANCE", "REGENERATION"])
    fun `a voided invoice stays blocked for every trigger except a reproduction`(trigger: GenerationJobTrigger) {
        val invoice = validInvoice(status = InvoiceStatus.VOIDED)
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows<InvoiceVoidedGenerationBlockedException> { ops.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK, trigger = trigger) }
        verify(generationJobRepository, never()).save(any())
    }

    @Test
    fun `a voided invoice accepts a reproduction job and keeps its frozen state`() {
        val invoice = validInvoice(status = InvoiceStatus.VOIDED)
        val grossBefore = invoice.totals.grossAmount
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(generationJobRepository.save(any())).thenAnswer { it.arguments[0] }

        val job = ops.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK, trigger = GenerationJobTrigger.REPRODUCTION)

        assertEquals(GenerationJobTrigger.REPRODUCTION, job.trigger)
        assertTrue(job.issued)
        assertEquals(InvoiceStatus.VOIDED, invoice.status)
        assertEquals(grossBefore, invoice.totals.grossAmount)
    }

    @Test
    fun `a voided invoice with a running send is still rejected before the voided guard is reached`() {
        val invoice = validInvoice(status = InvoiceStatus.VOIDED)
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(generationJobRepository.hasNonTerminalTriggerJob(1L, GenerationJobTrigger.SEND)).thenReturn(true)

        assertThrows<de.littletrotter.domain.exception.InvoiceSendAlreadyInProgressException> {
            ops.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK, trigger = GenerationJobTrigger.SEND)
        }
    }

    @ParameterizedTest
    @EnumSource(names = ["REGENERATION", "REPRODUCTION"])
    fun `a cancelled invoice with a completed issued document accepts a recovery job`(trigger: GenerationJobTrigger) {
        val invoice = validInvoice(status = InvoiceStatus.CANCELLED)
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(generationJobRepository.hasCompletedIssuedJob(1L)).thenReturn(true)
        whenever(generationJobRepository.save(any())).thenAnswer { it.arguments[0].also { j -> (j as GenerationJob).id = 502L } }

        val job = ops.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK, trigger = trigger)

        assertEquals(GenerationJobStatus.PENDING, job.status)
        assertEquals(trigger, job.trigger)
        assertTrue(job.issued)
    }

    @Test
    fun `a cancelled invoice with a completed issued document rejects a manual job`() {
        val invoice = validInvoice(status = InvoiceStatus.CANCELLED)
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(generationJobRepository.hasCompletedIssuedJob(1L)).thenReturn(true)

        assertThrows<InvoiceImmutableGenerationBlockedException> { ops.createJob(invoice, "PRINT_JOB", DocumentJobLane.BULK) }
    }

    @Test
    fun `createJob rejects a second job for an already completed issued invoice`() {
        val invoice = validInvoice(status = InvoiceStatus.SENT)
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(generationJobRepository.hasCompletedIssuedJob(1L)).thenReturn(true)

        assertThrows<InvoiceImmutableGenerationBlockedException> { ops.createJob(invoice, "PDF", DocumentJobLane.BULK) }
    }

    @Test
    fun `createJob by id throws when the invoice cannot be found`() {
        whenever(invoiceRepository.findByIdWithItems(404L)).thenReturn(null)

        assertThrows<InvoiceNotFoundException> { ops.createJob(404L, "PDF", DocumentJobLane.BULK) }
    }

    /** The hard stop this class now owns: linking a receipt no longer refuses an oversized
     *  document (that check became advisory), so job creation is the one place left that must
     *  still refuse before an over-budget document is actually produced. */
    @Test
    fun `createJob refuses when the invoice's current receipt set exceeds the document size budget`() {
        val invoice = validInvoice()
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        // Five legacy-sized receipts (5 MiB each, pre-dating the 1 MiB upload cap): well over the
        // 20 MiB document limit while the count (5) stays far under the 19-receipt limit — only
        // the size guard can catch this, same fixture shape as ReceiptServiceInvoiceLevelTest.
        val oversized = (1L..5L).map { id -> ReceiptSize("legacy-$id.pdf", DocumentSize.ofMebiBytes(5L)) }
        whenever(receiptBudgetService.invoiceDocumentBudget(1L)).thenReturn(InvoiceDocumentBudget.calculate(oversized))

        val ex = assertThrows<InvoiceDocumentSizeExceededException> { ops.createJob(invoice, "PDF", DocumentJobLane.BULK) }

        assertEquals(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE, ex.limit)
        verify(generationJobRepository, never()).save(any())
    }

    @Test
    fun `createJob persists as usual when the invoice's receipt set stays within the document size budget`() {
        val invoice = validInvoice()
        whenever(invoiceRepository.findByIdWithItems(1L)).thenReturn(invoice)
        val withinBudget = listOf(ReceiptSize("receipt-1.pdf", DocumentSize.ofMebiBytes(1L)))
        whenever(receiptBudgetService.invoiceDocumentBudget(1L)).thenReturn(InvoiceDocumentBudget.calculate(withinBudget))
        whenever(generationJobRepository.save(any())).thenAnswer { it.arguments[0].also { j -> (j as GenerationJob).id = 501L } }

        val job = ops.createJob(invoice, "PDF", DocumentJobLane.BULK)

        assertEquals(GenerationJobStatus.PENDING, job.status)
        verify(generationJobRepository).save(any())
    }
}
