package de.littletrotter.e2e.invoice

import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceOverviewQueryService
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** L28 — the Valkey-cached overview must not serve a stale row after a position change on a DRAFT
 *  invoice (no invoice number yet, so the aggregate queues no item domain event) or after an item
 *  update (which queues none at all). */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceOverviewCacheItemMutationE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var overviewQueryService: InvoiceOverviewQueryService

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun buildParties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
        val sender = SenderProfile().apply {
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
        val recipient = Recipient().apply {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
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
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    private fun overviewGross(tag: String) = QuarkusTransaction.requiringNew().call {
        overviewQueryService.findOverview(0, 10, tag, null, InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC)
    }.single().grossAmount!!.amount

    private fun cmd(tag: String, price: String, taxRateId: Long, itemId: Long? = null) = InvoiceItemCommand(
        itemId = itemId, category = InvoiceItemCategory.SERVICE, description = "$tag Beratung",
        quantity = BigDecimal.ONE, unit = InvoiceItemUnit.HOUR,
        unitPriceNet = Money.of(price), taxRateId = taxRateId,
    )

    @Test
    fun `overview shows the new gross right after an item is added to and then updated on a draft`() {
        val tag = "OC1"
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }

        assertEquals(0, BigDecimal.ZERO.compareTo(overviewGross(tag)), "empty draft, primes the cache")

        val itemId = QuarkusTransaction.requiringNew().call {
            itemMutationService.addItem(invoiceId, cmd(tag, "100.00", parties.taxRateId)).itemList.single().id!!
        }
        assertEquals(0, BigDecimal("119.00").compareTo(overviewGross(tag)), "after addItem")

        QuarkusTransaction.requiringNew().run {
            itemMutationService.updateItem(invoiceId, cmd(tag, "200.00", parties.taxRateId, itemId))
        }
        assertEquals(0, BigDecimal("238.00").compareTo(overviewGross(tag)), "after updateItem")
    }
}
