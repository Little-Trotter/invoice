package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.einvoice.EInvoiceXml
import de.littletrotter.shared.Money
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.fixture.edit

/**
 * PO report 03.09.2026 — creating an e-invoice for a customer whose VAT id is longer than 30
 * characters (legal per `recipient.vat_id VARCHAR(64)`, e.g. a Luxembourg customer such as
 * KD-NEKAR-LU) used to fail at [CreateInvoiceUseCase.createInvoiceWithRecipient] with
 * `value too long for type character varying(30)`, because `invoice_party_snapshot.vat_id` was
 * narrower than the columns it copies from. Reproduces the exact input-port path the UI's
 * "Neue Rechnung" dialog and the customer table's per-row action drive
 * ([de.littletrotter.ui.view.invoice.InvoiceCreateDialog]), then finalizes and generates the
 * e-invoice XML the same way [de.littletrotter.e2e.einvoice.EInvoiceGenerationE2EIT] does, to
 * prove the fix holds end to end, not just at the initial insert.
 *
 * Coordinator follow-up: the recipient's VAT id is now a real third-country identifier
 * (an unrecognised-but-syntactically-complete prefix `VatIdValidator` has no regex for) instead
 * of arbitrary digits: `VatId` accepts that shape (only a *known* prefix with the wrong shape, or
 * a value over the column width, is rejected), matching what this field allowed before `VatId`
 * existed (a bare `@Size(max = 64)` bound, no format check).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class PartySnapshotVatIdWidthE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var changeInvoiceStatus: ChangeInvoiceStatusUseCase

    @Inject
    lateinit var editInvoiceItems: EditInvoiceItemsUseCase

    @Inject
    lateinit var taxRateQueries: TaxRateQueryPort

    @Inject
    lateinit var createInvoiceUseCase: CreateInvoiceUseCase

    private val mustang = XmlExportServiceImpl(null)

    private val createdInvoiceIds = mutableListOf<Long>()
    private val createdRecipientIds = mutableListOf<Long>()
    private val createdSenderIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        QuarkusTransaction.requiringNew().run {
            createdInvoiceIds.forEach { em.find(Invoice::class.java, it)?.let(em::remove) }
            createdRecipientIds.forEach { em.find(Recipient::class.java, it)?.let(em::remove) }
            createdSenderIds.forEach { em.find(SenderProfile::class.java, it)?.let(em::remove) }
        }
    }

    // 34 characters — longer than the pre-fix 30-char snapshot column, well within the widened
    // 64-char one and within recipient.vat_id's own VARCHAR(64). JP has no recognised
    // VatIdValidator format, so this is exactly the UNKNOWN_PREFIX shape the coordinator follow-up
    // carves out.
    private val longVatId = "JP" + "1".repeat(32)

    private fun sender(tag: String): SenderProfile {
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
        return sender
    }

    private fun recipientWithLongVatId(tag: String): Recipient {
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Michels S.A."
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId(longVatId)
            routingId = "PO-$tag-1"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 5", "1000", "Luxembourg", Country.LU, true))
        return recipient
    }

    @Test
    fun `creating an invoice for a recipient with a 34-char VAT id succeeds and the buyer snapshot carries it in full`() {
        val tag = "PSVW"
        val (senderId, recipientId) = QuarkusTransaction.requiringNew().call {
            val s = sender(tag)
            val r = recipientWithLongVatId(tag)
            em.persist(s); em.persist(r); em.flush()
            listOf(s.id!!, r.id!!)
        }
        createdSenderIds += senderId; createdRecipientIds += recipientId

        // The exact reproduction point of the PO-reported bug: before the fix, the cascaded
        // insert into invoice_party_snapshot threw here.
        val created = QuarkusTransaction.requiringNew().call {
            createInvoiceUseCase.createInvoiceWithRecipient(senderId, recipientId, reverseChargeDecision = false)
        }
        createdInvoiceIds += created.id

        assertEquals(longVatId, created.recipient?.vatId, "fixture sanity: the recipient itself carries the full 34-char VAT id")

        val snapshotVatId = QuarkusTransaction.requiringNew().call {
            em.createNativeQuery(
                "SELECT vat_id FROM invoice_party_snapshot WHERE invoice_id = :invoiceId AND party_role = 1",
            ).setParameter("invoiceId", created.id).singleResult as String
        }
        assertEquals(longVatId, snapshotVatId, "the BUYER party snapshot row carries the full, untruncated VAT id")

        // Finalize and generate — same shape as EInvoiceGenerationE2EIT — proving the fix holds
        // through the whole lifecycle, not just at the initial insert.
        val taxRateId = taxRateQueries.findAllTaxRates().find { it.code == "DE_STANDARD_19" }?.id
            ?: error("expected seeded TaxRate 'DE_STANDARD_19' (V2__baseline_seed.sql) to exist")

        QuarkusTransaction.requiringNew().run {
            editInvoiceItems.addItemToInvoice(
                created.id,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, created.id)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        QuarkusTransaction.requiringNew().run { changeInvoiceStatus.transitionInvoice(created.id, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { changeInvoiceStatus.transitionInvoice(created.id, InvoiceStatus.ISSUED) }

        val dto = QuarkusTransaction.requiringNew().call { InvoiceDtoMapper().mapToDto(em.find(Invoice::class.java, created.id)!!) }
        val xml = String(mustang.generate(dto), Charsets.UTF_8)
        EInvoiceXml.assertText(
            xml, "//ram:BuyerTradeParty/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='VA']", longVatId,
            "BT-48 — the generated e-invoice still carries the full buyer VAT id after finalization",
        )
    }
}
