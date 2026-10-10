package de.littletrotter.application

import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.invoice.InvoiceOverviewQueryServiceImpl
import de.littletrotter.application.invoice.InvoiceOverviewSort
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoicePaymentTermSnapshot
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.germanFallbackLanguage
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.Money
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.repository.InvoiceRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/**
 * (PO-Entscheid 29.08.2026, Core-Härtung) — [InvoiceOverviewQueryServiceImpl]'s
 * query-building methods (`queryOverview`/`findOverviewAfterCursor`/`countOverview*`/`getTotals`)
 * no longer build JPQL strings (Criteria over the [Invoice] entity instead), so there is no query
 * string left for a mocked `EntityManager` to capture. The former tests here that asserted on
 * captured JPQL fragments are superseded by real `@QuarkusTest` coverage against Postgres:
 * [InvoiceOverviewQueryServiceIT] already exercises every filter dimension (text/status/
 * reverseCharge/validationStatus/recipientId/senderProfileId/date-range/keyset cursor, plus wildcard
 * escaping and — new with this rewrite — `getTotals` and cache-resilience behavior that needs a
 * live `EntityManager`). Test relocation, not loss of coverage (see `feedback-coverage-80-80.md`).
 *
 * What stays unit-testable without a live `EntityManager`: pure row-mapping ([mapRowToDto]),
 * the repository-backed [de.littletrotter.application.invoice.InvoiceOverviewQueryServiceImpl
 * .findAllOverview] path, the pure `countOverview(_, _, reverseCharge = null)` delegation, and the
 * cache-hit short-circuit (never touches `em` at all, so no query needs mocking).
 */
class InvoiceOverviewQueryServiceImplTest {

    @Test
    fun `mapRowToDto handles null seller and recipient`() {
        val row = arrayOf<Any?>(
            123L,          // 0  id
            "INV-001",     // 1  invoiceNumber
            null,          // 2  typeCode
            null,          // 3  sellerName1
            null,          // 4  sellerName2
            null,          // 5  recipientName
            BigDecimal("100.00"), // 6  grossAmount
            null,          // 7  paymentTermName
            "Some free text", // 8  freeText
            3,             // 9  itemsCount
            LocalDate.of(2025, 1, 2), // 10 invoiceDate
            InvoiceStatus.DRAFT,      // 11 status
            ValidationStatus.UNAVAILABLE, // 12 validationStatus
            30             // 13 paymentTermsDays
        )

        val dto = InvoiceOverviewQueryServiceImpl.mapRowToDto(row)
        assertEquals(123L, dto.id)
        assertEquals("INV-001", dto.invoiceNumber)
        assertNull(dto.sellerName)
        assertNull(dto.recipientName)
        assertEquals(Money.of("100.00"), dto.grossAmount)
        assertEquals("30", dto.paymentLabel)
        assertEquals(3, dto.itemsCount)
        assertEquals(LocalDate.of(2025, 1, 2), dto.invoiceDate)
        assertEquals(InvoiceStatus.DRAFT, dto.status)
        assertEquals(ValidationStatus.UNAVAILABLE, dto.validationStatus)
    }

    @Test
    fun `mapRowToDto prefers selectedSeller over senderProfile and constructs city`() {
        val row = arrayOf<Any?>(
            5L,            // 0  id
            "INV-002",     // 1  invoiceNumber
            null,          // 2  typeCode
            "Seller A",    // 3  sellerName1
            "SenderProfile B", // 4  sellerName2
            "Recipient Ltd",   // 5  recipientName
            BigDecimal("250.50"), // 6  grossAmount
            "Net 14",      // 7  paymentTermName
            null,          // 8  freeText
            1,             // 9  itemsCount
            LocalDate.of(2024, 12, 31), // 10 invoiceDate
            InvoiceStatus.SENT,    // 11 status
            ValidationStatus.VALID,     // 12 validationStatus
            null           // 13 paymentTermsDays
        )

        val dto = InvoiceOverviewQueryServiceImpl.mapRowToDto(row)
        assertEquals("Seller A", dto.sellerName)
        assertNull(dto.sellerCity)
        assertEquals("Net 14", dto.paymentLabel)
    }

