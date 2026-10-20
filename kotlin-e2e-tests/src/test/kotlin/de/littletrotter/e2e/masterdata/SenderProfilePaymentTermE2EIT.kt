package de.littletrotter.e2e.masterdata

import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class SenderProfilePaymentTermE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var commandFacade: CreateInvoiceUseCase

    private fun createPaymentTerm(tag: String, days: Int): PaymentTerm = QuarkusTransaction.requiringNew().call {
        val german = em.find(Language::class.java, 1) ?: Language().edit {
            id = 1
            code = "de"
            isFallback = true
            isActive = true
        }.also { em.persist(it) }

        val term = aPaymentTerm {
            changeName(german, "$tag $days Tage netto")
            dueInDays = days
            isActive = true
        }
        em.persist(term)
        em.flush()
        term
    }

    private fun createRecipient(tag: String): Recipient = QuarkusTransaction.requiringNew().call {
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-CUST-1")
            name = "$tag Kunde GmbH"
            email = Email.ofNullable("kunde@$tag.example")
            vatId = VatId("DE999999999")
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Kundenweg 1", "10115", "Berlin", Country.DE, true))
        em.persist(recipient)
        em.flush()
        recipient
    }

    @Test
    fun `creating a new sender profile with payment term persists and loads payment term correctly`() {
        val term = createPaymentTerm("SPP1", 14)

        val createdId = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "SPP1 Neuer Anbieter GmbH"
                invoicePrefix = "SPP1-"
                registerCourt = "AG Berlin"
                email = Email.ofNullable("billing@spp1.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sellerProfileService.save(sender, defaults = SellerDefaultSelections(paymentTermId = term.id))
            val id = sender.id!!
            sellerProfileService.saveAddress(id, Address.AddressType.BILLING, "Anbieterstr. 1", "10115", "Berlin", "DE")
            id
        }

        // Verify findByIdForEdit resolves paymentTerm and its localized name outside transaction
        val loaded = sellerProfileService.findByIdForEdit(createdId)
        assertNotNull(loaded)
        assertNotNull(loaded?.defaultPaymentTerm)
        assertEquals("SPP1 14 Tage netto", loaded?.defaultPaymentTerm?.nameFor("de"))

        // Update payment term on the existing sender profile
        val term30 = createPaymentTerm("SPP1", 30)
        QuarkusTransaction.requiringNew().call {
            val toUpdate = sellerProfileService.findByIdForEdit(createdId)!!
            sellerProfileService.save(toUpdate, defaults = SellerDefaultSelections(paymentTermId = term30.id))
        }

        val updated = sellerProfileService.findByIdForEdit(createdId)
        assertEquals("SPP1 30 Tage netto", updated?.defaultPaymentTerm?.nameFor("de"))

        // Verify an invoice created for this sender profile derives the updated payment term
        val recipient = createRecipient("SPP1")
        val invoice = QuarkusTransaction.requiringNew().call {
            commandFacade.createInvoiceWithRecipient(createdId, recipient.id!!)
        }
        assertEquals("SPP1 30 Tage netto", invoice.paymentTermName)
    }

    @Test
    fun `creating a new sender profile without payment term and updating it later works`() {
        val createdId = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "SPP2 Anbieter Ohne Term GmbH"
                invoicePrefix = "SPP2-"
                registerCourt = "AG Berlin"
                email = Email.ofNullable("billing@spp2.example")
                taxNumber = TaxNumber("12/345/67891")
                vatId = VatId("DE123456780")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sellerProfileService.save(sender, defaults = SellerDefaultSelections(paymentTermId = null))
            val id = sender.id!!
            sellerProfileService.saveAddress(id, Address.AddressType.BILLING, "Anbieterstr. 2", "10115", "Berlin", "DE")
            id
        }

        val loaded = sellerProfileService.findByIdForEdit(createdId)
        assertNotNull(loaded)
        assertNull(loaded?.defaultPaymentTerm)

        // Assign payment term
        val term = createPaymentTerm("SPP2", 21)
        QuarkusTransaction.requiringNew().call {
            val toUpdate = sellerProfileService.findByIdForEdit(createdId)!!
            sellerProfileService.save(toUpdate, defaults = SellerDefaultSelections(paymentTermId = term.id))
        }

        val updated = sellerProfileService.findByIdForEdit(createdId)
        assertEquals("SPP2 21 Tage netto", updated?.defaultPaymentTerm?.nameFor("de"))
    }
}
