package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobPublishingService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.GenerationJobRecoveryService
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceCancellationService
import de.littletrotter.domain.exception.InvoiceImmutableGenerationBlockedException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.xpdf.testsupport.TypstContainerRenderer
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * The genuine E2E-level gaps in the single-generation gate: the
 * decision-table matrix and the retry-after-failure case are already exhaustively covered by
 * [de.littletrotter.application.GenerationJobServiceTest] (mocked repositories,
 * kotlin-invoice), and the ISSUED-invoice second-job-rejected + byte-identical-archive case is
 * already covered end to end by [ArchivePackageE2EIT] (`second job attempt is rejected...`). Three
 * scenarios from the single-generation decision table had NO coverage at this level (real Postgres, the actual
 * [GenerationJobService] wiring) before this file: a CANCELLED **original** whose completed job
 * predates its own cancellation still locks a second attempt; a cancellation document (type code 384) locks
 * its own second attempt the same way; and the retry-after-failure path — the whole reason
 * `hasCompletedJob` is keyed off COMPLETED rather than "any job exists" — actually reaches a
 * COMPLETED job and only then locks, against a real database rather than an in-memory test double.
 *
 * A fourth scenario has its own test below: a review-time draft job (completed
 * while the invoice is still DRAFT/IN_REVIEW) must never lock generation once the invoice is later
 * sent — only the first COMPLETED job with [de.littletrotter.domain.invoice.GenerationJob.issued]
 * `true` does ([de.littletrotter.domain.repository.GenerationJobRepository.hasCompletedIssuedJob],
 * not "any completed job").
 *
 * CONTAINER-DEPENDENT (real Postgres via [PostgresTestResource]) — per this task's hard
 * constraints, written and compile-checked but deliberately NOT run in this environment (no
 * Docker). Deferred to a live-DB verification pass, same as every other kotlin-e2e-tests IT.
 *
 * Follows [InvoiceLifecycleWatermarkE2EIT]'s and [ArchivePackageE2EIT]'s established fixture idiom
 * (in-memory [DocumentStore], the real [PdfExportServiceImpl] driven directly, KoSIT stubbed
 * VALID) — receipts are deliberately omitted here, this suite has nothing new to say about the
 * receipt annex, only about the generation lock itself.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceGenerationLockE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var cancellationService: InvoiceCancellationService

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var generationJobRecoveryService: GenerationJobRecoveryService

    @Inject
    lateinit var generationJobRepository: GenerationJobRepository

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    private class InMemoryDocumentStore : DocumentStore {
        val objects = mutableMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { objects[objectKey] = data }
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not stored: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }

    private fun mapToDto(invoiceId: Long): InvoiceDto {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        return de.littletrotter.application.invoice.InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)
    }

    /** Same idiom as [InvoiceLifecycleWatermarkE2EIT.exportPdf] — drives the real rendering
     *  pipeline and returns the stored PDF bytes for [store]'s deterministic export key. */
    /** Exports [dto] and returns the PDF key the export itself reported (per-attempt) with its bytes. */
    private fun exportPdf(dto: InvoiceDto, store: InMemoryDocumentStore, jobId: String): Pair<String, ByteArray> {
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(null),
            reportRenderer = TypstContainerRenderer(),
            documentStore = store,
            kositValidator = object : KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = KositValidationResult(status = KositOutcome.VALID)
            },
        )
        val objectKey = service.performExport(dto, jobId).objectKey
        return objectKey to store.get(objectKey)
    }

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun buildParties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
        val sender = aSenderProfile {
            phone = "+49 30 1234567"
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    private fun sentInvoice(tag: String): Long {
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        return invoiceId
    }

    private fun statusOf(invoiceId: Long): InvoiceStatus =
        QuarkusTransaction.requiringNew().call<InvoiceStatus> { em.find(Invoice::class.java, invoiceId).status }

    /** PENDING -> DELEGATED -> COMPLETED, the same two-step sequence a real worker delegation
     *  would leave a job in (same idiom as [ArchivePackageE2EIT.createAndCompleteJob]). */
    private fun createAndCompleteJob(invoiceId: Long): GenerationJobStatus {
        val job = QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        val delegated = QuarkusTransaction.requiringNew().call { generationJobPublishingService.markDelegated(job) }
        val completed = QuarkusTransaction.requiringNew().call { generationJobResultHandler.markCompleted(delegated) }
        return completed.status
    }

    // ─── Gap 1 — a CANCELLED original whose completed job predates its own cancellation ────────────

    @Test
    fun `RS-4 - a cancelled original with a pre-cancellation completed job rejects a second job, archived PDF stays byte-identical`() {
        val tag = "GL1"
        val invoiceId = sentInvoice(tag)
        val store = InMemoryDocumentStore()

        // The original's real, only generation job runs and completes while it is still ISSUED --
        // "this is how the final document gets produced" (the single-generation decision table).
        val dto = QuarkusTransaction.requiringNew().call { mapToDto(invoiceId) }
        val (objectKey, firstBytes) = exportPdf(dto, store, "$tag-first")
        assertEquals(GenerationJobStatus.COMPLETED, createAndCompleteJob(invoiceId))

        // Cancellation: the original transitions ISSUED -> CANCELLED. It already carries a completed job,
        // so the single-generation gate must still reject any further createJob for it -- CANCELLED does not
        // reset the lock, it only ever gates the FIRST generation of a document born/moved into it.
        QuarkusTransaction.requiringNew().call { cancellationService.createCreditNote(invoiceId, "E2E RS-4 Storno") }
        assertEquals(InvoiceStatus.CANCELLED, statusOf(invoiceId), "the original moves ISSUED -> CANCELLED")

        val ex = assertThrows(InvoiceImmutableGenerationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().run { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        }
        val invoiceNumber = QuarkusTransaction.requiringNew().call<String> { em.find(Invoice::class.java, invoiceId).invoiceNumber.value }
        assertTrue(ex.message!!.contains(invoiceNumber), "the rejection message names the invoice")

        // No second worker run ever happened -- the archived PDF is unchanged, brand mark only.
        assertArrayEquals(firstBytes, store.get(objectKey), "a rejected second job on the cancelled original must never overwrite its archived PDF")
    }

    // ─── Gap 2 — a cancellation document (384) locks its own second attempt ────────────────────────

    @Test
    fun `RS-4 - a cancellation document's completed job rejects a second job, archived PDF stays byte-identical`() {
        val tag = "GL2"
        val originalId = sentInvoice(tag)
        val creditNoteId = QuarkusTransaction.requiringNew().call { cancellationService.createCreditNote(originalId, "E2E RS-4 Storno") }.id!!
        assertEquals(InvoiceStatus.CANCELLED, statusOf(creditNoteId), "the storno document is born CANCELLED")

        val store = InMemoryDocumentStore()
        val dto = QuarkusTransaction.requiringNew().call { mapToDto(creditNoteId) }
        val (objectKey, firstBytes) = exportPdf(dto, store, "$tag-storno-first")

        assertEquals(GenerationJobStatus.COMPLETED, createAndCompleteJob(creditNoteId), "the storno document's first generation job completes")

        assertThrows(InvoiceImmutableGenerationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().run { generationJobCreationService.createJob(creditNoteId, "PRINT_JOB", DocumentJobLane.BULK) }
        }

        assertArrayEquals(firstBytes, store.get(objectKey), "a rejected second job on the storno document must never overwrite its archived PDF")
    }

    // ─── Gap 3 — retry-after-failure through the real database, not GenerationJobServiceTest's
    //     mocked repositories ──────────────────────────────────────────────────────────────────

    @Test
    fun `RS-4 - a failed job does not lock an ISSUED invoice against real Postgres -- a retry completes, then a third job is rejected`() {
        val tag = "GL3"
        val invoiceId = sentInvoice(tag)

        val first = QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        QuarkusTransaction.requiringNew().run { generationJobRecoveryService.markFailed(first, message = "E2E simulated worker failure") }

        // The failed attempt must never lock out a retry -- this is the whole reason the gate is
        // keyed off hasCompletedIssuedJob (COMPLETED and issued=true only), not "any job exists
        // for this invoice".
        val second = QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        assertNotNull(second.id, "a failed first attempt must never lock out a retry, even against a real database")

        // Drive the SAME retry job through to completion -- calling createAndCompleteJob here
        // would create a THIRD job, not complete this one.
        val delegated = QuarkusTransaction.requiringNew().call { generationJobPublishingService.markDelegated(second) }
        val completed = QuarkusTransaction.requiringNew().call { generationJobResultHandler.markCompleted(delegated) }
        assertEquals(GenerationJobStatus.COMPLETED, completed.status, "the retry reaches COMPLETED")

        assertThrows(InvoiceImmutableGenerationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().run { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        }

        val jobs = QuarkusTransaction.requiringNew().call { generationJobRepository.findByInvoiceId(invoiceId) }
        assertEquals(2, jobs.size, "only the two real attempts (failed + completed retry) persist -- the rejected third never does")
    }

    // ─── Gap 4 — a review-time draft job must not lock a later-sent invoice ─────

    @Test
    fun `RS-4 - a completed review-time draft job does not lock the invoice once sent -- only the first issued job locks`() {
        val tag = "GL4"
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }

        // Review-time draft job: completes while the invoice is still IN_REVIEW, i.e. NOT issued.
        assertEquals(GenerationJobStatus.COMPLETED, createAndCompleteJob(invoiceId), "the review-time draft job completes")
        val draftJob = QuarkusTransaction.requiringNew().call { generationJobRepository.findByInvoiceId(invoiceId) }.single()
        assertEquals(false, draftJob.issued, "a job created while the invoice was still IN_REVIEW must be issued=false")

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        assertEquals(InvoiceStatus.ISSUED, statusOf(invoiceId))

        // The completed draft job must NOT lock generation now that the invoice is ISSUED.
        val issuedJob = QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        assertNotNull(issuedJob.id, "a completed review-time draft job must never lock the invoice once it is sent")

        // Drive this SAME job through to completion -- calling createAndCompleteJob here would
        // create a THIRD job, not complete this one.
        val delegated = QuarkusTransaction.requiringNew().call { generationJobPublishingService.markDelegated(issuedJob) }
        val completed = QuarkusTransaction.requiringNew().call { generationJobResultHandler.markCompleted(delegated) }
        assertEquals(GenerationJobStatus.COMPLETED, completed.status)
        assertEquals(true, completed.issued, "a job created while the invoice is already ISSUED must be issued=true")

        // Now the first ISSUED job is completed -- a further attempt must be rejected.
        assertThrows(InvoiceImmutableGenerationBlockedException::class.java) {
            QuarkusTransaction.requiringNew().run { generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        }
        val jobs = QuarkusTransaction.requiringNew().call { generationJobRepository.findByInvoiceId(invoiceId) }
        assertEquals(2, jobs.size, "the review-time draft job plus the one issued job -- the rejected third never persists")
    }
}
