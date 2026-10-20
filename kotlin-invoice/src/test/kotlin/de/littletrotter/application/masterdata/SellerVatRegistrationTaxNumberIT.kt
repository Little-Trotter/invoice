package de.littletrotter.application.masterdata

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.ForeignTaxNumber
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.test.FIXED_TODAY
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * The foreign tax number of a seller registration against a real, freshly provisioned PostgreSQL
 * schema: persistence round trip, the blank-string CHECK, and the copy onto the seller snapshot
 * when the invoice is issued.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SellerVatRegistrationTaxNumberIT {

    private companion object {
        val counter = AtomicInteger(0)
    }

    @Inject lateinit var sellerProfileService: SellerProfileService
    @Inject lateinit var customerManagementService: CustomerManagementService
    @Inject lateinit var invoiceLifecycleService: InvoiceLifecycleService
    @Inject lateinit var invoiceRepository: InvoiceRepository
    @Inject lateinit var itemMutationService: InvoiceItemMutationService
    @Inject lateinit var taxRateQueries: TaxRateQueryPort
    @Inject lateinit var em: EntityManager

    private fun senderWithLu(foreignNumber: String?): SenderProfile = aSenderProfile {
        name = "TaxNo Sender ${counter.incrementAndGet()}"
        invoicePrefix = "TN${counter.get()}-"
        email = Email("sender${System.nanoTime()}@example.invalid")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
        registerCourt = "Amtsgericht Test"
        phone = "+49 30 1234567"
    }.also {
        it.updateVatRegistrations(
            listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"), ForeignTaxNumber.ofNullable(foreignNumber))),
            FIXED_TODAY,
        )
    }

    @Test
    fun `tax number round-trips through the database and can be cleared`() {
        val id = QuarkusTransaction.requiringNew().call {
            sellerProfileService.save(senderWithLu("1990 0101 12345"), defaults = SellerDefaultSelections()).id!!
        }

        val stored = QuarkusTransaction.requiringNew().call {
            em.createNativeQuery("SELECT tax_number FROM seller_vat_registration WHERE sender_profile_id = :id")
                .setParameter("id", id).singleResult as String?
        }
        assertEquals("1990010112345", stored)

        QuarkusTransaction.requiringNew().run {
            val profile = sellerProfileService.findByIdForEdit(id)!!
            profile.updateVatRegistrations(
                listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"), null)),
                FIXED_TODAY,
            )
            sellerProfileService.save(profile, defaults = SellerDefaultSelections())
        }
        val cleared = QuarkusTransaction.requiringNew().call {
            em.createNativeQuery("SELECT tax_number FROM seller_vat_registration WHERE sender_profile_id = :id")
                .setParameter("id", id).singleResult as String?
        }
        assertNull(cleared)
    }

    @Test
    fun `the database refuses a blank tax number`() {
        val id = QuarkusTransaction.requiringNew().call {
            sellerProfileService.save(senderWithLu(null), defaults = SellerDefaultSelections()).id!!
        }

        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("UPDATE seller_vat_registration SET tax_number = '   ' WHERE sender_profile_id = :id")
                    .setParameter("id", id).executeUpdate()
            }
        }
    }

    @Test
    fun `issuing freezes the tax number of the invoice registration on the seller snapshot`() {
        val invoiceId = QuarkusTransaction.requiringNew().call {
            val sender = sellerProfileService.save(senderWithLu("1990010112345"), defaults = SellerDefaultSelections())
            val recipient = aRecipient {
                name = "TaxNo Customer ${counter.incrementAndGet()}"
                email = Email("customer${System.nanoTime()}@example.invalid")
                customerType = CustomerType.CONSUMER
                vatId = VatId("DE987654321")
            }.also {
                it.addAddress(Address.forRecipient(it, Address.AddressType.BILLING, "Rue Exemple 5", "1000", "Luxembourg", Country.LU, true))
                customerManagementService.save(it)
            }
            val invoice = invoiceLifecycleService.create(sender.id!!, recipient.id!!)
            invoice.edit {
                references.buyerReference = "BUYER-REF-TN"
                sellerVatRegistrationCountry = Country.LU
                deliveryDate = LocalDate.now()
            }
            invoiceRepository.saveAndFlush(invoice)
            itemMutationService.addItem(
                invoice.id!!,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "Test Item",
                    quantity = java.math.BigDecimal.ONE,
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = de.littletrotter.shared.Money.of("100.00"),
                    taxRateId = taxRateQueries.findAllTaxRates().first { it.code == "LU_STANDARD_17" }.id,
                ),
            )
            invoiceLifecycleService.transition(invoice.id!!, InvoiceStatus.IN_REVIEW)
            invoiceLifecycleService.transition(invoice.id!!, InvoiceStatus.ISSUED)
            invoice.id!!
        }

        val frozen = QuarkusTransaction.requiringNew().call {
            em.createNativeQuery(
                "SELECT registration_tax_number FROM invoice_party_snapshot WHERE invoice_id = :id AND party_role = 0",
            ).setParameter("id", invoiceId).singleResult as String?
        }
        assertEquals("1990010112345", frozen)

        val onBuyerRow = QuarkusTransaction.requiringNew().call {
            em.createNativeQuery(
                "SELECT registration_tax_number FROM invoice_party_snapshot WHERE invoice_id = :id AND party_role = 1",
            ).setParameter("id", invoiceId).singleResult as String?
        }
        assertNull(onBuyerRow)
    }

    @Test
    fun `the database refuses a registration tax number on the buyer row`() {
        val invoiceId = QuarkusTransaction.requiringNew().call {
            val sender = sellerProfileService.save(senderWithLu(null), defaults = SellerDefaultSelections())
            val recipient = aRecipient {
                name = "TaxNo Buyer ${counter.incrementAndGet()}"
                email = Email("buyer${System.nanoTime()}@example.invalid")
                customerType = CustomerType.CONSUMER
                vatId = VatId("DE987654321")
            }.also { customerManagementService.save(it) }
            invoiceLifecycleService.create(sender.id!!, recipient.id!!).id!!
        }

        assertThrows(PersistenceException::class.java) {
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery(
                    "UPDATE invoice_party_snapshot SET registration_tax_number = '123' WHERE invoice_id = :id AND party_role = 1",
                ).setParameter("id", invoiceId).executeUpdate()
            }
        }
    }
}
