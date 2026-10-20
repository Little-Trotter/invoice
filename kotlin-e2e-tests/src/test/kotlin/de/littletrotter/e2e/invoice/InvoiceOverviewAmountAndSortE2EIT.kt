package de.littletrotter.e2e.invoice

import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.invoice.InvoiceOverviewQueryService
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.Money
import de.littletrotter.ui.format.MoneyFormatter
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.fixture.anInvoice
import de.littletrotter.domain.invoice.InvoiceTotals

/** Large gross amounts must preserve precision through [InvoiceOverviewQueryService.findOverview],
 *  Postgres NUMERIC, Valkey cache's Jackson JSON round-trip, and [MoneyFormatter]. Invoice number
 *  sort is real, not a silent fallback to invoiceDate. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceOverviewAmountAndSortE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var overviewQueryService: InvoiceOverviewQueryService

    /** Persists one ISSUED invoice with exactly the given gross amount (net/vat chosen so they sum
     *  to it — no CHECK constraint ties them together, only each column's own non-negativity, but
     *  keeping them internally consistent avoids modeling a nonsensical invoice). [tag] keeps every
     *  test's rows out of each other's way: it seeds the invoicePrefix/customerNumber/tax-rate code
     *  AND doubles as the [filterText] each test scopes its own query to. */
    private fun persistInvoice(tag: String, number: String, netAmount: String, vatAmount: String, grossAmount: String): Long {
        // A test may persist several invoices under the same tag (e.g. the sort test) — every
        // unique/tag-scoped column (invoice_prefix, customer_number, tax-rate code) must stay
        // distinct per call too, so this suffix rides along with every one of them.
        val suffix = number.filter { it.isDigit() }.takeLast(4)
        return QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "$tag Sender GmbH"
                invoicePrefix = "$tag$suffix-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag$suffix.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-${number.hashCode()}")
                name = "$tag Empfänger AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
                iban = Iban.ofNullable("DE02120300000000202051")
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)

            val taxRate = TaxRate().edit {
                code = "${tag}_${number.hashCode()}_STD19"
                category = "S"
                name = "VAT 19"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
                validFrom = LocalDate.of(2024, 1, 1)
                isDefault = false
            }
            em.persist(taxRate)

            val invoice = anInvoice(status = InvoiceStatus.ISSUED).edit {
                senderProfile = sender
                this.recipient = recipient
                invoiceNumber = InvoiceNumber(number)
                invoiceDate = LocalDate.of(2026, 2, 10)
                dueDate = LocalDate.of(2026, 3, 12)
                references.buyerReference = "PO-$tag"
            }
            invoice.addInitialItems(
                listOf(
                    anInvoiceItem().edit {
                        positionId = "1"
                        category = InvoiceItemCategory.SERVICE
                        description = "Beratung"
                        quantity = BigDecimal.ONE
                        unit = InvoiceItemUnit.PIECE
                        unitPriceNet = Money(BigDecimal(netAmount))
                        taxRateRef = taxRate
                        sortOrder = 0
                    },
                ),
            )
            // Set directly rather than via recalculateTotals(): this test cares about the read
            // path round-tripping an exact figure, not about re-deriving one from a tax rate.
            invoice.edit { totals = InvoiceTotals(Money(BigDecimal(netAmount)), Money(BigDecimal(vatAmount)), Money(BigDecimal(grossAmount))) }

            em.persist(invoice)
            em.flush()
            invoice.id!!
        }
    }

    @Test
    fun `a four-digit gross amount survives the real read path, cold and cached, without precision loss`() {
        val tag = "AMT1"
        persistInvoice(tag, "$tag-2026-08-00001", "8403.36", "1596.64", "10000.00")

        val cold = QuarkusTransaction.requiringNew().call {
            overviewQueryService.findOverview(0, 10, tag, null, InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC)
        }
        assertEquals(1, cold.size, "the filterText must isolate exactly this test's own invoice")
        val coldAmount = cold.single().grossAmount
        assertEquals(0, BigDecimal("10000.00").compareTo(coldAmount?.amount), "the cold DB read must carry the exact amount")
        assertEquals("10.000,00 EUR", MoneyFormatter.format(coldAmount, cold.single().currency), "cold read: the exact string the widened grid column now fits")

        // Second call with identical parameters — same cache key, so this hits the Valkey overview
        // cache and deserializes through Jackson instead of querying Postgres again.
        val cached = QuarkusTransaction.requiringNew().call {
            overviewQueryService.findOverview(0, 10, tag, null, InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC)
        }
        val cachedAmount = cached.single().grossAmount
        assertEquals(0, BigDecimal("10000.00").compareTo(cachedAmount?.amount), "the Valkey/Jackson round-trip must not lose precision")
        assertEquals("10.000,00 EUR", MoneyFormatter.format(cachedAmount, cached.single().currency), "cached read: must still be the full, un-clipped string")
    }

    @Test
    fun `sorting by invoiceNumber returns real lexicographic DB order, not the removed 'Nr' column's silent date fallback`() {
        val tag = "SRT1"
        persistInvoice(tag, "$tag-2026-0002", "50.00", "9.50", "59.50")
        persistInvoice(tag, "$tag-2026-0001", "50.00", "9.50", "59.50")

        val ascending = QuarkusTransaction.requiringNew().call {
            overviewQueryService.findOverview(0, 10, tag, null, InvoiceOverviewSortField.INVOICE_NUMBER, SortDirection.ASC)
        }
        assertEquals(listOf("$tag-2026-0001", "$tag-2026-0002"), ascending.map { it.invoiceNumber })

        val descending = QuarkusTransaction.requiringNew().call {
            overviewQueryService.findOverview(0, 10, tag, null, InvoiceOverviewSortField.INVOICE_NUMBER, SortDirection.DESC)
        }
        assertEquals(listOf("$tag-2026-0002", "$tag-2026-0001"), descending.map { it.invoiceNumber })
    }
}
