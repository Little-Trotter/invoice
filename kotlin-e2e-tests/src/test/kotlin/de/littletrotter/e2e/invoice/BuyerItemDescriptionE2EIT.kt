package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * E2E tests for the buyer item description field (Referenz-Beschreibung), the plain
 * `invoice_item.buyer_item_description` column added alongside BT-156 (Referenz-ID):
 * - Happy path: round-trip an item with both a BT-156 value and a buyerItemDescription value
 *   through save/reload.
 * - Edge case 1: an item saved without any BT-156 value still round-trips a
 *   buyerItemDescription value on its own (the two fields are independent).
 * - Edge case 2: a buyerItemDescription of `null` (never filled in) reloads as `null`, not an
 *   empty string.
 * - Edge case 3: a buyerItemDescription at exactly 50 characters (the field's maxLength)
 *   persists in full.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class BuyerItemDescriptionE2EIT {
    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun parties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
        val sender = aSenderProfile {
            name = "$tag BuyerDesc-E2E Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Berlin"
            email = Email.ofNullable("billing@buyerdesc-e2e.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE129273398")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(
            Address.forSender(sender, Address.AddressType.BILLING, "Test Str. 1", "10115", "Berlin", Country.DE, true),
        )
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-BDESC-100")
            name = "$tag BuyerDesc-E2E Recipient AG"
            email = Email.ofNullable("ap@buyerdesc-e2e.example")
            vatId = VatId("DE136695976")
        }
        em.persist(sender)
        em.persist(recipient)
        val taxRate = TaxRate().edit {
            code = "${tag}_BDESC_STD19"
            category = "S"
            name = "USt 19 %"
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isSelectable = true
            isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    private fun reloadedItem(itemId: Long): InvoiceItem = QuarkusTransaction.requiringNew().call {
        em.createQuery("SELECT i FROM InvoiceItem i WHERE i.id = :id", InvoiceItem::class.java)
            .setParameter("id", itemId)
            .singleResult
    }

    @Test
    fun `happy path - buyer item description round-trips alongside BT-156 through save and reload`() {
        val parties = parties("BDHAPPY")
        val invoiceId = QuarkusTransaction.requiringNew().call {
            lifecycleService.create(parties.senderId, parties.recipientId).id!!
        }

        val itemId = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "Beratungsleistung",
                    buyerItemDescription = "Buyer-specific description",
                    quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            ).itemList.last().id!!
        }

        val reloaded = reloadedItem(itemId)
        assertEquals("Buyer-specific description", reloaded.buyerItemDescription)
    }

    @Test
    fun `edge case 1 - buyerItemDescription persists on its own without any BT-156 value`() {
        val parties = parties("BDNOREF")
        val invoiceId = QuarkusTransaction.requiringNew().call {
            lifecycleService.create(parties.senderId, parties.recipientId).id!!
        }

        val itemId = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.PRODUCT,
                    description = "Product without a BT-156 reference",
                    buyerItemDescription = "Description only, no reference id",
                    quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.PIECE,
                    unitPriceNet = Money.of("50.00"),
                    taxRateId = parties.taxRateId,
                ),
            ).itemList.last().id!!
        }

        val reloaded = reloadedItem(itemId)
        assertEquals("Description only, no reference id", reloaded.buyerItemDescription)
    }

    @Test
    fun `edge case 2 - buyerItemDescription left unset reloads as null, not an empty string`() {
        val parties = parties("BDNULL")
        val invoiceId = QuarkusTransaction.requiringNew().call {
            lifecycleService.create(parties.senderId, parties.recipientId).id!!
        }

        val itemId = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.PRODUCT,
                    description = "Product with no buyer description at all",
                    buyerItemDescription = null,
                    quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.PIECE,
                    unitPriceNet = Money.of("50.00"),
                    taxRateId = parties.taxRateId,
                ),
            ).itemList.last().id!!
        }

        val reloaded = reloadedItem(itemId)
        assertNull(reloaded.buyerItemDescription)
    }

    @Test
    fun `edge case 3 - buyerItemDescription at exactly 50 characters persists in full`() {
        val parties = parties("BD50")
        val invoiceId = QuarkusTransaction.requiringNew().call {
            lifecycleService.create(parties.senderId, parties.recipientId).id!!
        }
        val fiftyChars = "a".repeat(50)

        val itemId = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "Service at the max description length",
                    buyerItemDescription = fiftyChars,
                    quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            ).itemList.last().id!!
        }

        val reloaded = reloadedItem(itemId)
        assertEquals(fiftyChars, reloaded.buyerItemDescription)
        assertEquals(50, reloaded.buyerItemDescription?.length)
    }
}
