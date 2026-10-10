package de.littletrotter.application

import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import de.littletrotter.application.dto.StatisticsFilter
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.Money
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber

/**
 * Integration tests verifying that all statistics query methods correctly apply
 * every filter dimension: year, sender, recipient, status, reverseCharge, text
 * (invoice_number / sender_name / recipient_name), month, and quarter.
 *
 * Each test uses a dedicated far-future year (2041–2047) so data committed by
 * one test never affects another test's assertions.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class StatisticsFilterIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var svc: InvoiceStatisticsQueryPort

    // ─── helpers ──────────────────────────────────────────────────────────────

    private fun makeSender(name: String, prefix: String): SenderProfile =
        SenderProfile().apply { this.name = name; invoicePrefix = prefix; registerCourt = "AG TestIT" }

    private fun makeRecipient(name: String, custNum: String): Recipient =
        Recipient().apply { this.name = name; customerNumber = CustomerNumber.ofNullable(custNum) }

    /**
     * @param number must match `PREFIX-YYYY-MM-NNNNN` (new) or `PREFIX-YYYY-NNNN` (legacy).
     *   Prefix is 1–10 alphanumeric characters (no dashes).
     */
    private fun makeInvoice(
        number: String,
        sender: SenderProfile,
        recipient: Recipient,
        date: LocalDate,
        net: String,
        vat: String,
        gross: String,
        status: InvoiceStatus = InvoiceStatus.DRAFT,
        reverseCharge: Boolean = false,
        itemDesc: String = "Test Service",
    ): Invoice = Invoice().apply {
        invoiceNumber = InvoiceNumber(number)
        selectedSeller = sender
        this.recipient = recipient
        invoiceDate = date
        totals.netAmount = Money(BigDecimal(net))
        totals.vatAmount = Money(BigDecimal(vat))
        totals.grossAmount = Money(BigDecimal(gross))
        paymentTermsDays = 14
        this.reverseCharge = reverseCharge
        this.forceStatusForTest(status)
        addItem(InvoiceItem().apply {
            description = itemDesc
            unitPriceNet = Money(BigDecimal(net))
            quantity = BigDecimal.ONE
            netAmount = Money(BigDecimal(net))
            vatAmount = Money(BigDecimal(vat))
            grossAmount = Money(BigDecimal(gross))
        })
    }

    // ─── test 1: name-based text filter at all trend aggregation levels ──────

    /**
     * The aggregated period views (v_stat_yearly/quarterly/monthly/daily) do not
     * carry invoice_number — text search on them matches sender_name / recipient_name.
     * This test proves that a name-based text filter is applied correctly at every
     * aggregation level (yearly → quarterly → monthly → trend → daily).
     *
     * Two invoices share one sender but have distinct recipients so the filter can
     * isolate one of them by recipient name.
     */
    @Test
    @Transactional
    fun `name-based text filter works at all trend aggregation levels`() {
        val sender = makeSender("TrendFilter Sender 2041", "TF41")
        val alpha = makeRecipient("Alpha Trend Recipient 2041", "CUST-TFA41")
        val beta = makeRecipient("Beta Trend Recipient 2041", "CUST-TFB41")
        em.persist(sender); em.persist(alpha); em.persist(beta)

        // Alpha: Q1 / March / day 15
        val invA = makeInvoice(
            "TFA41-2041-03-00001", sender, alpha, LocalDate.of(2041, 3, 15),
            "100.00", "19.00", "119.00",
        )
        // Beta: Q2 / June / day 10
        val invB = makeInvoice(
            "TFB41-2041-06-00001", sender, beta, LocalDate.of(2041, 6, 10),
            "200.00", "38.00", "238.00",
        )
        em.persist(invA); em.persist(invB)
        em.flush(); em.clear()

        // getYearlyTotals – "alpha trend recipient" matches only invA
        val yearly = svc.getYearlyTotals(StatisticsFilter(filterText = "alpha trend recipient"))
        val y2041 = yearly.find { it.year == 2041 }
        assertNotNull(y2041, "Year 2041 must be present when filtering by Alpha's recipient name")
        assertEquals(Money.of(BigDecimal("119.00")), y2041!!.grossAmount)
        assertEquals(1L, y2041.invoiceCount)

        // "beta trend recipient" matches only invB
        val yearlyOther = svc.getYearlyTotals(StatisticsFilter(filterText = "beta trend recipient"))
        val y2041Other = yearlyOther.find { it.year == 2041 }
        assertNotNull(y2041Other)
        assertEquals(Money.of(BigDecimal("238.00")), y2041Other!!.grossAmount)
        assertEquals(1L, y2041Other.invoiceCount)

        // getQuarterlyTotals – Alpha is in Q1 (March)
        val quarterly = svc.getQuarterlyTotals(2041, StatisticsFilter(filterText = "alpha trend recipient"))
        assertEquals(1, quarterly.size, "Only Q1 should match")
        assertEquals(1, quarterly[0].quarter)
        assertEquals(Money.of(BigDecimal("119.00")), quarterly[0].grossAmount)

        // getMonthlyTotals – only month 3
        val monthly = svc.getMonthlyTotals(2041, StatisticsFilter(filterText = "alpha trend recipient"))
        assertEquals(1, monthly.size, "Only month 3 should match")
        assertEquals(3, monthly[0].month)
        assertEquals(Money.of(BigDecimal("119.00")), monthly[0].grossAmount)

        // getMonthlyTrend – only month 3
        val trend = svc.getMonthlyTrend(2041, StatisticsFilter(filterText = "alpha trend recipient"))
        assertEquals(1, trend.size, "Trend must contain only month 3")
        assertEquals(3, trend[0].month)
        assertEquals(Money.of(BigDecimal("119.00")), trend[0].grossAmount)

        // getDailyTrend – only day 15 in month 3
        val daily = svc.getDailyTrend(2041, 3, StatisticsFilter(filterText = "alpha trend recipient"))
        assertEquals(1, daily.size, "Only day 15 must appear")
        assertEquals(15, daily[0].day)
        assertEquals(Money.of(BigDecimal("119.00")), daily[0].grossAmount)

        // Cross-check: Beta is in June → month 3 daily trend must be empty
        val dailyCross = svc.getDailyTrend(2041, 3, StatisticsFilter(filterText = "beta trend recipient"))
        assertTrue(dailyCross.isEmpty(), "Beta lives in June, month-3 daily must be empty")

        // Sender-name filter: both invoices share the same sender → both months visible
        val bySenderName = svc.getMonthlyTotals(2041, StatisticsFilter(filterText = "TrendFilter Sender 2041"))
        assertEquals(2, bySenderName.size, "Both months must appear when filtering by sender name")
    }

    // ─── test 2: status filter ────────────────────────────────────────────────

    @Test
    @Transactional
    fun `status filter works at all aggregation levels`() {
        val sender = makeSender("StatusFilter Sender 2042", "SF42")
        val recipient = makeRecipient("StatusFilter Recipient 2042", "CUST-SF42")
        em.persist(sender); em.persist(recipient)

        // DRAFT in Q1 (March), SENT in Q3 (September)
        val draft = makeInvoice(
            "SF42-2042-03-00001", sender, recipient, LocalDate.of(2042, 3, 10),
            "150.00", "28.50", "178.50", InvoiceStatus.DRAFT,
        )
        val finalized = makeInvoice(
            "SF42-2042-09-00001", sender, recipient, LocalDate.of(2042, 9, 20),
            "300.00", "57.00", "357.00", InvoiceStatus.SENT,
        )
        em.persist(draft); em.persist(finalized)
        em.flush(); em.clear()

        // Quarterly: SENT → only Q3
        val quarterlyFin = svc.getQuarterlyTotals(2042, StatisticsFilter(status = InvoiceStatus.SENT))
        assertEquals(1, quarterlyFin.size)
        assertEquals(3, quarterlyFin[0].quarter)
        assertEquals(Money.of(BigDecimal("357.00")), quarterlyFin[0].grossAmount)

        // Quarterly: DRAFT → only Q1
        val quarterlyDraft = svc.getQuarterlyTotals(2042, StatisticsFilter(status = InvoiceStatus.DRAFT))
        assertEquals(1, quarterlyDraft.size)
        assertEquals(1, quarterlyDraft[0].quarter)
        assertEquals(Money.of(BigDecimal("178.50")), quarterlyDraft[0].grossAmount)

        // Monthly: DRAFT → only month 3
        val monthlyDraft = svc.getMonthlyTotals(2042, StatisticsFilter(status = InvoiceStatus.DRAFT))
        assertEquals(1, monthlyDraft.size)
        assertEquals(3, monthlyDraft[0].month)

        // Monthly: SENT → only month 9
        val monthlyFin = svc.getMonthlyTotals(2042, StatisticsFilter(status = InvoiceStatus.SENT))
        assertEquals(1, monthlyFin.size)
        assertEquals(9, monthlyFin[0].month)

        // Monthly trend: SENT → only month 9
        val trendFin = svc.getMonthlyTrend(2042, StatisticsFilter(status = InvoiceStatus.SENT))
        assertEquals(1, trendFin.size)
        assertEquals(9, trendFin[0].month)

        // Daily: SENT in month 9 → day 20
        val dailyFin = svc.getDailyTrend(2042, 9, StatisticsFilter(status = InvoiceStatus.SENT))
        assertEquals(1, dailyFin.size)
        assertEquals(20, dailyFin[0].day)

        // Daily: DRAFT in month 9 → empty (DRAFT is only in March)
        val dailyDraftSep = svc.getDailyTrend(2042, 9, StatisticsFilter(status = InvoiceStatus.DRAFT))
        assertTrue(dailyDraftSep.isEmpty())

        // Yearly totals for SENT → inv2's amounts only
        val yearlyFin = svc.getYearlyTotals(StatisticsFilter(status = InvoiceStatus.SENT))
        val y2042 = yearlyFin.find { it.year == 2042 }
        assertNotNull(y2042)
        assertEquals(Money.of(BigDecimal("357.00")), y2042!!.grossAmount)
        assertEquals(1L, y2042.invoiceCount)
    }

    // ─── test 3: sender filter ────────────────────────────────────────────────

    @Test
    @Transactional
    fun `sender filter isolates invoices at all aggregation levels`() {
        val sender1 = makeSender("SenderFilter Alpha 2043", "SFA43")
        val sender2 = makeSender("SenderFilter Beta 2043", "SFB43")
        val recipient = makeRecipient("SenderFilter Recipient 2043", "CUST-SFR43")
        em.persist(sender1); em.persist(sender2); em.persist(recipient)

        // sender1: Q1 / March / day 5
        val invS1 = makeInvoice(
            "SFA43-2043-03-00001", sender1, recipient, LocalDate.of(2043, 3, 5),
            "120.00", "22.80", "142.80",
        )
        // sender2: Q2 / June / day 15
        val invS2 = makeInvoice(
            "SFB43-2043-06-00001", sender2, recipient, LocalDate.of(2043, 6, 15),
            "240.00", "45.60", "285.60",
        )
        em.persist(invS1); em.persist(invS2)
        em.flush(); em.clear()

        val s1Id = sender1.id

        // Monthly totals for sender1: only month 3
        val monthly = svc.getMonthlyTotals(2043, StatisticsFilter(senderId = s1Id))
        assertEquals(1, monthly.size)
        assertEquals(3, monthly[0].month)
        assertEquals(Money.of(BigDecimal("142.80")), monthly[0].grossAmount)

        // Monthly trend for sender1: only month 3
        val trend = svc.getMonthlyTrend(2043, StatisticsFilter(senderId = s1Id))
        assertEquals(1, trend.size)
        assertEquals(3, trend[0].month)

        // Daily for sender1 in month 3: only day 5
        val dailyS1 = svc.getDailyTrend(2043, 3, StatisticsFilter(senderId = s1Id))
        assertEquals(1, dailyS1.size)
        assertEquals(5, dailyS1[0].day)

        // Daily for sender2 in month 3: empty (sender2 is in June)
        val dailyS2Mar = svc.getDailyTrend(2043, 3, StatisticsFilter(senderId = sender2.id))
        assertTrue(dailyS2Mar.isEmpty())

        // Recipient totals filtered by sender1 and year
        val recipTotals = svc.getRecipientTotals(10, StatisticsFilter(senderId = s1Id, year = 2043))
        assertEquals(1, recipTotals.size)
        assertEquals(Money.of(BigDecimal("142.80")), recipTotals[0].grossAmount)

        // Yearly totals for sender1 only
        val yearly = svc.getYearlyTotals(StatisticsFilter(senderId = s1Id))
        val y2043 = yearly.find { it.year == 2043 }
        assertNotNull(y2043)
        assertEquals(1L, y2043!!.invoiceCount)
        assertEquals(Money.of(BigDecimal("142.80")), y2043.grossAmount)

        // Quarterly totals for sender2 in 2043: only Q2 (June)
        val quarterlyS2 = svc.getQuarterlyTotals(2043, StatisticsFilter(senderId = sender2.id))
        assertEquals(1, quarterlyS2.size)
        assertEquals(2, quarterlyS2[0].quarter)
        assertEquals(Money.of(BigDecimal("285.60")), quarterlyS2[0].grossAmount)
    }

    // ─── test 4: status distribution with month and quarter filters ───────────

    @Test
    @Transactional
    fun `status distribution is filtered by month and quarter`() {
        val sender = makeSender("StatusDist Sender 2044", "SD44")
        val recipient = makeRecipient("StatusDist Recipient 2044", "CUST-SD44")
        em.persist(sender); em.persist(recipient)

        // One invoice per quarter, each with a distinct status
        val draft = makeInvoice(
            "SD44-2044-03-00001", sender, recipient, LocalDate.of(2044, 3, 10),
            "100.00", "19.00", "119.00", InvoiceStatus.DRAFT,
        )
        val review = makeInvoice(
            "SD44-2044-06-00001", sender, recipient, LocalDate.of(2044, 6, 5),
            "200.00", "38.00", "238.00", InvoiceStatus.IN_REVIEW,
        )
        val final = makeInvoice(
            "SD44-2044-09-00001", sender, recipient, LocalDate.of(2044, 9, 15),
            "300.00", "57.00", "357.00", InvoiceStatus.SENT,
        )
        em.persist(draft); em.persist(review); em.persist(final)
        em.flush(); em.clear()

        // Month 3 → only DRAFT
        val distM3 = svc.getStatusDistribution(StatisticsFilter(year = 2044, month = 3))
        assertEquals(1, distM3.size)
        assertEquals(InvoiceStatus.DRAFT, distM3[0].status)
        assertEquals(1L, distM3[0].count)

        // Month 6 → only IN_REVIEW
        val distM6 = svc.getStatusDistribution(StatisticsFilter(year = 2044, month = 6))
        assertEquals(1, distM6.size)
        assertEquals(InvoiceStatus.IN_REVIEW, distM6[0].status)

        // Month 9 → only SENT
        val distM9 = svc.getStatusDistribution(StatisticsFilter(year = 2044, month = 9))
        assertEquals(1, distM9.size)
        assertEquals(InvoiceStatus.SENT, distM9[0].status)

        // Quarter 1 (Jan–Mar) → only DRAFT
        val distQ1 = svc.getStatusDistribution(StatisticsFilter(year = 2044, quarter = 1))
        assertEquals(1, distQ1.size)
        assertEquals(InvoiceStatus.DRAFT, distQ1[0].status)

        // Quarter 2 (Apr–Jun) → only IN_REVIEW
        val distQ2 = svc.getStatusDistribution(StatisticsFilter(year = 2044, quarter = 2))
        assertEquals(1, distQ2.size)
        assertEquals(InvoiceStatus.IN_REVIEW, distQ2[0].status)

        // Quarter 3 (Jul–Sep) → only SENT
        val distQ3 = svc.getStatusDistribution(StatisticsFilter(year = 2044, quarter = 3))
        assertEquals(1, distQ3.size)
        assertEquals(InvoiceStatus.SENT, distQ3[0].status)

        // Quarter 4 (Oct–Dec) → empty for 2044
        val distQ4 = svc.getStatusDistribution(StatisticsFilter(year = 2044, quarter = 4))
        assertTrue(distQ4.isEmpty())

        // No time sub-filter → all three statuses for 2044
        val distAll = svc.getStatusDistribution(StatisticsFilter(year = 2044))
        assertEquals(3, distAll.size)
        assertTrue(distAll.any { it.status == InvoiceStatus.DRAFT })
        assertTrue(distAll.any { it.status == InvoiceStatus.IN_REVIEW })
        assertTrue(distAll.any { it.status == InvoiceStatus.SENT })
    }

    // ─── test 5: recipient / sender / article rankings ────────────────────────

    @Test
    @Transactional
    fun `recipient and sender rankings filter correctly by year, sender, recipient, and text`() {
        val sender1 = makeSender("RankSender Alpha 2045", "RS1A45")
        val sender2 = makeSender("RankSender Beta 2045", "RS2B45")
        val recipient1 = makeRecipient("RankRecipient Alpha 2045", "CUST-RR1-45")
        val recipient2 = makeRecipient("RankRecipient Beta 2045", "CUST-RR2-45")
        em.persist(sender1); em.persist(sender2)
        em.persist(recipient1); em.persist(recipient2)

        // RNKA prefix → sender1→recipient1 in Q2
        val inv1 = makeInvoice(
            "RNKA-2045-04-00001", sender1, recipient1, LocalDate.of(2045, 4, 10),
            "500.00", "95.00", "595.00", itemDesc = "Rank Service Alpha",
        )
        // RNKB prefix → sender2→recipient2 in Q3
        val inv2 = makeInvoice(
            "RNKB-2045-07-00001", sender2, recipient2, LocalDate.of(2045, 7, 20),
            "800.00", "152.00", "952.00", itemDesc = "Rank Service Beta",
        )
        em.persist(inv1); em.persist(inv2)
        em.flush(); em.clear()

        // Recipient totals filtered by sender1 for 2045 → only recipient1
        val recipBySender1 = svc.getRecipientTotals(10, StatisticsFilter(senderId = sender1.id, year = 2045))
        assertEquals(1, recipBySender1.size)
        assertTrue(recipBySender1[0].recipientName.contains("Alpha"))
        assertEquals(Money.of(BigDecimal("595.00")), recipBySender1[0].grossAmount)
        assertEquals(1L, recipBySender1[0].invoiceCount)

        // Recipient totals filtered by sender2 for 2045 → only recipient2
        val recipBySender2 = svc.getRecipientTotals(10, StatisticsFilter(senderId = sender2.id, year = 2045))
        assertEquals(1, recipBySender2.size)
        assertTrue(recipBySender2[0].recipientName.contains("Beta"))

        // Sender totals filtered by recipient2 for 2045 → only sender2
        val senderByRecip2 = svc.getSenderTotals(10, StatisticsFilter(recipientId = recipient2.id, year = 2045))
        assertEquals(1, senderByRecip2.size)
        assertTrue(senderByRecip2[0].senderName.contains("Beta"))
        assertEquals(Money.of(BigDecimal("952.00")), senderByRecip2[0].grossAmount)

        // Sender totals filtered by recipient1 for 2045 → only sender1
        val senderByRecip1 = svc.getSenderTotals(10, StatisticsFilter(recipientId = recipient1.id, year = 2045))
        assertEquals(1, senderByRecip1.size)
        assertTrue(senderByRecip1[0].senderName.contains("Alpha"))

        // Text filter on invoice_number prefix "rnka" → only inv1's recipient appears
        val recipByText = svc.getRecipientTotals(10, StatisticsFilter(filterText = "rnka", year = 2045))
        assertEquals(1, recipByText.size)
        assertEquals(Money.of(BigDecimal("595.00")), recipByText[0].grossAmount)

        // Text filter "rnkb" → only inv2's sender appears
        val senderByText = svc.getSenderTotals(10, StatisticsFilter(filterText = "rnkb", year = 2045))
        assertEquals(1, senderByText.size)
        assertEquals(Money.of(BigDecimal("952.00")), senderByText[0].grossAmount)

        // Article totals: "rnka" in invoice_number isolates only inv1's item
        val articleAlpha = svc.getArticleTotals(10, StatisticsFilter(filterText = "rnka", year = 2045))
        assertEquals(1, articleAlpha.size)
        assertEquals("Rank Service Alpha", articleAlpha[0].description)
        assertEquals(Money.of(BigDecimal("500.00")), articleAlpha[0].netAmount)

        // Article totals: "rnkb" → inv2's item only
        val articleBeta = svc.getArticleTotals(10, StatisticsFilter(filterText = "rnkb", year = 2045))
        assertEquals(1, articleBeta.size)
        assertEquals("Rank Service Beta", articleBeta[0].description)
        assertEquals(Money.of(BigDecimal("800.00")), articleBeta[0].netAmount)

        // Recipient-name text filter: "RankRecipient Alpha 2045" → only recipient1
        val recipByName = svc.getRecipientTotals(10, StatisticsFilter(filterText = "RankRecipient Alpha 2045", year = 2045))
        assertEquals(1, recipByName.size)
        assertEquals(Money.of(BigDecimal("595.00")), recipByName[0].grossAmount)
    }

    // ─── test 6: reverseCharge filter ────────────────────────────────────────

    @Test
    @Transactional
    fun `reverseCharge filter works for statistics queries`() {
        val sender = makeSender("RC Stats Sender 2046", "RCS46")
        val recipient = makeRecipient("RC Stats Recipient 2046", "CUST-RCS46")
        em.persist(sender); em.persist(recipient)

        val rcInv = makeInvoice(
            "RCS46-2046-05-00001", sender, recipient, LocalDate.of(2046, 5, 10),
            "1000.00", "0.00", "1000.00", reverseCharge = true,
        )
        val normalInv = makeInvoice(
            "RCS46-2046-08-00001", sender, recipient, LocalDate.of(2046, 8, 20),
            "500.00", "95.00", "595.00", reverseCharge = false,
        )
        em.persist(rcInv); em.persist(normalInv)
        em.flush(); em.clear()

        // Yearly totals reverseCharge=true: only rcInv (May / Q2)
        val yearlyRc = svc.getYearlyTotals(StatisticsFilter(reverseCharge = true))
        val y2046Rc = yearlyRc.find { it.year == 2046 }
        assertNotNull(y2046Rc)
        assertEquals(1L, y2046Rc!!.invoiceCount)
        assertEquals(Money.of(BigDecimal("1000.00")), y2046Rc.grossAmount)

        // Yearly totals reverseCharge=false: only normalInv (August / Q3)
        val yearlyNormal = svc.getYearlyTotals(StatisticsFilter(reverseCharge = false))
        val y2046Normal = yearlyNormal.find { it.year == 2046 }
        assertNotNull(y2046Normal)
        assertEquals(1L, y2046Normal!!.invoiceCount)
        assertEquals(Money.of(BigDecimal("595.00")), y2046Normal.grossAmount)

        // Quarterly totals reverseCharge=true: only Q2 (May)
        val quarterlyRc = svc.getQuarterlyTotals(2046, StatisticsFilter(reverseCharge = true))
        assertEquals(1, quarterlyRc.size)
        assertEquals(2, quarterlyRc[0].quarter)

        // Monthly totals reverseCharge=false: only month 8
        val monthlyNormal = svc.getMonthlyTotals(2046, StatisticsFilter(reverseCharge = false))
        assertEquals(1, monthlyNormal.size)
        assertEquals(8, monthlyNormal[0].month)

        // Status distribution reverseCharge=true: only rcInv (1 DRAFT)
        val distRc = svc.getStatusDistribution(StatisticsFilter(year = 2046, reverseCharge = true))
        assertEquals(1, distRc.size)
        assertEquals(1L, distRc[0].count)
    }

    // ─── test 7: combined filters ─────────────────────────────────────────────

    @Test
    @Transactional
    fun `combined sender plus status plus text filters all applied simultaneously`() {
        val sender1 = makeSender("Combined Sender A 2047", "CSA47")
        val sender2 = makeSender("Combined Sender B 2047", "CSB47")
        // Distinct recipients so a name-based text filter can isolate inv1 from inv2.
        val alpha = makeRecipient("Combined Alpha Recipient 2047", "CUST-CBA47")
        val beta = makeRecipient("Combined Beta Recipient 2047", "CUST-CBB47")
        em.persist(sender1); em.persist(sender2); em.persist(alpha); em.persist(beta)

        // sender1 / Alpha / DRAFT / March
        val inv1 = makeInvoice(
            "CSDA-2047-03-00001", sender1, alpha, LocalDate.of(2047, 3, 12),
            "200.00", "38.00", "238.00", InvoiceStatus.DRAFT,
        )
        // sender1 / Beta / SENT / June
        val inv2 = makeInvoice(
            "CSAF-2047-06-00001", sender1, beta, LocalDate.of(2047, 6, 8),
            "400.00", "76.00", "476.00", InvoiceStatus.SENT,
        )
        // sender2 / Alpha / DRAFT / March (same month as inv1, different sender)
        val inv3 = makeInvoice(
            "CSDB-2047-03-00001", sender2, alpha, LocalDate.of(2047, 3, 25),
            "150.00", "28.50", "178.50", InvoiceStatus.DRAFT,
        )
        em.persist(inv1); em.persist(inv2); em.persist(inv3)
        em.flush(); em.clear()

        val s1Id = sender1.id

        // sender1 + DRAFT → only inv1 (March)
        val monthlyS1Draft = svc.getMonthlyTotals(2047, StatisticsFilter(senderId = s1Id, status = InvoiceStatus.DRAFT))
        assertEquals(1, monthlyS1Draft.size)
        assertEquals(3, monthlyS1Draft[0].month)
        assertEquals(Money.of(BigDecimal("238.00")), monthlyS1Draft[0].grossAmount)

        // sender1 + SENT → only inv2 (June)
        val monthlyS1Final = svc.getMonthlyTotals(2047, StatisticsFilter(senderId = s1Id, status = InvoiceStatus.SENT))
        assertEquals(1, monthlyS1Final.size)
        assertEquals(6, monthlyS1Final[0].month)

        // sender1 + recipient-name text "alpha" → only inv1 (inv2 is Beta)
        val monthlyS1Text = svc.getMonthlyTotals(2047, StatisticsFilter(senderId = s1Id, filterText = "combined alpha recipient"))
        assertEquals(1, monthlyS1Text.size)
        assertEquals(3, monthlyS1Text[0].month)
        assertEquals(Money.of(BigDecimal("238.00")), monthlyS1Text[0].grossAmount)

        // sender1 + DRAFT + text "alpha" → still only inv1
        val monthlyTriple = svc.getMonthlyTotals(
            2047, StatisticsFilter(senderId = s1Id, status = InvoiceStatus.DRAFT, filterText = "combined alpha recipient")
        )
        assertEquals(1, monthlyTriple.size)
        assertEquals(Money.of(BigDecimal("238.00")), monthlyTriple[0].grossAmount)

        // sender1 alone → two months (March + June)
        val monthlyS1All = svc.getMonthlyTotals(2047, StatisticsFilter(senderId = s1Id))
        assertEquals(2, monthlyS1All.size)

        // DRAFT alone in 2047 → month 3 is summed across both DRAFT invoices (inv1 + inv3)
        val monthlyDraftAll = svc.getMonthlyTotals(2047, StatisticsFilter(status = InvoiceStatus.DRAFT))
        val march = monthlyDraftAll.find { it.month == 3 }
        assertNotNull(march)
        assertEquals(
            Money.of(BigDecimal("416.50")), march!!.grossAmount,
            "inv1(238.00) + inv3(178.50) = 416.50"
        )

        // Status distribution: sender1 + year 2047 → DRAFT and SENT only
        val distS1 = svc.getStatusDistribution(StatisticsFilter(senderId = s1Id, year = 2047))
        assertEquals(2, distS1.size)
        assertTrue(distS1.any { it.status == InvoiceStatus.DRAFT && it.count == 1L })
        assertTrue(distS1.any { it.status == InvoiceStatus.SENT && it.count == 1L })

        // Status distribution: sender1 + year 2047 + quarter 1 → only DRAFT
        val distS1Q1 = svc.getStatusDistribution(StatisticsFilter(senderId = s1Id, year = 2047, quarter = 1))
        assertEquals(1, distS1Q1.size)
        assertEquals(InvoiceStatus.DRAFT, distS1Q1[0].status)
    }

    // ─── test 9: getAvailableYears ────────────────────────────────────────────

    @Test
    @Transactional
    fun `getAvailableYears returns distinct invoice years in descending order`() {
        val sender = makeSender("Years Sender 2049", "YRS49")
        val recipient = makeRecipient("Years Recipient 2049", "CUST-YRS49")
        em.persist(sender); em.persist(recipient)

        em.persist(makeInvoice("YRS49-2049-06-00001", sender, recipient, LocalDate.of(2049, 6, 1), "100.00", "19.00", "119.00"))
        em.persist(makeInvoice("YRS49-2050-03-00001", sender, recipient, LocalDate.of(2050, 3, 1), "200.00", "38.00", "238.00"))
        em.persist(makeInvoice("YRS49-2050-09-00001", sender, recipient, LocalDate.of(2050, 9, 1), "300.00", "57.00", "357.00"))
        em.flush(); em.clear()

        val years = svc.getAvailableYears()
        assertTrue(years.containsAll(listOf(2049, 2050)), "Both test years must appear")

        // 2050 must come before 2049 (descending)
        val idx2050 = years.indexOf(2050)
        val idx2049 = years.indexOf(2049)
        assertTrue(idx2050 < idx2049, "Years must be ordered descending (2050 before 2049)")

        // No duplicates for 2050 (two invoices in same year)
        assertEquals(1, years.count { it == 2050 }, "2050 must appear exactly once")
    }

    @Test
    @Transactional
    fun `getAvailableYears is restricted to years with an invoice for the given sender or recipient`() {
        val targetSender = makeSender("Years Sender 2052", "YRS52")
        val otherSender = makeSender("Years Other Sender 2052", "YRO52")
        val targetRecipient = makeRecipient("Years Recipient 2052", "CUST-YRT52")
        val otherRecipient = makeRecipient("Years Other Recipient 2052", "CUST-YRO52")
        em.persist(targetSender); em.persist(otherSender)
        em.persist(targetRecipient); em.persist(otherRecipient)

        // Only the target sender/recipient has an invoice in 2052; the other pair only in 2053.
        em.persist(makeInvoice("YRS52-2052-01-00001", targetSender, targetRecipient, LocalDate.of(2052, 1, 1), "100.00", "19.00", "119.00"))
        em.persist(makeInvoice("YRO52-2053-01-00001", otherSender, otherRecipient, LocalDate.of(2053, 1, 1), "100.00", "19.00", "119.00"))
        em.flush(); em.clear()

        val bySender = svc.getAvailableYears(StatisticsFilter(senderId = targetSender.id))
        assertEquals(listOf(2052), bySender, "senderId must restrict to years that sender actually invoiced in")

        val byRecipient = svc.getAvailableYears(StatisticsFilter(recipientId = targetRecipient.id))
        assertEquals(listOf(2052), byRecipient, "recipientId must restrict to years that recipient was actually invoiced in")
    }

    // ─── test 10: recipient/sender/article rankings respect month & quarter ───

    @Test
    @Transactional
    fun `recipient, sender and article rankings are filtered by month and quarter`() {
        val sender1 = makeSender("RankPeriod Sender Jan 2051", "RPSJ51")
        val sender2 = makeSender("RankPeriod Sender Jul 2051", "RPSU51")
        val recipient1 = makeRecipient("RankPeriod Recipient Jan 2051", "CUST-RPJ51")
        val recipient2 = makeRecipient("RankPeriod Recipient Jul 2051", "CUST-RPU51")
        em.persist(sender1); em.persist(sender2)
        em.persist(recipient1); em.persist(recipient2)

        // January invoice (month 1, quarter 1)
        val invJan = makeInvoice(
            "RPJ51-2051-01-00001", sender1, recipient1, LocalDate.of(2051, 1, 15),
            "500.00", "95.00", "595.00", itemDesc = "Period Ranking Item Jan",
        )
        // July invoice (month 7, quarter 3)
        val invJul = makeInvoice(
            "RPU51-2051-07-00001", sender2, recipient2, LocalDate.of(2051, 7, 15),
            "800.00", "152.00", "952.00", itemDesc = "Period Ranking Item Jul",
        )
        em.persist(invJan); em.persist(invJul)
        em.flush(); em.clear()

        // Year-only → both recipients/senders/articles appear
        assertEquals(2, svc.getRecipientTotals(10, StatisticsFilter(year = 2051)).size)
        assertEquals(2, svc.getSenderTotals(10, StatisticsFilter(year = 2051)).size)
        assertEquals(2, svc.getArticleTotals(10, StatisticsFilter(year = 2051)).size)

        // Month filter isolates the January invoice only
        val recipJan = svc.getRecipientTotals(10, StatisticsFilter(year = 2051, month = 1))
        assertEquals(1, recipJan.size)
        assertTrue(recipJan[0].recipientName.contains("Jan"))
        assertEquals(Money.of(BigDecimal("595.00")), recipJan[0].grossAmount)
        assertEquals(1L, recipJan[0].invoiceCount)

        val senderJan = svc.getSenderTotals(10, StatisticsFilter(year = 2051, month = 1))
        assertEquals(1, senderJan.size)
        assertTrue(senderJan[0].senderName.contains("Jan"))

        val articleJan = svc.getArticleTotals(10, StatisticsFilter(year = 2051, month = 1))
        assertEquals(1, articleJan.size)
        assertEquals("Period Ranking Item Jan", articleJan[0].description)
        assertEquals(Money.of(BigDecimal("500.00")), articleJan[0].netAmount)

        // Quarter filter isolates the July invoice only (Q3 = Jul–Sep)
        val recipQ3 = svc.getRecipientTotals(10, StatisticsFilter(year = 2051, quarter = 3))
        assertEquals(1, recipQ3.size)
        assertTrue(recipQ3[0].recipientName.contains("Jul"))
        assertEquals(Money.of(BigDecimal("952.00")), recipQ3[0].grossAmount)

        val senderQ3 = svc.getSenderTotals(10, StatisticsFilter(year = 2051, quarter = 3))
        assertEquals(1, senderQ3.size)
        assertTrue(senderQ3[0].senderName.contains("Jul"))

        val articleQ3 = svc.getArticleTotals(10, StatisticsFilter(year = 2051, quarter = 3))
        assertEquals(1, articleQ3.size)
        assertEquals("Period Ranking Item Jul", articleQ3[0].description)

        // A quarter with no data for this year returns nothing
        assertTrue(svc.getRecipientTotals(10, StatisticsFilter(year = 2051, quarter = 2)).isEmpty())

        // Month filter combined with a text filter still narrows correctly
        // (exercises the flat-view fallback path used whenever filterText is set)
        val recipJanText = svc.getRecipientTotals(10, StatisticsFilter(year = 2051, month = 1, filterText = "rpj51"))
        assertEquals(1, recipJanText.size)
        assertEquals(Money.of(BigDecimal("595.00")), recipJanText[0].grossAmount)
    }

    // ─── test 11: attack-shaped filterText stays literal — injection regression ──
    // (PO-Entscheid 29.08.2026) — every statistics query used to build its WHERE clause from a
    // native SQL string (StatsWhereClauseBuilder). Criteria replaces it: filterText can only ever
    // reach the database as a bound LIKE parameter, never as SQL text, so these attack shapes must
    // behave exactly like any other non-matching search term — no error, no widened result set.

    @Test
    @Transactional
    fun `attack-shaped filterText stays literal match text across every aggregation level`() {
        val sender = makeSender("Attack Sender 2052", "ATK52")
        val recipient = makeRecipient("Attack Recipient 2052", "CUST-ATK52")
        em.persist(sender); em.persist(recipient)

        val inv = makeInvoice(
            "ATK52-2052-04-00001", sender, recipient, LocalDate.of(2052, 4, 10),
            "100.00", "19.00", "119.00", itemDesc = "Attack Item",
        )
        em.persist(inv)
        em.flush(); em.clear()

        val attackInputs = listOf(
            "x' OR '1'='1",
            "'; DROP TABLE invoice; --",
            "\\",
            "attack' OR sender_id IS NOT NULL --",
        )

        for (attack in attackInputs) {
            assertTrue(
                svc.getYearlyTotals(StatisticsFilter(filterText = attack)).none { it.year == 2052 },
                "getYearlyTotals must not match on attack input: $attack",
            )
            assertTrue(
                svc.getMonthlyTotals(2052, StatisticsFilter(filterText = attack)).isEmpty(),
                "getMonthlyTotals must not match on attack input: $attack",
            )
            assertTrue(
                svc.getRecipientTotals(10, StatisticsFilter(year = 2052, filterText = attack)).isEmpty(),
                "getRecipientTotals must not match on attack input: $attack",
            )
            assertTrue(
                svc.getSenderTotals(10, StatisticsFilter(year = 2052, filterText = attack)).isEmpty(),
                "getSenderTotals must not match on attack input: $attack",
            )
            assertTrue(
                svc.getArticleTotals(10, StatisticsFilter(year = 2052, filterText = attack)).isEmpty(),
                "getArticleTotals must not match on attack input: $attack",
            )
        }

        // Sanity check: the real filter text still finds the fixture (proves the attack inputs
        // above failed to match for lack of relevance, not because the query itself is broken).
        assertEquals(1, svc.getRecipientTotals(10, StatisticsFilter(year = 2052, filterText = "attack recipient")).size)
    }

    // ─── statsPredicates' status dimension on the ranking methods,
    //     and getAvailableYears with both senderId and recipientId set together ──

    /**
     * `filter.status` is exercised against period/trend/distribution queries elsewhere in this
     * class, but never against the three ranking methods (`getRecipientTotals`/`getSenderTotals`/
     * `getArticleTotals`), which route through the very same `statsPredicates` — closes that gap
     * directly instead of relying on branch coverage incidentally picked up by an unrelated method.
     */
    @Test
    @Transactional
    fun `boundary - status filter narrows recipient, sender and article rankings, not just period totals`() {
        val sender = makeSender("Status Rank Sender 2058", "SRK58")
        val recipientDraft = makeRecipient("Status Rank Draft Recipient 2058", "CUST-SRD58")
        val recipientSent = makeRecipient("Status Rank Sent Recipient 2058", "CUST-SRS58")
        em.persist(sender); em.persist(recipientDraft); em.persist(recipientSent)

        val draftInv = makeInvoice(
            "SRK58-2058-02-00001", sender, recipientDraft, LocalDate.of(2058, 2, 10),
            "300.00", "57.00", "357.00", InvoiceStatus.DRAFT, itemDesc = "Status Rank Draft Item",
        )
        val sentInv = makeInvoice(
            "SRK58-2058-04-00001", sender, recipientSent, LocalDate.of(2058, 4, 10),
            "700.00", "133.00", "833.00", InvoiceStatus.SENT, itemDesc = "Status Rank Sent Item",
        )
        em.persist(draftInv); em.persist(sentInv)
        em.flush(); em.clear()

        val recipientsDraft = svc.getRecipientTotals(10, StatisticsFilter(year = 2058, status = InvoiceStatus.DRAFT))
        assertEquals(1, recipientsDraft.size)
        assertTrue(recipientsDraft[0].recipientName.contains("Draft"))
        assertEquals(Money.of(BigDecimal("357.00")), recipientsDraft[0].grossAmount)

        val sendersSent = svc.getSenderTotals(10, StatisticsFilter(year = 2058, status = InvoiceStatus.SENT))
        assertEquals(1, sendersSent.size)
        assertEquals(Money.of(BigDecimal("833.00")), sendersSent[0].grossAmount)

        val articlesDraft = svc.getArticleTotals(10, StatisticsFilter(year = 2058, status = InvoiceStatus.DRAFT))
        assertEquals(1, articlesDraft.size)
        assertEquals("Status Rank Draft Item", articlesDraft[0].description)
    }

    /** Both `senderId` and `recipientId` were only ever tested individually against
     *  `getAvailableYears` elsewhere in this class — a real invoice matching one but not the other
     *  proves both predicates apply together (AND), not either-or. */
    @Test
    @Transactional
    fun `boundary - getAvailableYears applies senderId and recipientId together, not either-or`() {
        val targetSender = makeSender("Years Both Sender 2059", "YB59S")
        val otherSender = makeSender("Years Both Other Sender 2059", "YB59O")
        val targetRecipient = makeRecipient("Years Both Recipient 2059", "CUST-YB59T")
        val otherRecipient = makeRecipient("Years Both Other Recipient 2059", "CUST-YB59R")
        em.persist(targetSender); em.persist(otherSender)
        em.persist(targetRecipient); em.persist(otherRecipient)

        // Matches senderId but not recipientId -> must NOT appear when both are required.
        em.persist(makeInvoice("YB59S-2059-01-00001", targetSender, otherRecipient, LocalDate.of(2059, 1, 1), "100.00", "19.00", "119.00"))
        // Matches recipientId but not senderId -> must NOT appear when both are required.
        em.persist(makeInvoice("YB59O-2060-01-00001", otherSender, targetRecipient, LocalDate.of(2060, 1, 1), "100.00", "19.00", "119.00"))
        // Matches both -> the only year that must appear when both filters are combined.
        em.persist(makeInvoice("YB59S-2061-01-00001", targetSender, targetRecipient, LocalDate.of(2061, 1, 1), "100.00", "19.00", "119.00"))
        em.flush(); em.clear()

        val years = svc.getAvailableYears(StatisticsFilter(senderId = targetSender.id, recipientId = targetRecipient.id))

        assertEquals(listOf(2061), years, "only the year with an invoice matching both sender and recipient must appear")
    }

    // ─── reverseCharge interacting with filterText, and reverseCharge on the trend
    //     methods (getMonthlyTrend/getDailyTrend never exercised it before) ───────────────

    /**
     * `reverseCharge` and `filterText` were only ever exercised individually against these
     * queries elsewhere in this class; a real fixture where each predicate alone would admit a
     * *different* invoice proves both apply together (AND), not either-or — the same risk
     * `combined sender plus status plus text filters` already covers for senderId/status/text.
     */
    @Test
    @Transactional
    fun `boundary - reverseCharge combined with filterText narrows to the invoice matching both`() {
        val sender = makeSender("RC Text Sender 2060", "RCT60")
        val rcRecipient = makeRecipient("RC Text Alpha Recipient 2060", "CUST-RCTA60")
        val normalRecipient = makeRecipient("RC Text Beta Recipient 2060", "CUST-RCTB60")
        em.persist(sender); em.persist(rcRecipient); em.persist(normalRecipient)

        // reverseCharge=true but recipient name does NOT match "alpha" -> must be excluded once
        // filterText is also applied.
        val rcOtherText = makeInvoice(
            "RCT60-2060-02-00001", sender, normalRecipient, LocalDate.of(2060, 2, 10),
            "100.00", "0.00", "100.00", reverseCharge = true,
        )
        // filterText "alpha" matches, but reverseCharge=false -> must be excluded once
        // reverseCharge=true is also required.
        val textOnlyRc = makeInvoice(
            "RCT60-2060-05-00001", sender, rcRecipient, LocalDate.of(2060, 5, 10),
            "200.00", "38.00", "238.00", reverseCharge = false,
        )
        // Matches both -> the only invoice that must survive the combined filter.
        val both = makeInvoice(
            "RCT60-2060-08-00001", sender, rcRecipient, LocalDate.of(2060, 8, 10),
            "300.00", "0.00", "300.00", reverseCharge = true,
        )
        em.persist(rcOtherText); em.persist(textOnlyRc); em.persist(both)
        em.flush(); em.clear()

        val combinedFilter = StatisticsFilter(reverseCharge = true, filterText = "rc text alpha recipient")

        val yearly = svc.getYearlyTotals(combinedFilter).find { it.year == 2060 }
        assertNotNull(yearly, "combined reverseCharge+filterText must still find the one matching invoice")
        assertEquals(1L, yearly!!.invoiceCount)
        assertEquals(Money.of(BigDecimal("300.00")), yearly.grossAmount)

        val monthly = svc.getMonthlyTotals(2060, combinedFilter)
        assertEquals(1, monthly.size)
        assertEquals(8, monthly[0].month)

        // getMonthlyTrend/getDailyTrend never carried a reverseCharge assertion before this test.
        val trend = svc.getMonthlyTrend(2060, combinedFilter)
        assertEquals(1, trend.size)
        assertEquals(8, trend[0].month)

        val daily = svc.getDailyTrend(2060, 8, combinedFilter)
        assertEquals(1, daily.size)
        assertEquals(10, daily[0].day)

        // reverseCharge alone (no text) still finds both RC invoices (rcOtherText + both).
        val yearlyRcOnly = svc.getYearlyTotals(StatisticsFilter(reverseCharge = true)).find { it.year == 2060 }
        assertNotNull(yearlyRcOnly)
        assertEquals(2L, yearlyRcOnly!!.invoiceCount)
    }

    /**
     * `!filterText.isNullOrBlank()` short-circuits to `false` both for a `null` filterText (the
     * common case, exercised throughout this class) AND for a present-but-blank one — a caller
     * that trims a text field to `"   "` before disabling its own search (rather than nulling it
     * out) must not have that whitespace treated as a real search term. Exercises the same guard
     * in `statsPredicates` (period/trend queries) AND `invoiceRankingSource`/`itemRankingSource`
     * (rankings) — a blank filterText must behave exactly like no filterText in both places.
     */
    @Test
    @Transactional
    fun `boundary - a present but blank filterText is treated the same as no filterText at all`() {
        val sender = makeSender("Blank Text Sender 2062", "BLT62")
        val recipient = makeRecipient("Blank Text Recipient 2062", "CUST-BLT62")
        em.persist(sender); em.persist(recipient)

        val inv = makeInvoice(
            "BLT62-2062-03-00001", sender, recipient, LocalDate.of(2062, 3, 10),
            "100.00", "19.00", "119.00", itemDesc = "Blank Text Item",
        )
        em.persist(inv)
        em.flush(); em.clear()

        val noFilter = StatisticsFilter()
        val blankFilter = StatisticsFilter(filterText = "   ")

        val yearlyNoFilter = svc.getYearlyTotals(noFilter).find { it.year == 2062 }
        val yearlyBlank = svc.getYearlyTotals(blankFilter).find { it.year == 2062 }
        assertNotNull(yearlyBlank, "a blank filterText must not narrow getYearlyTotals at all")
        assertEquals(yearlyNoFilter!!.grossAmount, yearlyBlank!!.grossAmount)

        val recipientsNoFilter = svc.getRecipientTotals(10, StatisticsFilter(year = 2062))
        val recipientsBlank = svc.getRecipientTotals(10, StatisticsFilter(year = 2062, filterText = "   "))
        assertEquals(recipientsNoFilter.size, recipientsBlank.size, "a blank filterText must not narrow getRecipientTotals")

        val articlesNoFilter = svc.getArticleTotals(10, StatisticsFilter(year = 2062))
        val articlesBlank = svc.getArticleTotals(10, StatisticsFilter(year = 2062, filterText = "   "))
        assertEquals(articlesNoFilter.size, articlesBlank.size, "a blank filterText must not narrow getArticleTotals")
    }
}
