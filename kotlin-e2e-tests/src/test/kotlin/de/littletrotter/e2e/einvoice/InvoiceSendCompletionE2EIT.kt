package de.littletrotter.e2e.einvoice

import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobPublishingService
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceSendService
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.domain.exception.InvoiceImmutableGenerationBlockedException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJobTrigger
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.Money
import de.littletrotter.xpdf.impl.EmbeddedKositValidator
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * The "every feature package gets E2E happy + border" proof for the send flow (E2E layer: cross-module, real pipeline, KoSIT embedded), driven end to end through the real application services (no UI/NATS driver, same "real services, no message queue" shape as [InvoiceDateSendGateE2EIT] — the `LinkageError` that booting kotlin-invoice's full CDI container alongside a live NATS broker in this module would trigger rules that out, so the worker hop is stood in for directly with the real Mustang generator + the real embedded KoSIT validator, exactly the artifact a real `kotlin-xpdf` worker would have produced and handed back on `document-job-results`).
 *
 * Fixture recipe copied from [InvoiceDateSendGateE2EIT.buildDraftInvoice] (a proven, real KoSIT-green invoice) rather than reinvented — see that class's own KDoc for why every BT field it sets matters.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceSendCompletionE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var invoiceSendService: InvoiceSendService

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobQueryService: GenerationJobQueryService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    @Inject
    lateinit var b2bFieldValueService: B2bFieldValueService

    companion object {
        private lateinit var kosit: EmbeddedKositValidator

        @JvmStatic
        @BeforeAll
        fun initKosit() {
            kosit = EmbeddedKositValidator().apply { init() }
        }
    }

    private val mustang = XmlExportServiceImpl(null)

    private fun mustangXml(dto: InvoiceDto): String = String(mustang.generate(dto), Charsets.UTF_8)

    /** Real KoSIT report for [invoiceId]'s current state, the same shape a real `kotlin-xpdf`
     *  worker would hand back on `document-job-results`. */
    private fun realKositReport(invoiceId: Long): String {
        val dto = QuarkusTransaction.requiringNew().call<InvoiceDto> {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(invoice)
        }
        val result = kosit.validate(mustangXml(dto), timeoutMs = 30_000)
        return KositReportPayload.encode(KositValidationResult(status = result.status, details = result.details))
    }

    // ─── Fixture — same shape as InvoiceDateSendGateE2EIT.buildDraftInvoice ─────────────────────

    private fun buildSendableInvoice(tag: String, withBuyerAddress: Boolean): Long {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "SendCompletion Sender $tag GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
                phone = "+49 30 1234567"
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-100")
                name = "SendCompletion Empfänger $tag AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
                // BR-DE-15/PEPPOL-EN16931-R008 — a standing Leitweg-ID so
                // ensureBuyerReferenceForFinalization never blocks this suite's own scenario.
                routingId = "PO-$tag-STANDING"
            }
            // Border case (E2E "Randfall") — omitting the buyer's own billing address is the
            // validation-breaking defect: EN16931/KoSIT requires it (BR-08/BR-09), so leaving it
            // out is exactly the "send an invoice with a validation-breaking defect" the standing
            // E2E rule (feedback-e2e-happy-and-border) asks for, while every OTHER field this
            // fixture sets stays identical to the happy path.
            if (withBuyerAddress) {
                recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            }
            em.persist(sender)
            em.persist(recipient)

            val taxRate = TaxRate().edit {
                code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
            }
            em.persist(taxRate)
            em.flush()
            Triple(sender.id!!, recipient.id!!, taxRate.id!!)
        }

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "SendCompletion E2E Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        return invoiceId
    }

    private fun findInvoice(invoiceId: Long): Invoice =
        QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!! }

    /**
     * `requestSend`'s own job creation fires `GenerationJobCreated` synchronously
     * (`AFTER_SUCCESS`), which — in this E2E module, unlike the plain-unit-test/mocked-collaborator
     * shape — schedules a REAL `GenerationJobFastPublishTransaction.publishOne` attempt on a real managed
     * executor thread (no `NatsTestResource` wired here, so that attempt itself is expected to
     * fail once it reaches the network hop, but its own preceding `Invoice`-touching writes still
     * run for real first). This test drives the job to COMPLETED and calls [resolveSendOutcome]
     * synchronously, on the SAME thread, immediately after — a genuine, if narrow, race against
     * that background attempt for the same `GenerationJob`/`Invoice` rows. Retrying the whole
     * per-job sequence (reloading fresh each attempt) absorbs a transient
     * `OptimisticLockException`/`StaleObjectStateException` from that race exactly the way
     * [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer]'s own real
     * retry-with-backoff loop absorbs the identical class of conflict in production — this is not
     * a workaround for a code defect, it is the same resilience this feature's own production
     * consumer already provides, reproduced here because this test drives `markCompleted`/
     * `resolveSendOutcome` directly instead of through that consumer.
     */
    private fun completeAndResolve(jobId: Long, report: String) {
        var attempt = 0
        while (true) {
            try {
                QuarkusTransaction.requiringNew().run {
                    val current = generationJobQueryService.findById(jobId)!!
                    if (current.status == de.littletrotter.domain.invoice.GenerationJobStatus.PENDING) {
                        generationJobPublishingService.tryMarkDelegated(current)
                    }
                }
                val completed = QuarkusTransaction.requiringNew().call {
                    val delegated = generationJobQueryService.findById(jobId)!!
                    if (delegated.status == de.littletrotter.domain.invoice.GenerationJobStatus.COMPLETED) {
                        delegated
                    } else {
                        generationJobResultHandler.markCompleted(delegated, resultRefs = "{}", kositReport = report)
                    }
                }
                QuarkusTransaction.requiringNew().run { generationJobResultHandler.resolveSendOutcome(completed) }
                return
            } catch (ex: Exception) {
                // Kept broad on purpose -- see this method's own KDoc: absorbing a
                // transient optimistic-lock conflict from the real async fast-path publish attempt
                // racing this test's own synchronous drive of the same job/invoice.
                attempt++
                if (attempt >= 5) throw ex
                Thread.sleep(200)
            }
        }
    }

    // ─── Happy path — send -> real Mustang/KoSIT generation -> ISSUED, e-invoice attached ─────────

    @Test
    fun `happy path - Senden generates a real, KoSIT-valid e-invoice and the invoice reaches ISSUED`() {
        val invoiceId = buildSendableInvoice("E2ESEND", withBuyerAddress = true)

        val job = invoiceSendService.requestSend(invoiceId)
        assertEquals(GenerationJobTrigger.SEND, job.trigger)
        assertTrue(job.issued, "a SEND job is always the issued document (D1/D3)")

        // Stand-in for the real kotlin-xpdf worker's own round trip: the real Mustang generator
        // + the real embedded KoSIT validator produce the exact report a genuine worker result
        // would carry on `document-job-results`.
        val report = realKositReport(invoiceId)
        assertTrue(
            KositReportPayload.decode(report)?.status == KositOutcome.VALID.name,
            "fixture must be genuinely KoSIT-green for this to be the happy path -- got: $report",
        )

        completeAndResolve(job.id!!, report)

        val sent = findInvoice(invoiceId)
        assertEquals(InvoiceStatus.ISSUED, sent.status, "a genuinely KoSIT-valid completion must reach ISSUED")
        assertTrue(sent.invoiceNumber.isAssigned())

        // The completed, issued=true SEND job, with the invoice now itself ISSUED, blocks
        // any further generation for it.
        assertThrows(InvoiceImmutableGenerationBlockedException::class.java) {
            generationJobCreationService.createJob(invoiceId, "PRINT_JOB", DocumentJobLane.BULK)
        }
    }

    // ─── Border case — a validation-breaking defect must never reach ISSUED ───────────────────────

    @Test
    fun `border case - a validation-breaking defect (missing buyer address) never reaches ISSUED`() {
        val invoiceId = buildSendableInvoice("E2EBORDER", withBuyerAddress = false)

        val job = invoiceSendService.requestSend(invoiceId)
        val report = realKositReport(invoiceId)
        assertNotEquals(
            KositOutcome.VALID.name,
            KositReportPayload.decode(report)?.status,
            "fixture must be genuinely KoSIT-invalid for this to be the border case -- a missing " +
                "buyer address must be a real, KoSIT-caught defect, not merely assumed",
        )

        completeAndResolve(job.id!!, report)

        val invoice = findInvoice(invoiceId)
        assertEquals(InvoiceStatus.IN_REVIEW, invoice.status, "a KoSIT-invalid completion must never reach ISSUED")
        assertNotNull(job.id)
        // A retry (always creates a fresh SEND job and can still succeed once the underlying
        // data defect is fixed) is exercised at the IT level
        // (InvoiceSendCompletionIT."an INVALID completion..."); not repeated here to avoid this
        // module's own real async fast-path publish attempt (see completeAndResolve's own KDoc)
        // racing a second, immediately-following requestSend against the same invoice row.
    }
}
