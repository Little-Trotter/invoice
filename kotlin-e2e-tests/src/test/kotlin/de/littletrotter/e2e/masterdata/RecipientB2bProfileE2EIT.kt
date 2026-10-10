package de.littletrotter.e2e.masterdata

import de.littletrotter.application.masterdata.RecipientAddressInput
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.application.masterdata.MasterDataNumberService
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bProfile
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
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.value.CustomerNumber

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class RecipientB2bProfileE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var customerManagementService: CustomerManagementService

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var masterDataNumberService: MasterDataNumberService

    @Inject
    lateinit var createInvoice: CreateInvoiceUseCase

    @Inject
    lateinit var b2bFieldValueQueries: B2bFieldValueQueryPort

    @Inject
    lateinit var recipientQuery: RecipientQueryPort

    private fun createB2bProfile(tag: String): B2bProfile = QuarkusTransaction.requiringNew().call {
        val profile = B2bProfile().apply {
            name = "$tag Test Profile"
            active = true
        }
        em.persist(profile)
        em.flush()
        profile
    }

    private fun createSenderProfile(tag: String): SenderProfile = QuarkusTransaction.requiringNew().call {
        val sender = SenderProfile().apply {
            name = "$tag Anbieter GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Berlin"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sellerProfileService.save(sender, defaults = SellerDefaultSelections())
        val id = sender.id!!
        sellerProfileService.saveAddress(id, Address.AddressType.BILLING, "Anbieterstr. 1", "10115", "Berlin", "DE")
        sender
    }

    @Test
    fun `creating recipient with B2B profile persists correctly and is inherited by invoice`() {
        val profile = createB2bProfile("RCP1")

        val createdRecipient = QuarkusTransaction.requiringNew().call {
            val recipient = Recipient().apply {
                name = "RCP1 Kunde GmbH"
                customerType = CustomerType.CONSUMER
                email = Email.ofNullable("rcp1@example.invalid")
                b2bProfile = profile
            }
            // The standalone saveAddress(recipientId, ...) entry point was removed
            // (unguarded, no other caller); saveWithAddresses covers header + address in one
            // guarded call.
            customerManagementService.saveWithAddresses(
                recipient,
                RecipientAddressInput(
                    billingStreet = "Kundenstr. 1",
                    billingPostalCode = "10115",
                    billingCity = "Berlin",
                    billingCountry = "DE",
                ),
            )
        }

        val loaded = QuarkusTransaction.requiringNew().call { recipientQuery.findRecipientDetail(createdRecipient.id!!) }
        assertNotNull(loaded)
        assertEquals(profile.id, loaded?.b2bProfileId)

        // Create invoice and verify effective B2B profile
        val sender = createSenderProfile("RCP1")
        val invoice = QuarkusTransaction.requiringNew().call {
            createInvoice.createInvoiceWithRecipient(sender.id!!, createdRecipient.id!!)
        }
        val effectiveProfileId = b2bFieldValueQueries.findB2bProfileIdForInvoice(invoice.id)
        assertEquals(profile.id, effectiveProfileId)
    }

    @Test
    fun `sequential recipient creation with proposed numbers counts up correctly`() {
        val r1 = Recipient().apply { name = "SeqCust One GmbH"; customerType = CustomerType.CONSUMER; email = Email.ofNullable("one@example.invalid") }
        val prop1 = QuarkusTransaction.requiringNew().call { masterDataNumberService.proposeCustomer(r1.name) }
        assertNotNull(prop1)

        val saved1 = QuarkusTransaction.requiringNew().call {
            r1.customerNumber = CustomerNumber(prop1!!)
            customerManagementService.save(r1, proposedCustomerNumber = prop1)
        }
        assertEquals(prop1, saved1.customerNumber?.value)

        val r2 = Recipient().apply { name = "SeqCust Two GmbH"; customerType = CustomerType.CONSUMER; email = Email.ofNullable("two@example.invalid") }
        val prop2 = QuarkusTransaction.requiringNew().call { masterDataNumberService.proposeCustomer(r2.name) }
        assertNotNull(prop2)

        val saved2 = QuarkusTransaction.requiringNew().call {
            r2.customerNumber = CustomerNumber(prop2!!)
            customerManagementService.save(r2, proposedCustomerNumber = prop2)
        }
        assertEquals(prop2, saved2.customerNumber?.value)

        // Verify sequence incremented (trailing digits)
        val digits1 = prop1!!.takeLastWhile { it.isDigit() }.toLong()
        val digits2 = prop2!!.takeLastWhile { it.isDigit() }.toLong()
        assertEquals(digits1 + 1, digits2)
    }
}
