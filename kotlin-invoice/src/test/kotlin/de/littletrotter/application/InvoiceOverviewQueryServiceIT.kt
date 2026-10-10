package de.littletrotter.application

import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.application.cache.InvoiceCachePort
import de.littletrotter.application.dto.InvoiceCursor
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.invoice.InvoiceOverviewDimensions
import de.littletrotter.application.invoice.InvoiceOverviewQueryService
import de.littletrotter.application.invoice.InvoiceOverviewQueryServiceImpl
import de.littletrotter.application.invoice.InvoiceOverviewSort
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.common.QuarkusTestResource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Money
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceOverviewQueryServiceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceOverviewQueryService: InvoiceOverviewQueryService

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Test
    // @TestTransaction (rollback), not @Transactional (commit): this test used to commit
    // its two fixtures permanently and then assert the WHERE-less findOverview/countOverview
    // against the whole shared table, which is exactly the "already has rows" order dependency
    // the sibling tests below had to work around instead of fixing. Filtered on this test's
    // own "IT-2025-000" marker (matches only 0001/0002, not the "IT-2025-06-00001"-style numbers
    // other tests in this class use) so the count assertions never depend on what else is in the
    // table.
    @io.quarkus.test.TestTransaction
    fun `jpql projection returns overview rows and maps correctly`() {
        // prepare reference data
        val sender = SenderProfile().apply { name = "Default Sender"; invoicePrefix = "DS-"; registerCourt = "AG Musterstadt" }
        val seller = SenderProfile().apply { name = "Seller One"; invoicePrefix = "S1-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-1"); name = "Acme Corp" }
        val pt = PaymentTerm().apply { setName(em.find(de.littletrotter.domain.reference.Language::class.java, 1)!!, "Net 30") }

        em.persist(sender)
        em.persist(seller)
        em.persist(recipientEntity)
        em.persist(pt)

        // invoice with selectedSeller
        val inv1 = Invoice().apply {
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("IT-2025-0001")
            selectedSeller = seller
            this.recipient = recipientEntity
            invoiceDate = LocalDate.of(2025, 5, 1)
            totals.grossAmount = de.littletrotter.shared.Money(BigDecimal("100.00"))
            paymentTerm = pt
            paymentTermsDays = 30
            addItem(InvoiceItem().apply { description = "Item A"; unitPriceNet = de.littletrotter.shared.Money(BigDecimal("50.00")); quantity = BigDecimal.ONE })
        }
        em.persist(inv1)

        // invoice with senderProfile only
        val inv2 = Invoice().apply {
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("IT-2025-0002")
            senderProfile = sender
            this.recipient = recipientEntity
            invoiceDate = LocalDate.of(2025, 4, 1)
            totals.grossAmount = de.littletrotter.shared.Money(BigDecimal("200.00"))
            paymentTermsDays = 14
            addItem(InvoiceItem().apply { description = "Item B"; unitPriceNet = de.littletrotter.shared.Money(BigDecimal("200.00")); quantity = BigDecimal.ONE })
        }
        em.persist(inv2)

        em.flush()
        em.clear()

        val results = invoiceOverviewQueryService.findOverview(0, 10, "IT-2025-000", null)
        assertEquals(2, results.size)

        val first = results[0]
        // ordered by invoiceDate desc so inv1 is first
        assertEquals("IT-2025-0001", first.invoiceNumber)
        assertEquals("Seller One", first.sellerName)
        assertNull(first.sellerCity)
        assertEquals("Net 30", first.paymentLabel)
        assertEquals(Money.of(BigDecimal("100.00")), first.grossAmount)

        val second = results[1]
        assertEquals("IT-2025-0002", second.invoiceNumber)
        assertEquals("Default Sender", second.sellerName)
        assertEquals("14", second.paymentLabel)
        assertEquals(Money.of(BigDecimal("200.00")), second.grossAmount)

        // server-side count should match the overview size
        val count = invoiceOverviewQueryService.countOverview("IT-2025-000", null)
        assertEquals(2L, count)

        // filtered count works as expected
        val filteredCount = invoiceOverviewQueryService.countOverview("IT-2025-0001", null)
        assertEquals(1L, filteredCount)
    }

    @Test
    @Transactional
    fun `IN-4 - the paged overview shows the payment term's English name for an English-language invoice`() {
        // PO decision 2 (2026-09-06) — the accepted consequence: the column shows what the
        // document says, English name for an English invoice, to a German admin. This exercises
        // the Criteria join (InvoiceOverviewQueryServiceImpl.resolveLivePaymentTermName) the
        // in-memory findAllOverview() unit test above cannot reach.
        val germanLang = em.find(de.littletrotter.domain.reference.Language::class.java, 1)!!
        val englishLang = em.find(de.littletrotter.domain.reference.Language::class.java, 2)!!
        val pt = PaymentTerm().apply {
            // "14 Tage netto" collides verbatim with one of
            // PaymentTermMigrationIT's four seeded standard-term names; this shared, never-cleaned
            // database showed exactly that (that test's own "exactly four" count went to 5). A
            // distinctive, non-colliding name, the same convention every other fixture in this
            // class already follows.
            setName(germanLang, "IN4-OV 14 Tage netto")
            setName(englishLang, "IN4-OV Net 14 days")
        }
        val seller = SenderProfile().apply { name = "IN4 EN Seller"; invoicePrefix = "IN4EN-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-IN4EN"); name = "IN4 EN Corp" }
        em.persist(pt)
        em.persist(seller)
        em.persist(recipientEntity)

        val inv = Invoice().apply {
            invoiceNumber = InvoiceNumber("IT-2070-01-00001")
            selectedSeller = seller
            this.recipient = recipientEntity
            invoiceDate = LocalDate.of(2070, 1, 1)
            documentLanguage = "en"
            paymentTerm = pt
            totals.grossAmount = Money(BigDecimal("10.00"))
            addItem(InvoiceItem().apply { description = "Item"; unitPriceNet = Money(BigDecimal("10.00")); quantity = BigDecimal.ONE })
        }
        em.persist(inv)
        em.flush()
        em.clear()

        val results = invoiceOverviewQueryService.findOverview(0, 10, "IT-2070-01", null)
        assertEquals(1, results.size)
        assertEquals("IN4-OV Net 14 days", results[0].paymentLabel)
    }

    // Statistics-aggregate coverage (getYearlyTotals/getMonthlyTotals/... via v_stat_* views)
    // moved to InvoiceStatisticsQueryServiceIT (Z2 split).

    @Test
    @Transactional
    fun `countOverview returns correct counts for null, text, and status filters`() {
        val baselineTotal = invoiceOverviewQueryService.countOverview(null, null)
        val baselineDraft = invoiceOverviewQueryService.countOverview(null, InvoiceStatus.DRAFT)
        val baselineReview = invoiceOverviewQueryService.countOverview(null, InvoiceStatus.IN_REVIEW)

        // prepare reference data
        val seller = SenderProfile().apply { name = "Count Seller"; invoicePrefix = "CS-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-CNT"); name = "Count Corp" }

        em.persist(seller)
        em.persist(recipientEntity)

        // invoice 1: DRAFT status
        val inv1 = Invoice().apply {
            invoiceNumber = InvoiceNumber("IT-2025-06-00001")
            selectedSeller = seller
            this.recipient = recipientEntity
            invoiceDate = LocalDate.of(2025, 6, 1)
            totals.grossAmount = Money(BigDecimal("150.00"))
            paymentTermsDays = 14
            addItem(InvoiceItem().apply {
                description = "Service A"
                unitPriceNet = Money(BigDecimal("150.00"))
                quantity = BigDecimal.ONE
            })
        }
        em.persist(inv1)

        // invoice 2: DRAFT status, different number
        val inv2 = Invoice().apply {
            invoiceNumber = InvoiceNumber("IT-2025-07-00002")
            selectedSeller = seller
            this.recipient = recipientEntity
            invoiceDate = LocalDate.of(2025, 7, 1)
            totals.grossAmount = Money(BigDecimal("250.00"))
            paymentTermsDays = 30
            addItem(InvoiceItem().apply {
                description = "Service B"
                unitPriceNet = Money(BigDecimal("250.00"))
                quantity = BigDecimal.ONE
            })
        }
        em.persist(inv2)

        // invoice 3: IN_REVIEW status
        val inv3 = Invoice().apply {
            invoiceNumber = InvoiceNumber("IT-2025-08-00003")
            selectedSeller = seller
            this.recipient = recipientEntity
            invoiceDate = LocalDate.of(2025, 8, 1)
            totals.grossAmount = Money(BigDecimal("350.00"))
            paymentTermsDays = 7
            addItem(InvoiceItem().apply {
                description = "Service C"
                unitPriceNet = Money(BigDecimal("350.00"))
                quantity = BigDecimal.ONE
            })
        }
        inv3.forceStatusForTest(InvoiceStatus.IN_REVIEW)
        em.persist(inv3)

        em.flush()
        em.clear()

        // count all includes baseline data from other test methods in this class
        val totalCount = invoiceOverviewQueryService.countOverview(null, null)
        assertEquals(baselineTotal + 3L, totalCount)

        // count with filter matching exactly one invoice number
        val filteredByNumber = invoiceOverviewQueryService.countOverview("IT-2025-06-00001", null)
        assertEquals(1L, filteredByNumber)

        // count by status DRAFT – inv1 and inv2 are DRAFT
        val draftCount = invoiceOverviewQueryService.countOverview(null, InvoiceStatus.DRAFT)
        assertEquals(baselineDraft + 2L, draftCount)

        // count by status IN_REVIEW – only inv3
        val reviewCount = invoiceOverviewQueryService.countOverview(null, InvoiceStatus.IN_REVIEW)
        assertEquals(baselineReview + 1L, reviewCount)
    }

    /** `%` and `_` typed into the filter box are data, not wildcards.
     *  Before the ESCAPE fix a search for `50_000` also matched `50X000`.
     *  `@TestTransaction` (rollback), not `@Transactional` (commit): the projection test in
     *  this class asserts absolute row counts and must not see this fixture. */
    @Test
    @io.quarkus.test.TestTransaction
    fun `filter wildcards are treated literally`() {
        // The wildcard characters live in the recipient name — invoice numbers reject `_`
        // by domain validation, but names are free text and part of the same filter.
        val seller = SenderProfile().apply { name = "Esc Seller"; invoicePrefix = "ESC-"; registerCourt = "AG Musterstadt" }
        val underscoreRecipient = Recipient().apply { customerNumber = CustomerNumber("CUST-ESC1"); name = "Projekt 50_000 GmbH" }
        val plainRecipient = Recipient().apply { customerNumber = CustomerNumber("CUST-ESC2"); name = "Projekt 50X000 GmbH" }
        em.persist(seller)
        em.persist(underscoreRecipient)
        em.persist(plainRecipient)

        fun invoiceFor(number: String, to: Recipient, day: Int) = Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
            selectedSeller = seller
            this.recipient = to
            invoiceDate = LocalDate.of(2031, 1, day)
            totals.grossAmount = Money(BigDecimal("10.00"))
            paymentTermsDays = 14
            addItem(InvoiceItem().apply {
                description = "Esc item"
                unitPriceNet = Money(BigDecimal("10.00"))
                quantity = BigDecimal.ONE
            })
        }
        em.persist(invoiceFor("IT-2031-01-00001", underscoreRecipient, 1))
        em.persist(invoiceFor("IT-2031-01-00002", plainRecipient, 2))
        em.flush()
        em.clear()

        // `_` matches only itself, not "any single character"
        assertEquals(1L, invoiceOverviewQueryService.countOverview("50_000", null))
        val hits = invoiceOverviewQueryService.findOverview(0, 10, "50_000", null)
        assertEquals(listOf("IT-2031-01-00001"), hits.map { it.invoiceNumber })

        // `%` matches only a literal percent sign — unescaped it would match both rows
        assertEquals(0L, invoiceOverviewQueryService.countOverview("50%000", null))
        assertTrue(invoiceOverviewQueryService.findOverview(0, 10, "50%000", null).isEmpty())
    }

    @Test
    @Transactional
    fun `findOverview and countOverview filter on reverseCharge`() {
        val baselineReverseCharge = invoiceOverviewQueryService.countOverview(null, null, true)

        val seller = SenderProfile().apply { name = "RC Seller"; invoicePrefix = "RC-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-RC"); name = "RC Corp" }
        em.persist(seller)
        em.persist(recipientEntity)

        val rcInvoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("IT-2025-09-00001")
            selectedSeller = seller
            this.recipient = recipientEntity
            invoiceDate = LocalDate.of(2025, 9, 1)
            totals.grossAmount = Money(BigDecimal("500.00"))
            paymentTermsDays = 14
            reverseCharge = true
            addItem(InvoiceItem().apply {
                description = "Reverse charge service"
                unitPriceNet = Money(BigDecimal("500.00"))
                quantity = BigDecimal.ONE
            })
        }
        em.persist(rcInvoice)

        val normalInvoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("IT-2025-09-00002")
            selectedSeller = seller
            this.recipient = recipientEntity
            invoiceDate = LocalDate.of(2025, 9, 2)
            totals.grossAmount = Money(BigDecimal("600.00"))
            paymentTermsDays = 14
            addItem(InvoiceItem().apply {
                description = "Regular service"
                unitPriceNet = Money(BigDecimal("600.00"))
                quantity = BigDecimal.ONE
            })
        }
        em.persist(normalInvoice)

        em.flush()
        em.clear()

        val reverseChargeCount = invoiceOverviewQueryService.countOverview(null, null, true)
        assertEquals(baselineReverseCharge + 1L, reverseChargeCount)

        val reverseChargeResults = invoiceOverviewQueryService.findOverview(
            0, 10, null, null, InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC, true,
        )
        assertTrue(reverseChargeResults.any { it.invoiceNumber == "IT-2025-09-00001" })
        assertFalse(reverseChargeResults.any { it.invoiceNumber == "IT-2025-09-00002" })

        val nonReverseChargeResults = invoiceOverviewQueryService.findOverview(
            0, 10, "IT-2025-09", null, InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC, false,
        )
        assertEquals(1, nonReverseChargeResults.size)
        assertEquals("IT-2025-09-00002", nonReverseChargeResults[0].invoiceNumber)

        // null reverseCharge means "no filter" — both show up
        val unfiltered = invoiceOverviewQueryService.findOverview(
            0, 10, "IT-2025-09", null, InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC, null,
        )
        assertEquals(2, unfiltered.size)
    }

    /**
     * task: InvoiceOverviewQueryServiceImplTest coverage — keyset pagination is the classic place
     * for an off-by-one that either duplicates or skips a row when several invoices share the same
     * sort key (invoiceDate). Three invoices on the *same* date, paged two at a time: page 1 must
     * return exactly the two highest ids (tie-broken by `i.id DESC`, matching the ORDER BY), and
     * the cursor built from page 1's last row must yield exactly the remaining invoice on page 2 —
     * no duplicate of the last row of page 1, no invoice skipped in between.
     */
    @Test
    @Transactional
    fun `keyset pagination returns every invoice exactly once for equal invoice dates, without duplicates or gaps`() {
        val seller = SenderProfile().apply { name = "Cursor Seller"; invoicePrefix = "CUR-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-CUR"); name = "Cursor Corp" }
        em.persist(seller)
        em.persist(recipientEntity)

        val sameDay = LocalDate.of(2032, 3, 15)
        fun invoiceOn(number: String) = Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
            selectedSeller = seller
            this.recipient = recipientEntity
            invoiceDate = sameDay
            totals.grossAmount = Money(BigDecimal("10.00"))
            paymentTermsDays = 14
            addItem(InvoiceItem().apply { description = "Cursor Item"; unitPriceNet = Money(BigDecimal("10.00")); quantity = BigDecimal.ONE })
        }

        val inv1 = invoiceOn("CUR-2032-03-00001")
        val inv2 = invoiceOn("CUR-2032-03-00002")
        val inv3 = invoiceOn("CUR-2032-03-00003")
        em.persist(inv1)
        em.persist(inv2)
        em.persist(inv3)
        em.flush()
        em.clear()

        // Same invoiceDate for all three -> the tiebreaker `i.id DESC` alone decides the order,
        // and ids grow with persistence order, so the expected order is inv3, inv2, inv1.
        val page1 = invoiceOverviewQueryService.findOverviewAfterCursor(cursor = null, pageSize = 2, filterText = "CUR-2032-03")
        assertEquals(2, page1.size)
        assertEquals(listOf(inv3.invoiceNumber.value, inv2.invoiceNumber.value), page1.map { it.invoiceNumber })

        val cursor = InvoiceCursor.from(page1)
        assertNotNull(cursor, "a full page must yield a cursor for the next page")

        val page2 = invoiceOverviewQueryService.findOverviewAfterCursor(cursor = cursor, pageSize = 2, filterText = "CUR-2032-03")
        assertEquals(
            listOf(inv1.invoiceNumber.value), page2.map { it.invoiceNumber },
            "page 2 must contain exactly the one remaining invoice — no duplicate of page 1's last row, none skipped",
        )

        // Combined, the two pages must cover every invoice exactly once.
        val allSeen = (page1 + page2).map { it.invoiceNumber }
        assertEquals(setOf(inv1.invoiceNumber.value, inv2.invoiceNumber.value, inv3.invoiceNumber.value), allSeen.toSet())
        assertEquals(3, allSeen.size, "no invoice must appear twice across pages")

        // One more page past the end must be empty and yield no further cursor.
        val cursor2 = InvoiceCursor.from(page2)
        val page3 = invoiceOverviewQueryService.findOverviewAfterCursor(cursor = cursor2, pageSize = 2, filterText = "CUR-2032-03")
        assertTrue(page3.isEmpty())
    }

    // ── Kunde/Anbieter/Zeitraum filters ─────────────────────────────────────────────────

    private fun basicInvoice(number: String, seller: SenderProfile, recipient: Recipient, date: LocalDate) = Invoice().apply {
        invoiceNumber = InvoiceNumber(number)
        selectedSeller = seller
        this.recipient = recipient
        invoiceDate = date
        totals.grossAmount = Money(BigDecimal("10.00"))
        paymentTermsDays = 14
        addItem(InvoiceItem().apply { description = "Item"; unitPriceNet = Money(BigDecimal("10.00")); quantity = BigDecimal.ONE })
    }

    @Test
    // @TestTransaction (rollback), not @Transactional (commit): committed seeds from
    // these tests leaked into the projection/count assertions of the older tests in this
    // class (same lesson as the wildcard test above).
    @io.quarkus.test.TestTransaction
    fun `findOverview and countOverview filter on recipientId`() {
        val seller = SenderProfile().apply { name = "Recipient-Filter Seller"; invoicePrefix = "RF-"; registerCourt = "AG Musterstadt" }
        val targetRecipient = Recipient().apply { customerNumber = CustomerNumber("CUST-RFT"); name = "Target Recipient" }
        val otherRecipient = Recipient().apply { customerNumber = CustomerNumber("CUST-RFO"); name = "Other Recipient" }
        em.persist(seller)
        em.persist(targetRecipient)
        em.persist(otherRecipient)
        em.persist(basicInvoice("IT-2033-01-00001", seller, targetRecipient, LocalDate.of(2033, 1, 1)))
        em.persist(basicInvoice("IT-2033-01-00002", seller, otherRecipient, LocalDate.of(2033, 1, 2)))
        em.flush()
        em.clear()

        val recipientId = targetRecipient.id!!
        val results = invoiceOverviewQueryService.findOverview(
            0, 10, null, null, InvoiceOverviewSort(InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC),
            InvoiceOverviewDimensions(recipientId = recipientId),
        )
        assertEquals(listOf("IT-2033-01-00001"), results.map { it.invoiceNumber })

        val count = invoiceOverviewQueryService.countOverview(null, null, InvoiceOverviewDimensions(recipientId = recipientId))
        assertEquals(1L, count)
    }

    @Test
    // @TestTransaction (rollback), not @Transactional (commit): committed seeds from
    // these tests leaked into the projection/count assertions of the older tests in this
    // class (same lesson as the wildcard test above).
    @io.quarkus.test.TestTransaction
    fun `findOverview and countOverview filter on senderProfileId, matching either selectedSeller or senderProfile`() {
        val targetSender = SenderProfile().apply { name = "Target Sender"; invoicePrefix = "TS-"; registerCourt = "AG Musterstadt" }
        val otherSender = SenderProfile().apply { name = "Other Sender"; invoicePrefix = "OS-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-SFT"); name = "Sender Filter Recipient" }
        em.persist(targetSender)
        em.persist(otherSender)
        em.persist(recipientEntity)

        val viaSelectedSeller = basicInvoice("IT-2033-02-00001", targetSender, recipientEntity, LocalDate.of(2033, 2, 1))
        val viaSenderProfile = Invoice().apply {
            invoiceNumber = InvoiceNumber("IT-2033-02-00002")
            senderProfile = targetSender
            this.recipient = recipientEntity
            invoiceDate = LocalDate.of(2033, 2, 2)
            totals.grossAmount = Money(BigDecimal("10.00"))
            paymentTermsDays = 14
            addItem(InvoiceItem().apply { description = "Item"; unitPriceNet = Money(BigDecimal("10.00")); quantity = BigDecimal.ONE })
        }
        val otherInvoice = basicInvoice("IT-2033-02-00003", otherSender, recipientEntity, LocalDate.of(2033, 2, 3))
        em.persist(viaSelectedSeller)
        em.persist(viaSenderProfile)
        em.persist(otherInvoice)
        em.flush()
        em.clear()

        val senderProfileId = targetSender.id!!
        val results = invoiceOverviewQueryService.findOverview(
            0, 10, null, null, InvoiceOverviewSort(InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC),
            InvoiceOverviewDimensions(senderProfileId = senderProfileId),
        )
        assertEquals(setOf("IT-2033-02-00001", "IT-2033-02-00002"), results.map { it.invoiceNumber }.toSet())
        assertEquals(2, results.size)

        val count = invoiceOverviewQueryService.countOverview(null, null, InvoiceOverviewDimensions(senderProfileId = senderProfileId))
        assertEquals(2L, count)
    }

    @Test
    // @TestTransaction (rollback), not @Transactional (commit): committed seeds from
    // these tests leaked into the projection/count assertions of the older tests in this
    // class (same lesson as the wildcard test above).
    @io.quarkus.test.TestTransaction
    fun `findOverview and countOverview filter on invoiceDate range, half-open ranges allowed`() {
        val seller = SenderProfile().apply { name = "Date Range Seller"; invoicePrefix = "DR-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-DRT"); name = "Date Range Recipient" }
        em.persist(seller)
        em.persist(recipientEntity)

        em.persist(basicInvoice("IT-2034-01-00001", seller, recipientEntity, LocalDate.of(2034, 1, 15)))
        em.persist(basicInvoice("IT-2034-01-00002", seller, recipientEntity, LocalDate.of(2034, 6, 15)))
        em.persist(basicInvoice("IT-2034-01-00003", seller, recipientEntity, LocalDate.of(2034, 12, 15)))
        em.flush()
        em.clear()

        // full year range
        val fullYear = invoiceOverviewQueryService.findOverview(
            0, 10, null, null, InvoiceOverviewSort(InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC),
            InvoiceOverviewDimensions(dateFrom = LocalDate.of(2034, 1, 1), dateTo = LocalDate.of(2034, 12, 31)),
        )
        assertEquals(3, fullYear.size)

        // half-open: only dateFrom
        val fromMidYear = invoiceOverviewQueryService.findOverview(
            0, 10, null, null, InvoiceOverviewSort(InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC),
            InvoiceOverviewDimensions(dateFrom = LocalDate.of(2034, 6, 1)),
        )
        assertEquals(setOf("IT-2034-01-00002", "IT-2034-01-00003"), fromMidYear.map { it.invoiceNumber }.toSet())

        // half-open: only dateTo
        val toMidYear = invoiceOverviewQueryService.findOverview(
            0, 10, null, null, InvoiceOverviewSort(InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC),
            InvoiceOverviewDimensions(dateTo = LocalDate.of(2034, 6, 30)),
        )
        assertEquals(setOf("IT-2034-01-00001", "IT-2034-01-00002"), toMidYear.map { it.invoiceNumber }.toSet())

        val count = invoiceOverviewQueryService.countOverview(
            null, null,
            InvoiceOverviewDimensions(dateFrom = LocalDate.of(2034, 1, 1), dateTo = LocalDate.of(2034, 12, 31)),
        )
        assertEquals(3L, count)
    }

    @Test
    // @TestTransaction (rollback), not @Transactional (commit): committed seeds from
    // these tests leaked into the projection/count assertions of the older tests in this
    // class (same lesson as the wildcard test above).
    @io.quarkus.test.TestTransaction
    fun `findOverview combines recipient, sender, date range, status and free text filters`() {
        val seller = SenderProfile().apply { name = "Combo Seller"; invoicePrefix = "CB-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-CMB"); name = "Combo Recipient" }
        em.persist(seller)
        em.persist(recipientEntity)

        val matching = basicInvoice("IT-2035-01-00001", seller, recipientEntity, LocalDate.of(2035, 5, 10)).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        val wrongStatus = basicInvoice("IT-2035-01-00002", seller, recipientEntity, LocalDate.of(2035, 5, 11))
        val outsideRange = basicInvoice("IT-2035-01-00003", seller, recipientEntity, LocalDate.of(2036, 1, 1)).apply { forceStatusForTest(InvoiceStatus.IN_REVIEW) }
        em.persist(matching)
        em.persist(wrongStatus)
        em.persist(outsideRange)
        em.flush()
        em.clear()

        val results = invoiceOverviewQueryService.findOverview(
            0, 10, "IT-2035", InvoiceStatus.IN_REVIEW, InvoiceOverviewSort(InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC),
            InvoiceOverviewDimensions(
                recipientId = recipientEntity.id, senderProfileId = seller.id,
                dateFrom = LocalDate.of(2035, 1, 1), dateTo = LocalDate.of(2035, 12, 31),
            ),
        )
        assertEquals(listOf("IT-2035-01-00001"), results.map { it.invoiceNumber })
    }

    @Test
    // @TestTransaction (rollback), not @Transactional (commit): committed seeds from
    // these tests leaked into the projection/count assertions of the older tests in this
    // class (same lesson as the wildcard test above).
    @io.quarkus.test.TestTransaction
    fun `countOverviewGroupedByValidationStatus respects recipient, sender and date-range filters`() {
        val seller = SenderProfile().apply { name = "Chip Seller"; invoicePrefix = "CH-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-CHP"); name = "Chip Recipient" }
        val otherRecipient = Recipient().apply { customerNumber = CustomerNumber("CUST-CHPO"); name = "Other Chip Recipient" }
        em.persist(seller)
        em.persist(recipientEntity)
        em.persist(otherRecipient)

        val valid = basicInvoice("IT-2037-01-00001", seller, recipientEntity, LocalDate.of(2037, 3, 1)).apply { validationStatus = ValidationStatus.VALID }
        val invalid = basicInvoice("IT-2037-01-00002", seller, recipientEntity, LocalDate.of(2037, 3, 2)).apply { validationStatus = ValidationStatus.INVALID }
        val otherRecipientInvoice = basicInvoice("IT-2037-01-00003", seller, otherRecipient, LocalDate.of(2037, 3, 3)).apply { validationStatus = ValidationStatus.VALID }
        em.persist(valid)
        em.persist(invalid)
        em.persist(otherRecipientInvoice)
        em.flush()
        em.clear()

        val counts = invoiceOverviewQueryService.countOverviewGroupedByValidationStatus(
            null, null, null, recipientEntity.id, null, LocalDate.of(2037, 1, 1), LocalDate.of(2037, 12, 31),
        )
        assertEquals(1L, counts[ValidationStatus.VALID])
        assertEquals(1L, counts[ValidationStatus.INVALID])
        assertEquals(2, counts.values.sum().toInt())
    }

    @Test
    // @TestTransaction (rollback), not @Transactional (commit) — same reason as its sibling above.
    @io.quarkus.test.TestTransaction
    fun `countOverviewGroupedByStatus groups by invoice state and honors the validation filter`() {
        val seller = SenderProfile().apply { name = "State Chip Seller"; invoicePrefix = "SCH-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-SCH"); name = "State Chip Recipient" }
        em.persist(seller)
        em.persist(recipientEntity)

        // Two DRAFTs and one SENT, all VALID; plus one DRAFT that is INVALID, which the
        // validationStatus filter below must exclude from the DRAFT count.
        em.persist(basicInvoice("IT-2041-01-00001", seller, recipientEntity, LocalDate.of(2041, 3, 1)).apply { forceStatusForTest(InvoiceStatus.DRAFT); validationStatus = ValidationStatus.VALID })
        em.persist(basicInvoice("IT-2041-01-00002", seller, recipientEntity, LocalDate.of(2041, 3, 2)).apply { forceStatusForTest(InvoiceStatus.DRAFT); validationStatus = ValidationStatus.VALID })
        em.persist(basicInvoice("IT-2041-01-00003", seller, recipientEntity, LocalDate.of(2041, 3, 3)).apply { forceStatusForTest(InvoiceStatus.SENT); validationStatus = ValidationStatus.VALID })
        em.persist(basicInvoice("IT-2041-01-00004", seller, recipientEntity, LocalDate.of(2041, 3, 4)).apply { forceStatusForTest(InvoiceStatus.DRAFT); validationStatus = ValidationStatus.INVALID })
        em.flush()
        em.clear()

        val all = invoiceOverviewQueryService.countOverviewGroupedByStatus(
            null, null, null, recipientEntity.id, null, LocalDate.of(2041, 1, 1), LocalDate.of(2041, 12, 31),
        )
        assertEquals(3L, all[InvoiceStatus.DRAFT])
        assertEquals(1L, all[InvoiceStatus.SENT])

        // The chip counts must narrow with the toolbar's validation filter — that is what makes
        // them "the counts for what the list would actually show".
        val validOnly = invoiceOverviewQueryService.countOverviewGroupedByStatus(
            null, ValidationStatus.VALID, null, recipientEntity.id, null, LocalDate.of(2041, 1, 1), LocalDate.of(2041, 12, 31),
        )
        assertEquals(2L, validOnly[InvoiceStatus.DRAFT], "the INVALID draft must drop out")
        assertEquals(1L, validOnly[InvoiceStatus.SENT])
    }

    @Test
    // @TestTransaction (rollback), not @Transactional (commit): committed seeds from
    // these tests leaked into the projection/count assertions of the older tests in this
    // class (same lesson as the wildcard test above).
    @io.quarkus.test.TestTransaction
    fun `findOverviewAfterCursor filters on recipient, sender and date range in addition to the keyset condition`() {
        val targetSender = SenderProfile().apply { name = "Cursor Filter Sender"; invoicePrefix = "CFS-"; registerCourt = "AG Musterstadt" }
        val otherSender = SenderProfile().apply { name = "Cursor Filter Other Sender"; invoicePrefix = "CFO-"; registerCourt = "AG Musterstadt" }
        val targetRecipient = Recipient().apply { customerNumber = CustomerNumber("CUST-CFT"); name = "Cursor Filter Recipient" }
        em.persist(targetSender)
        em.persist(otherSender)
        em.persist(targetRecipient)

        em.persist(basicInvoice("IT-2038-01-00001", targetSender, targetRecipient, LocalDate.of(2038, 4, 1)))
        em.persist(basicInvoice("IT-2038-01-00002", targetSender, targetRecipient, LocalDate.of(2038, 4, 2)))
        // outside the date range
        em.persist(basicInvoice("IT-2038-01-00003", targetSender, targetRecipient, LocalDate.of(2039, 1, 1)))
        // wrong sender
        em.persist(basicInvoice("IT-2038-01-00004", otherSender, targetRecipient, LocalDate.of(2038, 4, 3)))
        em.flush()
        em.clear()

        val page = invoiceOverviewQueryService.findOverviewAfterCursor(
            cursor = null,
            pageSize = 10,
            filterText = null,
            status = null,
            dimensions = InvoiceOverviewDimensions(
                recipientId = targetRecipient.id,
                senderProfileId = targetSender.id,
                dateFrom = LocalDate.of(2038, 1, 1),
                dateTo = LocalDate.of(2038, 12, 31),
            ),
        )
        assertEquals(setOf("IT-2038-01-00001", "IT-2038-01-00002"), page.map { it.invoiceNumber }.toSet())
    }

    // ─── getTotals: no dedicated coverage existed before the Criteria rewrite ───

    @Test
    @io.quarkus.test.TestTransaction
    fun `getTotals sums net, vat and gross across matching invoices and respects the text and status filters`() {
        val seller = SenderProfile().apply { name = "Totals Seller"; invoicePrefix = "TOT-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-TOT"); name = "Totals Recipient" }
        em.persist(seller); em.persist(recipientEntity)

        val inv1 = basicInvoice("IT-2060-01-00001", seller, recipientEntity, LocalDate.of(2060, 1, 1)).apply {
            totals.netAmount = Money(BigDecimal("100.00"))
            totals.vatAmount = Money(BigDecimal("19.00"))
            totals.grossAmount = Money(BigDecimal("119.00"))
        }
        val inv2 = basicInvoice("IT-2060-01-00002", seller, recipientEntity, LocalDate.of(2060, 1, 2)).apply {
            totals.netAmount = Money(BigDecimal("50.00"))
            totals.vatAmount = Money(BigDecimal("9.50"))
            totals.grossAmount = Money(BigDecimal("59.50"))
            forceStatusForTest(InvoiceStatus.SENT)
        }
        em.persist(inv1); em.persist(inv2)
        em.flush(); em.clear()

        val (net, vat, gross) = invoiceOverviewQueryService.getTotals("IT-2060-01", null)
        assertEquals(Money.of(BigDecimal("150.00")), net)
        assertEquals(Money.of(BigDecimal("28.50")), vat)
        assertEquals(Money.of(BigDecimal("178.50")), gross)

        val (netFinalized, _, grossFinalized) = invoiceOverviewQueryService.getTotals("IT-2060-01", InvoiceStatus.SENT)
        assertEquals(Money.of(BigDecimal("50.00")), netFinalized)
        assertEquals(Money.of(BigDecimal("59.50")), grossFinalized)

        // blank filterText behaves like no filter at all — must not throw and must still include
        // both fixtures (checked relative to a baseline since other tests in this module commit data)
        val baseline = invoiceOverviewQueryService.getTotals(null, null)
        val blank = invoiceOverviewQueryService.getTotals("   ", null)
        assertEquals(baseline, blank)
    }

    // ─── cache resilience — corrupted entry / write failure / write success ─────────────────────
    // These need a real EntityManager (Criteria has no query string a mock could stand in for),
    // so they moved here from the former mocked-EntityManager unit test.

    /** Records every `cacheOverview` call instead of actually caching, and can be told to hand
     *  back an arbitrary (possibly corrupted) JSON payload on the next read. */
    private class RecordingCachePort : InvoiceCachePort by de.littletrotter.application.cache.NoopInvoiceCachePort() {
        var nextRead: String? = null
        var lastWrittenJson: String? = null
        override fun getCachedOverview(cacheKey: String): String? = nextRead
        override fun cacheOverview(cacheKey: String, json: String, ttlSeconds: Long) {
            lastWrittenJson = json
        }
    }

    @Test
    @io.quarkus.test.TestTransaction
    fun `a corrupted cache entry falls back to a fresh Criteria query instead of failing`() {
        val seller = SenderProfile().apply { name = "Corrupt Cache Seller"; invoicePrefix = "CCS-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-CCS"); name = "Corrupt Cache Recipient" }
        em.persist(seller); em.persist(recipientEntity)
        em.persist(basicInvoice("IT-2061-01-00001", seller, recipientEntity, LocalDate.of(2061, 1, 1)))
        em.flush(); em.clear()

        val cache = RecordingCachePort().apply { nextRead = "not-valid-json" }
        val svc = InvoiceOverviewQueryServiceImpl(invoiceRepository, em, cache, ObjectMapper())

        val result = svc.findOverview(0, 10, "IT-2061-01", null)
        assertEquals(listOf("IT-2061-01-00001"), result.map { it.invoiceNumber }, "corrupted cache must not swallow the real result")
    }

    @Test
    @io.quarkus.test.TestTransaction
    fun `a fresh query result is written back to the cache on a miss`() {
        val seller = SenderProfile().apply { name = "Cache Write Seller"; invoicePrefix = "CWS-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-CWS"); name = "Cache Write Recipient" }
        em.persist(seller); em.persist(recipientEntity)
        em.persist(basicInvoice("IT-2062-01-00001", seller, recipientEntity, LocalDate.of(2062, 1, 1)))
        em.flush(); em.clear()

        val cache = RecordingCachePort()
        val svc = InvoiceOverviewQueryServiceImpl(invoiceRepository, em, cache, ObjectMapper().findAndRegisterModules())

        val result = svc.findOverview(0, 10, "IT-2062-01", null)
        assertEquals(1, result.size)
        assertNotNull(cache.lastWrittenJson, "a cache miss must write the freshly queried result back")
        assertTrue(cache.lastWrittenJson!!.contains("IT-2062-01-00001"))
    }

    @Test
    @io.quarkus.test.TestTransaction
    fun `a cache-write failure never surfaces to the caller — the freshly queried result still wins`() {
        val seller = SenderProfile().apply { name = "Cache Fail Seller"; invoicePrefix = "CFL-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-CFL"); name = "Cache Fail Recipient" }
        em.persist(seller); em.persist(recipientEntity)
        em.persist(basicInvoice("IT-2063-01-00001", seller, recipientEntity, LocalDate.of(2063, 1, 1)))
        em.flush(); em.clear()

        val throwingCache = object : InvoiceCachePort by de.littletrotter.application.cache.NoopInvoiceCachePort() {
            override fun getCachedOverview(cacheKey: String): String? = null
            override fun cacheOverview(cacheKey: String, json: String, ttlSeconds: Long) {
                error("Valkey unreachable")
            }
        }
        val svc = InvoiceOverviewQueryServiceImpl(invoiceRepository, em, throwingCache, ObjectMapper())

        val result = svc.findOverview(0, 10, "IT-2063-01", null)
        assertEquals(listOf("IT-2063-01-00001"), result.map { it.invoiceNumber }, "a cache write failure must not swallow the real result")
    }

    // ─── sort-field coverage — every InvoiceOverviewSortField, both directions ──────────────────

    @Test
    @io.quarkus.test.TestTransaction
    fun `findOverview accepts every sort field and both directions without error`() {
        val seller = SenderProfile().apply { name = "Sort Seller"; invoicePrefix = "SRT-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-SRT"); name = "Sort Recipient" }
        em.persist(seller); em.persist(recipientEntity)
        em.persist(basicInvoice("IT-2064-01-00001", seller, recipientEntity, LocalDate.of(2064, 1, 1)))
        em.persist(basicInvoice("IT-2064-01-00002", seller, recipientEntity, LocalDate.of(2064, 1, 2)))
        em.flush(); em.clear()

        for (field in InvoiceOverviewSortField.entries) {
            for (dir in SortDirection.entries) {
                val results = invoiceOverviewQueryService.findOverview(
                    0, 10, "IT-2064-01", null, field, dir, reverseCharge = null,
                )
                assertEquals(2, results.size, "sort field $field / $dir must not drop or duplicate rows")
            }
        }
    }

    // ─── countOverview(validationStatus) overloads — the direct 4-arg and dims fallback paths ──

    @Test
    @io.quarkus.test.TestTransaction
    fun `countOverview 4-arg overload with a non-null validationStatus counts distinctly`() {
        val seller = SenderProfile().apply { name = "VS4 Seller"; invoicePrefix = "VS4-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-VS4"); name = "VS4 Recipient" }
        em.persist(seller); em.persist(recipientEntity)
        em.persist(basicInvoice("IT-2065-01-00001", seller, recipientEntity, LocalDate.of(2065, 1, 1)).apply { validationStatus = ValidationStatus.VALID })
        em.persist(basicInvoice("IT-2065-01-00002", seller, recipientEntity, LocalDate.of(2065, 1, 2)).apply { validationStatus = ValidationStatus.INVALID })
        em.flush(); em.clear()

        val count = invoiceOverviewQueryService.countOverview("IT-2065-01", null, null, ValidationStatus.VALID)
        assertEquals(1L, count)
    }

    @Test
    @io.quarkus.test.TestTransaction
    fun `countOverview dims overload falls back to the validationStatus-only count when recipient, sender and date range are all unset`() {
        val seller = SenderProfile().apply { name = "VS8 Seller"; invoicePrefix = "VS8-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-VS8"); name = "VS8 Recipient" }
        em.persist(seller); em.persist(recipientEntity)
        em.persist(basicInvoice("IT-2066-01-00001", seller, recipientEntity, LocalDate.of(2066, 1, 1)).apply { validationStatus = ValidationStatus.VALID })
        em.persist(basicInvoice("IT-2066-01-00002", seller, recipientEntity, LocalDate.of(2066, 1, 2)).apply { validationStatus = ValidationStatus.INVALID })
        em.flush(); em.clear()

        val count = invoiceOverviewQueryService.countOverview(
            "IT-2066-01", null,
            InvoiceOverviewDimensions(validationStatus = ValidationStatus.VALID),
        )
        assertEquals(1L, count)
    }

    @Test
    @io.quarkus.test.TestTransaction
    fun `document generation failure flag follows the newest job of an issued invoice only`() {
        val seller = SenderProfile().apply { name = "Seller Flag"; invoicePrefix = "SF-"; registerCourt = "AG Musterstadt" }
        val recipientEntity = Recipient().apply { customerNumber = CustomerNumber("CUST-FLAG"); name = "Flag Corp" }
        em.persist(seller)
        em.persist(recipientEntity)
        val base = java.time.OffsetDateTime.parse("2043-01-01T10:00:00Z")

        fun persistJob(inv: Invoice, jobStatus: GenerationJobStatus, minutes: Long) = em.persist(
            GenerationJob().apply {
                invoice = inv
                jobType = "DOCUMENT"
                status = jobStatus
                createdAt = base.plusMinutes(minutes)
            },
        )

        fun invoice(number: String, status: InvoiceStatus?) =
            basicInvoice(number, seller, recipientEntity, LocalDate.of(2043, 1, 1)).also { inv ->
                status?.let { inv.forceStatusForTest(it) }
                em.persist(inv)
            }

        val newestFailed = invoice("IT-2043-01-00001", InvoiceStatus.SENT)
        persistJob(newestFailed, GenerationJobStatus.COMPLETED, 0)
        persistJob(newestFailed, GenerationJobStatus.FAILED, 5)
        val recovered = invoice("IT-2043-01-00002", InvoiceStatus.SENT)
        persistJob(recovered, GenerationJobStatus.FAILED, 0)
        persistJob(recovered, GenerationJobStatus.COMPLETED, 5)
        val draftFailed = invoice("IT-2043-01-00003", null)
        persistJob(draftFailed, GenerationJobStatus.FAILED, 0)
        val noJob = invoice("IT-2043-01-00004", InvoiceStatus.SENT)
        em.flush()
        em.clear()

        val flags = invoiceOverviewQueryService.findOverview(0, 10, "IT-2043-01-0000", null)
            .associate { it.invoiceNumber to it.documentGenerationFailed }

        assertEquals(
            mapOf(
                newestFailed.invoiceNumber.value to true,
                recovered.invoiceNumber.value to false,
                draftFailed.invoiceNumber.value to false,
                noJob.invoiceNumber.value to false,
            ),
            flags,
        )
    }
}
