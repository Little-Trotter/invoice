package de.littletrotter.application

import de.littletrotter.fixture.seededStandardTaxRate
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import de.littletrotter.domain.invoice.InvoiceTotals
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.common.QuarkusTestResource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber

/**
 * Z2 — split from `InvoiceOverviewQueryServiceIT` together with the statistics query methods
 * themselves (`getYearlyTotals`/`getMonthlyTotals`/... via the `v_stat_*` views); see
 * [InvoiceStatisticsQueryPort]'s KDoc for the split rationale. `StatisticsFilterIT` covers the
 * per-filter-dimension behavior in depth — this class just proves the aggregates come back with
 * real amounts through every method once.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceStatisticsQueryServiceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceStatisticsQueryService: InvoiceStatisticsQueryPort

    @Test
    @Transactional
    fun `statistics aggregates return real amounts via views`() {
        // prepare reference data in a far-future year so no other test's baseline data collides.
        // The prefix must be unique per test class -- "SS-" alone collided with
        // InvoiceDetailWriteBackIT, since uq_sender_profile_invoice_prefix applies table-wide.
        val seller = aSenderProfile { name = "Stat Seller"; invoicePrefix = "OV-SS-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = aRecipient { customerNumber = CustomerNumber("CUST-STAT"); name = "Stat Recipient" }
        em.persist(seller)
        em.persist(recipientEntity)

        val inv = Invoice().edit {
            invoiceNumber = InvoiceNumber("IT-2030-05-00042")
            selectedSeller = seller
            this.recipient = recipientEntity
            invoiceDate = LocalDate.of(2030, 5, 15)
            totals = InvoiceTotals(netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal("23.45")), grossAmount = Money(BigDecimal("123.45")))
            paymentTermsDays = 14
            addItem(InvoiceItem().edit {
                taxRateRef = em.seededStandardTaxRate()
                description = "Stat Item"
                unitPriceNet = Money(BigDecimal("100.00"))
                quantity = BigDecimal.ONE
                netAmount = Money(BigDecimal("100.00"))
                vatAmount = Money(BigDecimal("23.45"))
                grossAmount = Money(BigDecimal("123.45"))
            })
        }
        em.persist(inv)
        em.flush()
        em.clear()

        val yearly = invoiceStatisticsQueryService.getYearlyTotals()
        val year2030 = yearly.find { it.year == 2030 }
        assertNotNull(year2030)
        assertEquals(Money.of(BigDecimal("123.45")), year2030!!.grossAmount)
        assertEquals(Money.of(BigDecimal("100.00")), year2030.netAmount)
        assertEquals(Money.of(BigDecimal("23.45")), year2030.vatAmount)
        assertEquals(1L, year2030.invoiceCount)

        val monthly = invoiceStatisticsQueryService.getMonthlyTotals(2030)
        val may = monthly.find { it.month == 5 }
        assertNotNull(may)
        assertEquals(Money.of(BigDecimal("123.45")), may!!.grossAmount)
        assertEquals(1L, may.invoiceCount)

        val quarterly = invoiceStatisticsQueryService.getQuarterlyTotals(2030)
        val q2 = quarterly.find { it.quarter == 2 }
        assertNotNull(q2)
        assertEquals(Money.of(BigDecimal("123.45")), q2!!.grossAmount)

        val trend = invoiceStatisticsQueryService.getMonthlyTrend(2030)
        assertTrue(trend.any { it.month == 5 && it.grossAmount == Money.of(BigDecimal("123.45")) })

        val daily = invoiceStatisticsQueryService.getDailyTrend(2030, 5)
        assertTrue(daily.any { it.day == 15 && it.grossAmount == Money.of(BigDecimal("123.45")) })

        val recipients = invoiceStatisticsQueryService.getRecipientTotals(10)
        assertTrue(recipients.any { it.recipientName == "Stat Recipient" && it.grossAmount == Money.of(BigDecimal("123.45")) })

        val senders = invoiceStatisticsQueryService.getSenderTotals(10)
        assertTrue(senders.any { it.senderName == "Stat Seller" && it.grossAmount == Money.of(BigDecimal("123.45")) })

        val articles = invoiceStatisticsQueryService.getArticleTotals(10)
        assertTrue(articles.any { it.description == "Stat Item" && it.netAmount == Money.of(BigDecimal("100.00")) })

        val statusDistribution = invoiceStatisticsQueryService.getStatusDistribution()
        assertTrue(statusDistribution.any { it.status == InvoiceStatus.DRAFT && it.count >= 1L })

        val availableYears = invoiceStatisticsQueryService.getAvailableYears()
        assertTrue(availableYears.contains(2030))
    }
}
