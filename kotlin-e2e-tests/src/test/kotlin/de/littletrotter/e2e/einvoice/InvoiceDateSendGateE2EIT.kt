package de.littletrotter.e2e.einvoice

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDateBeforeServiceEndException
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
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * PO decision 2026-08-31 — § 14 Abs. 4 Nr. 6 UStG send-date gate
 * ([Invoice.ensureInvoiceDateNotBeforeServiceEnd], wired into
 * [InvoiceLifecycleService.transition]'s `→ SENT` branch), driven end to end through the real
 * application services (no UI driver), the same pattern [Bt19EndToEndE2EIT]/`B2bProfileE2EIT`
 * already establish: real [InvoiceLifecycleService] against a real PostgreSQL
 * ([PostgresTestResource]), the real embedded KoSIT validator for the "KoSIT green" pin.
 *
 * Every scenario here runs against the application's real time source, so all dates are built
 * RELATIVE to today, never as literal calendar days — a literal date would exercise the intended
 * branch only when the suite happens to run on the matching day and silently flip to the other
 * branch afterwards. Scenarios that need a fixed moment, such as the year boundary, fix the time
 * source instead (see `InvoiceNumberYearRolloverE2EIT`). The PO's two examples (2026-08-31: single-day service 31.08.
 * sendable on 31.08.; period 20.08.–02.09. sendable only from 02.09.) are reproduced in shape:
 * "delivery date = today, sent today" and "13-day period whose end is still ahead, send blocked".
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceDateSendGateE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

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

    private fun assertKositValid(xml: String, label: String) {
        val result = kosit.validate(xml, timeoutMs = 30_000)
        assertEquals(KositOutcome.VALID, result.status, "$label: KoSIT must be VALID, was ${result.status} - ${result.details}")
    }

    // ─── Fixture — one sender/recipient/tax-rate/item combo, service dates set per scenario ───────

    private fun buildDraftInvoice(tag: String, draftInvoiceDate: LocalDate?): Long {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = SenderProfile().apply {
                name = "SendGate Sender $tag GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
                phone = "+49 30 1234567"
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("$tag-100")
                name = "SendGate Empfänger $tag AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
                // BR-DE-15/PEPPOL-EN16931-R008 — a standing Leitweg-ID so ensureBuyerReferenceForFinalization
                // never blocks this suite's own scenario.
                routingId = "PO-$tag-STANDING"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)

            val taxRate = TaxRate().apply {
                code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
            }
            em.persist(taxRate)
            em.flush()
            Triple(sender.id!!, recipient.id!!, taxRate.id!!)
        }

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
        if (draftInvoiceDate != null) {
            // dueDate moves along with the far-future draft invoiceDate so this fixture stays
            // plausible; the DB no longer enforces the ordering, but a draft whose due date sits
            // months before its invoice date would muddy what this test is actually about.
            QuarkusTransaction.requiringNew().run {
                val invoice = em.find(Invoice::class.java, invoiceId)!!
                invoice.invoiceDate = draftInvoiceDate
                invoice.dueDate = draftInvoiceDate.plusDays(14)
            }
        }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "SendGate E2E Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }
        return invoiceId
    }

    private fun setDeliveryDate(invoiceId: Long, date: LocalDate) {
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.deliveryDate = date }
    }

    private fun setServicePeriod(invoiceId: Long, from: LocalDate, to: LocalDate) {
        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            invoice.servicePeriodFrom = from
            invoice.servicePeriodTo = to
        }
    }

    private fun findInvoice(invoiceId: Long): Invoice =
        QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!! }

    // ─── Happy path — a future-dated draft is still legal, and gets stamped to "today" at SENT ────

    @Test
    fun `happy path - a future-dated draft stamps today at SENT and KoSIT stays green`() {
        val tag = "DGHAPPY"
        val invoiceId = buildDraftInvoice(tag, draftInvoiceDate = LocalDate.now().plusYears(1))
        // A single-day service ending exactly "today" - always sendable the day it happens.
        setDeliveryDate(invoiceId, LocalDate.now())

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }

        // Bt19EndToEndE2EIT's own pattern — items is lazy, so the DTO mapping (which walks them)
        // must happen inside the same transaction/session as the em.find, not after it returns.
        val (headerFields, dto) = QuarkusTransaction.requiringNew().call {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            Triple(invoice.status, invoice.invoiceDate, invoice.dueDate) to
                InvoiceDtoMapper(b2bFieldValueService = b2bFieldValueService).mapToDto(invoice)
        }
        val (status, invoiceDate, dueDate) = headerFields
        assertEquals(InvoiceStatus.SENT, status)
        assertEquals(LocalDate.now(), invoiceDate, "the far-future draft date must be overwritten with today at the SENT transition")
        // finding-fixes (2026-08-31) — Invoice.recomputeDueDateForSend's own KDoc: create()
        // resolves the sender's defaultPaymentTerm or the global default ("14 Tage netto",
        // due_in_days=14, V2__baseline_seed.sql) for every invoice, so freezePaymentTermSnapshot
        // freezes that 14-day count at SENT and dueDate is re-derived from it, not preserved as a
        // day-gap — kept consistent so the fixture stays plausible
        // outright (the draft's dueDate was computed against an invoiceDate a full year away).
        assertEquals(
            LocalDate.now().plusDays(14), dueDate,
            "dueDate must be recomputed from the frozen payment-term snapshot's dueInDays, not left at its draft value",
        )
        assertKositValid(mustangXml(dto), "$tag/happy path")
    }

    // ─── Single-day service, sent the same day (delivery date = today exercises this on every run) ──

    @Test
    fun `PO example - single-day service today, sent today - allowed (same day)`() {
        val tag = "DGSINGLE"
        val invoiceId = buildDraftInvoice(tag, draftInvoiceDate = null)
        val serviceDay = LocalDate.now()
        setDeliveryDate(invoiceId, serviceDay)

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }

        val invoice = findInvoice(invoiceId)
        assertEquals(InvoiceStatus.SENT, invoice.status, "same-day service, sent same day must be allowed")
        assertEquals(serviceDay, invoice.invoiceDate)
    }

    // ─── Multi-day period still running, send blocked (13-day period ending in two days) ────────────

    @Test
    fun `PO example - 13-day period ending in two days, sent before the end - blocked citing section 14 UStG`() {
        val tag = "DGPERIOD"
        val invoiceId = buildDraftInvoice(tag, draftInvoiceDate = null)
        setServicePeriod(invoiceId, LocalDate.now().minusDays(11), LocalDate.now().plusDays(2))

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        val ex = assertThrows(InvoiceDateBeforeServiceEndException::class.java) {
            QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }
        }
        assertTrue(ex.message!!.contains("§ 14 Abs. 4 Nr. 6 UStG"), "message must cite the legal basis: ${ex.message}")

        val invoice = findInvoice(invoiceId)
        assertEquals(InvoiceStatus.IN_REVIEW, invoice.status, "a blocked send must never advance the invoice's status")
    }

    // ─── period rule, ALLOWED side — sendable once the period has actually ended ────────────────

    @Test
    fun `period ending exactly today - sendable today (same-day rule applies to periods too)`() {
        val tag = "DGPEROK"
        val invoiceId = buildDraftInvoice(tag, draftInvoiceDate = null)
        setServicePeriod(invoiceId, LocalDate.now().minusDays(13), LocalDate.now())

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }

        assertEquals(InvoiceStatus.SENT, findInvoice(invoiceId).status)
    }

    // ─── plain blocked-send case — service end tomorrow ────────────────────────────────────────

    @Test
    fun `blocked-send case - service ends tomorrow, sent today - blocked`() {
        val tag = "DGBLOCK"
        val invoiceId = buildDraftInvoice(tag, draftInvoiceDate = null)
        setDeliveryDate(invoiceId, LocalDate.now().plusDays(1))

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        val ex = assertThrows(InvoiceDateBeforeServiceEndException::class.java) {
            QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.SENT) }
        }
        assertTrue(ex.message!!.contains("§ 14 Abs. 4 Nr. 6 UStG"))
        assertEquals(InvoiceStatus.IN_REVIEW, findInvoice(invoiceId).status)
    }
}
