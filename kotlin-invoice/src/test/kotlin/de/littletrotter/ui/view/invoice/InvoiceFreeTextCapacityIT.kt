package de.littletrotter.ui.view.invoice

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Currency
import de.littletrotter.shared.documents.FreeTextMarkup
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * The invoice free text is unbounded end to end. `invoice.free_text` is `TEXT` and the audit
 * journal's `detail` is `JSONB`; neither may cap what the editor allows (FreeTextMarkup.MAX_LENGTH characters) nor
 * what the server appends afterwards (storno reference, imported B2B lines). Real Postgres,
 * real header-update path, real audit trigger.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceFreeTextCapacityIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase

    /** Umlauts, `€`, a 4-byte emoji, line breaks and 4-space list indentation, exactly [length] UTF-16 units. */
    private fun mixedText(length: Int): String {
        val chunk = "Größe – Übergabe für 1.234,56 € 😀\n- Punkt\n    - eingerückt\n"
        val sb = StringBuilder()
        while (sb.length + chunk.length <= length) sb.append(chunk)
        while (sb.length < length) sb.append('x')
        return sb.toString()
    }

    private fun draftInvoiceId(): Long {
        val (senderId, recipientId) = QuarkusTransaction.requiringNew().call {
            val sender = SenderProfile().apply {
                phone = "+49 30 1234567"
                name = "FTC Sender GmbH"
                invoicePrefix = "FTC-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@ftc.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("FTC-100")
                name = "FTC Empfänger AG"
                email = Email.ofNullable("ap@ftc.example")
                vatId = VatId("DE222222222")
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)
            em.flush()
            sender.id!! to recipient.id!!
        }
        return QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }
    }

    private fun saveFreeText(invoiceId: Long, senderId: Long?, recipientId: Long?, freeText: String) {
        QuarkusTransaction.requiringNew().run {
            editInvoiceHeaderUseCase.updateInvoiceHeader(
                UpdateInvoiceHeaderCommand(
                    invoiceId = invoiceId,
                    expectedVersion = null,
                    invoiceDate = LocalDate.of(2026, 8, 20),
                    servicePeriodFrom = null,
                    servicePeriodTo = null,
                    dueDate = null,
                    freeText = freeText,
                    buyerReference = null,
                    orderReference = null,
                    orderReferenceDate = null,
                    currency = Currency.EUR,
                    recipientId = recipientId,
                    senderProfileId = senderId,
                    paymentTermId = null,
                ),
            )
        }
    }

    private fun storedFreeText(invoiceId: Long): String? =
        QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!!.freeText }

    /** `free_text` old/new values of every journal row of [invoiceId] that changed it, oldest first. */
    private fun journalFreeTextChanges(invoiceId: Long): List<Pair<String?, String?>> =
        QuarkusTransaction.requiringNew().call {
            em.createNativeQuery(
                "SELECT detail->'free_text'->>'old', detail->'free_text'->>'new' FROM invoice_audit_journal " +
                    "WHERE invoice_id = :id AND event = 1 AND detail->'free_text' IS NOT NULL ORDER BY seq",
            ).setParameter("id", invoiceId).resultList.filterIsInstance<Array<*>>()
                .map { it[0] as String? to it[1] as String? }
        }

    @Test
    fun `a MAX_LENGTH and a MAX_LENGTH plus 1500 character free text round-trip whole and the audit journal holds old and new in full`() {
        val invoiceId = draftInvoiceId()
        val (senderId, recipientId) = QuarkusTransaction.requiringNew().call {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            invoice.senderProfile!!.id to invoice.recipient!!.id
        }
        val atMax = mixedText(FreeTextMarkup.MAX_LENGTH)
        val grownPastCap = mixedText(FreeTextMarkup.MAX_LENGTH + 1500)
        assertEquals(FreeTextMarkup.MAX_LENGTH, atMax.length)

        saveFreeText(invoiceId, senderId, recipientId, atMax)
        assertEquals(atMax, storedFreeText(invoiceId), "MAX_LENGTH characters must come back unchanged")
        assertEquals(FreeTextMarkup.MAX_LENGTH, storedFreeText(invoiceId)!!.length)

        saveFreeText(invoiceId, senderId, recipientId, grownPastCap)
        assertEquals(grownPastCap, storedFreeText(invoiceId), "the database must not silently cap what the server appends")

        // char_length counts code points; the UTF-16 length of the emoji-carrying text differs from it.
        val dbLength = QuarkusTransaction.requiringNew().call {
            (em.createNativeQuery("SELECT char_length(free_text) FROM invoice WHERE id = :id").setParameter("id", invoiceId).singleResult as Number).toInt()
        }
        assertEquals(grownPastCap.codePointCount(0, grownPastCap.length), dbLength)

        val changes = journalFreeTextChanges(invoiceId)
        assertEquals(listOf(null to atMax, atMax to grownPastCap), changes, "audit detail must hold the full old and new free text")
    }
}
