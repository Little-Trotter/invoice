package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.Currency
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/** Party change must re-derive defaults (billing/delivery address, payment term, routing ID),
 *  but leave them alone if party doesn't change. Tests the real facade and repository through
 *  [de.littletrotter.application.invoice.InvoiceLifecycleService.updateHeader]. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceHeaderPartyDefaultsE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var editInvoiceHeader: EditInvoiceHeaderUseCase

    @Inject
    lateinit var createInvoiceUseCase: CreateInvoiceUseCase

    private fun senderWithBillingAddress(tag: String, street: String, paymentTerm: PaymentTerm? = null): SenderProfile {
        val sender = SenderProfile().apply {
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
            defaultPaymentTerm = paymentTerm
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, street, "10115", "Berlin", Country.DE, true))
        return sender
    }

    private fun recipientWithDeliveryAddress(tag: String, street: String, routingId: String?): Recipient {
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
            this.routingId = routingId
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.DELIVERY, street, "20095", "Hamburg", Country.DE, true))
        return recipient
    }

    @Test
    fun `updateInvoiceHeader re-derives deliveryAddress and buyerReference from a NEW recipient`() {
        val (senderId, oldRecipientId, newRecipientId, invoiceId) = QuarkusTransaction.requiringNew().call {
            val sender = senderWithBillingAddress("HPD1", "Anbieterweg 1")
            val oldRecipient = recipientWithDeliveryAddress("HPD1OLD", "Alte Lieferstr. 1", routingId = null)
            val newRecipient = recipientWithDeliveryAddress("HPD1NEW", "Neue Lieferstr. 2", routingId = "991-01484-64")
            em.persist(sender); em.persist(oldRecipient); em.persist(newRecipient)
            em.flush()
            val invoiceId = createInvoiceUseCase.createInvoiceWithRecipient(sender.id!!, oldRecipient.id!!).id
            listOf(sender.id!!, oldRecipient.id!!, newRecipient.id!!, invoiceId)
        }

        val updated = QuarkusTransaction.requiringNew().call {
            editInvoiceHeader.updateInvoiceHeader(headerCommand(invoiceId, senderId, newRecipientId))
        }

        assertEquals("Neue Lieferstr. 2", updated.deliveryAddress?.street, "die Lieferadresse kommt vom neu gewählten Empfänger")
        assertEquals("991-01484-64", updated.buyerReference, "die Käuferreferenz (BT-10) kommt vom neu gewählten Empfänger")
    }

    @Test
    fun `updateInvoiceHeader leaves deliveryAddress and buyerReference untouched when the recipient is unchanged`() {
        val (senderId, recipientId, invoiceId) = QuarkusTransaction.requiringNew().call {
            val sender = senderWithBillingAddress("HPD2", "Anbieterweg 1")
            val recipient = recipientWithDeliveryAddress("HPD2", "Lieferstr. 1", routingId = "STANDING-2")
            em.persist(sender); em.persist(recipient)
            em.flush()
            val invoiceId = createInvoiceUseCase.createInvoiceWithRecipient(sender.id!!, recipient.id!!).id
            Triple(sender.id!!, recipient.id!!, invoiceId)
        }

        val updated = QuarkusTransaction.requiringNew().call {
            editInvoiceHeader.updateInvoiceHeader(
                headerCommand(invoiceId, senderId, recipientId).copy(buyerReference = "Manuell ueberschrieben"),
            )
        }

        assertEquals("Lieferstr. 1", updated.deliveryAddress?.street, "ein unveraenderter Empfaenger laesst die Lieferadresse unangetastet")
        assertEquals("Manuell ueberschrieben", updated.buyerReference, "der Bearbeiter darf die vorgeschlagene Referenz weiter bearbeiten")
    }

    @Test
    fun `updateInvoiceHeader re-derives billingAddress and the default paymentTerm from a NEW sender`() {
        val (oldSenderId, newSenderId, recipientId, invoiceId) = QuarkusTransaction.requiringNew().call {
            val net14 = PaymentTerm().apply { setName(em.find(de.littletrotter.domain.reference.Language::class.java, 1)!!, "HPD3 14 Tage netto"); dueInDays = 14 }
            em.persist(net14); em.flush()
            val oldSender = senderWithBillingAddress("HPD3OLD", "Alte Anbieterstr. 1")
            val newSender = senderWithBillingAddress("HPD3NEW", "Neue Anbieterstr. 3", paymentTerm = net14)
            val recipient = recipientWithDeliveryAddress("HPD3", "Lieferstr. 1", routingId = null)
            em.persist(oldSender); em.persist(newSender); em.persist(recipient)
            em.flush()
            val invoiceId = createInvoiceUseCase.createInvoiceWithRecipient(oldSender.id!!, recipient.id!!).id
            listOf(oldSender.id!!, newSender.id!!, recipient.id!!, invoiceId)
        }

        val updated = QuarkusTransaction.requiringNew().call {
            editInvoiceHeader.updateInvoiceHeader(headerCommand(invoiceId, newSenderId, recipientId))
        }

        assertEquals("Neue Anbieterstr. 3", updated.billingAddress?.street, "die Rechnungsadresse kommt vom neu gewählten Anbieter")
        assertEquals("HPD3 14 Tage netto", updated.paymentTermName, "das Zahlungsziel kommt vom Standard-Zahlungsziel des neu gewählten Anbieters")
    }

    private fun headerCommand(invoiceId: Long, senderId: Long, recipientId: Long) = UpdateInvoiceHeaderCommand(
        invoiceId = invoiceId,
        expectedVersion = null,
        invoiceDate = null,
        servicePeriodFrom = null,
        servicePeriodTo = null,
        dueDate = null,
        freeText = null,
        buyerReference = null,
        orderReference = null,
        orderReferenceDate = null,
        currency = Currency.EUR,
        recipientId = recipientId,
        senderProfileId = senderId,
        paymentTermId = null,
        sellerVatRegistrationCountry = null,
        invoiceFormat = null,
    )
}
