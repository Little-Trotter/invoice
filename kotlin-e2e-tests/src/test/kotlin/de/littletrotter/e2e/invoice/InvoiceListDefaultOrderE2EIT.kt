package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceOverviewQueryService
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * The invoice list's newest-first default, end to end against the real query service (PO order:
 * the list must always open with the newest invoices on top). Two guarantees, both order-
 * independent of whatever the rest of the suite created before:
 * - differing invoice dates sort descending by date;
 * - equal invoice dates fall back to the id tiebreaker, so the most recently created invoice of
 *   the day is on top — without the tiebreaker the same-date order would be arbitrary and a
 *   freshly created invoice could hide below older ones.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceListDefaultOrderE2EIT {

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
        val sender = aSenderProfile {
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)
        val taxRate = TaxRate().edit {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    private fun draftInvoice(parties: Parties, tag: String, invoiceDate: LocalDate): Long {
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("1"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
            em.find(Invoice::class.java, invoiceId).edit { this.invoiceDate = invoiceDate }
        }
        return invoiceId
    }

    /** The exact call the list view's pristine entry makes: date descending, no filters. */
    private fun defaultOrderIds(): List<Long> = QuarkusTransaction.requiringNew().call {
        overviewQueryService.findOverview(
            0, 500, null, null,
            InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC, null,
        ).mapNotNull { it.id }
    }

    @Test
    fun `newer invoice dates come first in the default order`() {
        val parties = buildParties("ORD1")
        val older = draftInvoice(parties, "ORD1-old", E2eBusinessDay.today().minusDays(7))
        val newer = draftInvoice(parties, "ORD1-new", E2eBusinessDay.today())

        val ids = defaultOrderIds()
        assertTrue(
            ids.indexOf(newer) < ids.indexOf(older),
            "the invoice dated today must be listed before the one dated a week ago",
        )
    }

    @Test
    fun `on the same invoice date the most recently created invoice is on top`() {
        val parties = buildParties("ORD2")
        val date = E2eBusinessDay.today()
        val first = draftInvoice(parties, "ORD2-first", date)
        val second = draftInvoice(parties, "ORD2-second", date)

        val ids = defaultOrderIds()
        assertTrue(
            ids.indexOf(second) < ids.indexOf(first),
            "with equal dates the id tiebreaker must put the later-created invoice first",
        )
    }
}