    @Test
    fun `mapRowToDto handles null id and blank invoiceNumber`() {
        val row = arrayOf<Any?>(
            null, // id
            "",   // invoiceNumber
            null, // typeCode
            null, // sellerName1
            null, // sellerName2
            null, // recipientName
            null, // grossAmount
            null, // paymentTermName
            null, // freeText
            null, // itemsCount
            null, // invoiceDate
            null, // status
            null, // validationStatus
            null  // paymentTermsDays
        )

        val dto = InvoiceOverviewQueryServiceImpl.mapRowToDto(row)
        assertNull(dto.id)
        assertEquals("", dto.invoiceNumber)
        assertEquals(0, dto.itemsCount)
        assertEquals(de.littletrotter.domain.invoice.InvoiceStatus.DRAFT, dto.status)
        assertEquals(de.littletrotter.domain.shared.ValidationStatus.UNAVAILABLE, dto.validationStatus)
    }

    @Test
    fun `mapRowToDto handles null itemsCount and null payment terms into null paymentLabel`() {
        val row = arrayOf<Any?>(
            42L,           // 0  id
            "INV-003",     // 1  invoiceNumber
            null,          // 2  typeCode
            null,          // 3  sellerName1
            null,          // 4  sellerName2
            null,          // 5  recipientName
            java.math.BigDecimal("0.00"), // 6  grossAmount
            null,          // 7  paymentTermName
            null,          // 8  freeText
            null,          // 9  itemsCount null -> 0
            null,          // 10 invoiceDate
            null,          // 11 status
            null,          // 12 validationStatus
            null           // 13 paymentTermsDays
        )

        val dto = InvoiceOverviewQueryServiceImpl.mapRowToDto(row)
        assertEquals(0, dto.itemsCount)
        assertNull(dto.paymentLabel)
        assertEquals(Money.of("0.00"), dto.grossAmount)
    }

    @Test
    fun `mapRowToDto handles paymentTermsDays fallback to label when name missing`() {
        val row = arrayOf<Any?>(
            7L,            // 0  id
            "INV-004",     // 1  invoiceNumber
            null,          // 2  typeCode
            null,          // 3  sellerName1
            null,          // 4  sellerName2
            null,          // 5  recipientName
            java.math.BigDecimal("10.00"), // 6  grossAmount
            null,          // 7  paymentTermName
            null,          // 8  freeText
            2,             // 9  itemsCount
            null,          // 10 invoiceDate
            null,          // 11 status
            null,          // 12 validationStatus
            14             // 13 paymentTermsDays
        )

        val dto = InvoiceOverviewQueryServiceImpl.mapRowToDto(row)
        assertEquals("14", dto.paymentLabel)
        assertEquals(2, dto.itemsCount)
    }

    // ─── findAllOverview ──────────────────────────────────────────────────────
    // Repository-backed mapping (Invoice -> InvoiceOverviewDto); no Criteria query involved.

    private fun senderWithBillingCity(name: String, city: String): SenderProfile {
        val sender = SenderProfile().apply { this.name = name }
        sender.addresses.add(
            Address.forSender(sender, Address.AddressType.BILLING, "", "", city, isDefault = true)
        )
        return sender
    }

    @Test
    fun `findAllOverview shows the selected seller with its billing address when one is set`() {
        val invoiceRepository = mock<InvoiceRepository>()
        val em = mock<EntityManager>()

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("INV-2025-01-00001")
            selectedSeller = senderWithBillingCity("Seller GmbH", "Berlin")
            recipient = Recipient().apply { name = "Acme Corp" }
            paymentTerm = PaymentTerm().apply { setName(germanFallbackLanguage(), "Net 14") }
            totals.grossAmount = Money(BigDecimal("100.00"))
            addItem(InvoiceItem())
            addItem(InvoiceItem())
            forceStatusForTest(InvoiceStatus.SENT)
        }
        whenever(invoiceRepository.findAll(0, 60)).thenReturn(listOf(invoice))

        val sut = InvoiceOverviewQueryServiceImpl(invoiceRepository, em)
        val result = sut.findAllOverview()

