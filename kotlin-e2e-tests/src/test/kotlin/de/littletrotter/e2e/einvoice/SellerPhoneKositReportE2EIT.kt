package de.littletrotter.e2e.einvoice

import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobPublishingService
import de.littletrotter.application.invoice.GenerationJobResultHandler
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * N9 — the actual acceptance proof for the package: a full
 * generation from seeded, realistic master data must produce a document whose **persisted**
 * validation report (`GenerationJob.kositReport`, the exact column [GenerationJobService
 * .markCompleted] writes and the invoice detail view's KoSIT report dialog reads back) carries
 * neither BR-DE-6 (BT-42/seller phone missing) nor BR-DE-27 (BT-42 with fewer than three digits).
 *
 * Every step of the real pipeline runs for real: [InvoiceLifecycleService.transition] (the
 * unconditional [de.littletrotter.application.invoice.FinalizationPolicy
 * .enforceSellerTelephoneNumber] check this package adds runs here too — a seller without BT-42
 * could not even reach `SENT`, see `FinalizationGateIT`'s own dedicated rejection case for that
 * half), [InvoiceDtoMapper], [XmlExportServiceImpl] (the same real Mustang export path
 * `EInvoiceGenerationE2EIT` proves elsewhere) and the real, embedded [EmbeddedKositValidator] —
 * the same XRechnung 3.0.2 scenario a production KoSIT run uses. The only thing not exercised is
 * the NATS transport itself ([de.littletrotter.e2e.nats.DraftInvoiceResultCallbackE2EIT] already
 * covers that write-back path in isolation); this test calls [GenerationJobService.markCompleted]
 * directly with the real validator's own result, which is exactly what
 * `GenerationJobResultConsumer.consume` does once it has decoded the worker's envelope.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class SellerPhoneKositReportE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobPublishingService: GenerationJobPublishingService

    @Inject
    lateinit var generationJobResultHandler: GenerationJobResultHandler

    companion object {
        /** The real, embedded KoSIT validator — same idiom as `EInvoiceGenerationE2EIT`/
         *  `KositValidationIT`: loaded once, no CDI container needed outside the `@QuarkusTest`
         *  that also boots Postgres. */
        private lateinit var kosit: EmbeddedKositValidator

        @JvmStatic
        @BeforeAll
        fun initKosit() {
            kosit = EmbeddedKositValidator().apply { init() }
        }
    }

    private fun buildInvoice(tag: String): Long {
        val sender = SenderProfile().apply {
            name = "Little Trotter Consulting GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE89370400440532013000")
            // N9 — BT-42, satisfying both BR-DE-6 (present) and BR-DE-27 (>= 3 digits): the exact
            // field this package makes the seeded master data carry.
            phone = "+49 30 1234567"
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "Musterkunde AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE987654321")
            routingId = "04011000-1234512345-06"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender); em.persist(recipient)

        val taxRate = TaxRate().apply {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isActive = true; isSelectable = true
        }
        em.persist(taxRate)

        val invoice = Invoice().apply {
            senderProfile = sender
            this.recipient = recipient
            invoiceDate = LocalDate.of(2026, 2, 10)
            dueDate = LocalDate.of(2026, 3, 12)
            deliveryDate = LocalDate.now() // CORE.SERVICE_PERIOD_XOR (BT-72) — satisfied cheaply
            references.buyerReference = "PO-$tag-1"
        }
        invoice.addItem(
            InvoiceItem().apply {
                positionId = "1"; category = InvoiceItemCategory.SERVICE; description = "Beratung"
                quantity = BigDecimal("3"); unit = InvoiceItemUnit.HOUR; unitPriceNet = Money.of("150.00")
                sortOrder = 0
            }.also { it.applyTaxRule(taxRate) },
        )
        invoice.recalculateTotals()
        em.persist(invoice); em.flush()
        return invoice.id!!
    }

    @Test
    fun `N9 - a full generation from seeded master data with a valid seller phone persists a validation report with neither BR-DE-6 nor BR-DE-27`() {
        val invoiceId = QuarkusTransaction.requiringNew().call<Long> { buildInvoice("N9GEN") }

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }

        val (jobId, xml) = QuarkusTransaction.requiringNew().call<Pair<Long, String>> {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            assertEquals(InvoiceStatus.SENT, invoice.status, "the fixture must actually finalize for this proof to mean anything")
            val dto = InvoiceDtoMapper().mapToDto(invoice)
            val xml = String(XmlExportServiceImpl(null).generate(dto), Charsets.UTF_8)
            val job = generationJobCreationService.createJob(invoice, "PDF_EXPORT", DocumentJobLane.BULK)
            generationJobPublishingService.markDelegated(job)
            job.id!! to xml
        }

        // The real, embedded validator — the same one a production worker run uses.
        val result = kosit.validate(xml)

        QuarkusTransaction.requiringNew().run {
            val job = em.find(GenerationJob::class.java, jobId)!!
            generationJobResultHandler.markCompleted(job, kositReport = KositReportPayload.encode(result))
        }

        val persisted = QuarkusTransaction.requiringNew().call<KositReportPayload> {
            em.clear()
            val job = em.find(GenerationJob::class.java, jobId)!!
            KositReportPayload.decode(job.kositReport)!!
        }

        assertEquals(
            KositOutcome.VALID, KositOutcome.valueOf(persisted.status),
            "the seeded master data's own BT-42 must keep the persisted report VALID — ${persisted.details}",
        )
        assertTrue(
            persisted.details.none { it.ruleId == "BR-DE-6" },
            "BR-DE-6 (seller phone missing) must not appear in the persisted report: ${persisted.details}",
        )
        assertTrue(
            persisted.details.none { it.ruleId == "BR-DE-27" },
            "BR-DE-27 (seller phone too few digits) must not appear in the persisted report: ${persisted.details}",
        )
    }
}