        assertEquals(1, result.size)
        val dto = result[0]
        assertEquals("INV-2025-01-00001", dto.invoiceNumber)
        assertEquals("Seller GmbH", dto.sellerName)
        assertEquals("Berlin", dto.sellerCity)
        assertEquals("Acme Corp", dto.recipientName)
        assertEquals("Net 14", dto.paymentLabel)
        assertEquals(2, dto.itemsCount)
    }

    @Test
    fun `IN-4 - findAllOverview shows the payment term's English name for an English-language invoice (PO decision 2 - accepted consequence)`() {
        val invoiceRepository = mock<InvoiceRepository>()
        val em = mock<EntityManager>()

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("INV-2025-01-00002")
            selectedSeller = senderWithBillingCity("Seller GmbH", "Berlin")
            recipient = Recipient().apply { name = "Acme Corp" }
            documentLanguage = "en"
            paymentTerm = PaymentTerm().apply {
                setName(germanFallbackLanguage(), "14 Tage netto")
                setName(de.littletrotter.domain.reference.Language().apply { id = 2; code = "en"; isFallback = false }, "Net 14 days")
            }
            totals.grossAmount = Money(BigDecimal("100.00"))
            forceStatusForTest(InvoiceStatus.SENT)
        }
        whenever(invoiceRepository.findAll(0, 60)).thenReturn(listOf(invoice))

        val sut = InvoiceOverviewQueryServiceImpl(invoiceRepository, em)
        val result = sut.findAllOverview()

        // The column shows what the document says — a German admin looking at an English
        // invoice's row sees the English payment-term name, not a German one. Documented and
        // intended per PO decision 2 (2026-09-06), not a bug to "fix" later.
        assertEquals("Net 14 days", result[0].paymentLabel)
    }

    @Test
    fun `findAllOverview falls back to the sender profile and its billing address when no seller is selected`() {
        val invoiceRepository = mock<InvoiceRepository>()
        val em = mock<EntityManager>()

        val invoice = Invoice().apply {
            // invoiceNumber left at InvoiceNumber.UNASSIGNED (blank) on purpose — a draft never sent.
            senderProfile = senderWithBillingCity("Default Sender", "Hamburg")
            paymentTermsDays = 30
            totals.grossAmount = Money(BigDecimal("0.00"))
        }
        whenever(invoiceRepository.findAll(0, 60)).thenReturn(listOf(invoice))

        val sut = InvoiceOverviewQueryServiceImpl(invoiceRepository, em)
        val dto = sut.findAllOverview().single()

        assertEquals("", dto.invoiceNumber, "blank invoice number must surface as empty string, not null")
        assertEquals("Default Sender", dto.sellerName)
        assertEquals("Hamburg", dto.sellerCity)
        assertNull(dto.recipientName)
        assertEquals("30", dto.paymentLabel, "no payment term entity -> falls back to the raw days count")
        assertEquals(0, dto.itemsCount)
    }

    @Test
    fun `findAllOverview prefers the selected seller for the name but falls back to the sender profile for the city when the seller has no address of its own`() {
        val invoiceRepository = mock<InvoiceRepository>()
        val em = mock<EntityManager>()

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("INV-2025-03-00003")
            selectedSeller = SenderProfile().apply { name = "Seller Ohne Adresse" } // no billing address
            senderProfile = senderWithBillingCity("Fallback Sender", "München")
            totals.grossAmount = Money(BigDecimal("5.00"))
        }
        whenever(invoiceRepository.findAll(0, 60)).thenReturn(listOf(invoice))

        val sut = InvoiceOverviewQueryServiceImpl(invoiceRepository, em)
        val dto = sut.findAllOverview().single()

        assertEquals("Seller Ohne Adresse", dto.sellerName, "selectedSeller wins for the name even without its own address")
        assertEquals("München", dto.sellerCity, "city falls back to senderProfile's address when selectedSeller has none")
    }

    @Test
    fun `findAllOverview returns empty seller and recipient fields when neither seller nor sender profile is set`() {
        val invoiceRepository = mock<InvoiceRepository>()
        val em = mock<EntityManager>()

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("INV-2025-04-00004")
            totals.grossAmount = Money(BigDecimal("1.00"))
        }
        whenever(invoiceRepository.findAll(0, 60)).thenReturn(listOf(invoice))

        val sut = InvoiceOverviewQueryServiceImpl(invoiceRepository, em)
        val dto = sut.findAllOverview().single()

        assertNull(dto.sellerName)
        assertNull(dto.sellerCity)
        assertNull(dto.recipientName)
        assertEquals("14", dto.paymentLabel, "Invoice's default paymentTermsDays (14) when nothing else is set")
    }

    @Test
    fun `boundary - findAllOverview prefers the frozen payment term snapshot over the live payment term and days`() {
        val invoiceRepository = mock<InvoiceRepository>()
        val em = mock<EntityManager>()

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("INV-2025-05-00005")
            paymentTerm = PaymentTerm().apply { setName(germanFallbackLanguage(), "Net 14") }
            paymentTermsDays = 30
            paymentTermSnapshot = InvoicePaymentTermSnapshot().apply { name = "14 Tage netto (eingefroren)" }
            totals.grossAmount = Money(BigDecimal("1.00"))
        }
        whenever(invoiceRepository.findAll(0, 60)).thenReturn(listOf(invoice))

        val sut = InvoiceOverviewQueryServiceImpl(invoiceRepository, em)
        val dto = sut.findAllOverview().single()

        assertEquals("14 Tage netto (eingefroren)", dto.paymentLabel, "the frozen snapshot wins over the live PaymentTerm and the raw days")
    }

    // ─── findOverview cache read path ─────────────────────────────────────────
    // A cache hit returns before ever touching `em` — no Criteria mocking needed, only a plain
    // "never interacted with em at all" check.

    @Test
    fun `findOverview returns the cached rows on a valid cache hit, ohne die Datenbank erneut abzufragen`() {
        val invoiceRepository = mock<InvoiceRepository>()
        val em = mock<EntityManager>()
        val cache = mock<de.littletrotter.application.cache.InvoiceCachePort>()
        val objectMapper = mock<ObjectMapper>()

        val cachedDto = InvoiceOverviewDto(
            id = 1L, invoiceNumber = "INV-CACHE", typeCode = null, sellerName = "Cached Seller",
            sellerCity = null, recipientName = null, grossAmount = Money.of("42.00"), paymentLabel = null,
            freeText = null, itemsCount = 0, invoiceDate = LocalDate.of(2025, 1, 1),
            status = InvoiceStatus.DRAFT, validationStatus = ValidationStatus.UNAVAILABLE,
        )
        whenever(cache.getCachedOverview(org.mockito.kotlin.any())).thenReturn("""[{"id":1}]""")
        whenever(
            objectMapper.readValue(
                org.mockito.kotlin.any<String>(),
                org.mockito.kotlin.any<com.fasterxml.jackson.core.type.TypeReference<List<InvoiceOverviewDto>>>(),
            ),
        ).thenReturn(listOf(cachedDto))

        val sut = InvoiceOverviewQueryServiceImpl(invoiceRepository, em, cache, objectMapper)
        val result = sut.findOverview(0, 10, null, null)

        assertEquals(listOf(cachedDto), result)
        verifyNoInteractions(em)
    }

    // ─── countOverview(filterText, status, reverseCharge = null) ──────────────
    // The only countOverview overload that never needs a Criteria query of its own — a pure
    // delegation to the repository-level (already Criteria-based, see PanacheInvoiceRepository)
    // two-parameter count.

    // ─── overviewCacheKey — default-argument overload of the public companion helper ───────────
    // Sort/filter dimensions now bundled into InvoiceOverviewSort and this file's own
    // (internal) OverviewFilters (see findOverview); the default-value paths of this @JvmStatic
    // helper are otherwise never exercised at all.

    @Test
    fun `overviewCacheKey omits optional filter dimensions from the key when called with only the required params`() {
        val key = InvoiceOverviewQueryServiceImpl.overviewCacheKey(
            0, 10, InvoiceOverviewSort(InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC),
        )
        assertEquals("overview:0:10:::INVOICE_DATE:DESC::::::", key)
    }

    @Test
    fun `overviewCacheKey includes every optional filter dimension when all are supplied`() {
        val key = InvoiceOverviewQueryServiceImpl.overviewCacheKey(
            0, 10, InvoiceOverviewSort(InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC),
            InvoiceOverviewQueryServiceImpl.OverviewFilters(
                filterText = "acme", status = InvoiceStatus.DRAFT,
                reverseCharge = true, validationStatus = ValidationStatus.VALID, recipientId = 5L, senderProfileId = 7L,
                dateFrom = LocalDate.of(2025, 1, 1), dateTo = LocalDate.of(2025, 12, 31),
            ),
        )
        assertEquals("overview:0:10:acme:DRAFT:INVOICE_DATE:DESC:true:VALID:5:7:2025-01-01:2025-12-31", key)
    }

    @Test
    fun `countOverview with reverseCharge=null delegates to the two argument count`() {
        val invoiceRepository = mock<InvoiceRepository>()
        val em = mock<EntityManager>()
        whenever(invoiceRepository.countForOverview("acme", InvoiceStatus.DRAFT)).thenReturn(7L)

        val sut = InvoiceOverviewQueryServiceImpl(invoiceRepository, em)
        val count = sut.countOverview("acme", InvoiceStatus.DRAFT, null)

        assertEquals(7L, count)
        org.mockito.kotlin.verify(invoiceRepository).countForOverview("acme", InvoiceStatus.DRAFT)
    }
}
