package de.littletrotter.infrastructure.persistence

import de.littletrotter.test.MockEntityManager
import de.littletrotter.test.Row
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.test.FIXED_TIME_SOURCE

class InvoiceReadRepositoryImplTest {

    private val metrics = InvoiceReadMetrics(SimpleMeterRegistry())

    @Test
    fun `findInvoiceDetailDto assembles dto from queries`() {
        val invoiceId = 1L
        val header: Row = headerRow(invoiceId)
        val items: List<Row> = listOf(
            arrayOf(
                11L, "1", "d", BigDecimal("2"), "H", BigDecimal("50"), BigDecimal("19"), 1L, BigDecimal("100"), BigDecimal("19"), BigDecimal("119"), 0L, "SERVICE", 0, "LS-2025-0042",
                // appended: itemDescription, articleRefId, exemptionReasonText, taxLegalBasis, taxCategory.
                null, null, null, null, "S",
                // Appended: tripDetailedOnInvoice, tripFixedNetPrice, servicePeriodFrom, servicePeriodTo
                // (this fixture's own item is a plain SERVICE line, not a TRIP one).
                false, null, null, null,
                // buyerItemDescription — appended at the end of the item projection.
                null,
            ),
        )
        val job: List<Row> = listOf(arrayOf(1001L, "COMPLETED", OffsetDateTime.now(), "{}", "r", null))

        val em = MockEntityManager(header = listOf(header), items = items, jobs = job)
        val repo = InvoiceReadRepositoryImpl(em, metrics, time = FIXED_TIME_SOURCE)

        val dto = repo.findInvoiceDetailDto(invoiceId)
        assertNotNull(dto)
        assertEquals("RE-1", dto?.invoiceNumber)
        assertEquals(1001L, dto?.latestGenerationJob?.id)
        assertEquals(7L, dto?.version)
        assertEquals("Senderweg 1", dto?.billingAddress?.street)
        assertEquals("LS-2025-0042", dto?.items?.single()?.deliveryNoteReference)
        // the cancellation dialog decides on this value whether to offer the English reason
        // field, so it has to survive the projection rather than defaulting to "de".
        assertEquals("en", dto?.documentLanguage)
        // Without these three the PDF footer was incomplete (registerCourt was already in the
        // projection but was not carried over in dtoToInvoice).
        assertEquals("AG Berlin", dto?.sender?.registerCourt)
        assertEquals("Marty McFly", dto?.sender?.managingDirector)
        assertEquals("Postbank", dto?.sender?.bankName)
        assertEquals("PBNKDEFFXXX", dto?.sender?.bic)
        assertEquals("PO-2026-001", dto?.buyerReference)
        assertEquals("BST-2026-0042", dto?.purchaseOrderReference)
        assertEquals("ANG-2026-0007", dto?.orderReference)
        assertEquals(LocalDate.of(2026, 5, 20), dto?.orderReferenceDate)
        assertEquals("PROJ-77", dto?.projectNumber)
        // The send history renders from these two alone (H-7), with no call into the mail
        // component on the detail view's render path.
        assertEquals(true, dto?.mailSent)
        assertEquals(OffsetDateTime.parse("2026-09-07T10:15:30Z"), dto?.mailSentAt)
        assertEquals(OffsetDateTime.parse("2026-09-01T08:00:00Z"), dto?.createdAt)
        assertEquals(de.littletrotter.shared.Currency.CHF.ordinal, dto?.currency)
        assertEquals("DE21701500000585916554", dto?.sender?.iban)
        assertEquals(
            de.littletrotter.domain.shared.InvoiceFormat.INTERNATIONAL.ordinal,
            dto?.recipient?.invoiceFormat,
            "die Rechnungsart des Kunden reicht bis in das Detail-DTO",
        )
        assertEquals("AG Berlin", dto?.sender?.registerCourt)
        // fix/mustang-kmu-leitweg-v2 — sender no longer carries a Leitweg-ID (BT-10 identifies
        // the buyer, never the seller); the recipient's routingId at its post-removal index
        // (24, was 25) must still map correctly.
        assertNull(dto?.sender?.routingId)
        assertEquals("992-XYZ", dto?.recipient?.routingId)
        // the fields the UI now reads instead of reconstructing a domain Invoice.
        assertEquals(0, dto?.validationStatus)
        assertEquals(0, dto?.invoiceFormat)
        assertNull(dto?.sellerVatRegistrationCountry)
        assertEquals(7L, dto?.paymentTermId)
        assertNull(dto?.recipient?.b2bProfileId)
        assertEquals(0, dto?.typeCode)
        assertNull(dto?.documentGenerationFailure)
    }

    @Test
    fun `findInvoiceDetailDto maps the failed job's lastError of an issued invoice`() {
        val invoiceId = 1L
        val failedJob: List<Row> = listOf(
            arrayOf(1002L, "FAILED", OffsetDateTime.now(), "{}", null, "Typst compile failed\nstack"),
        )
        val sent = de.littletrotter.domain.invoice.InvoiceStatus.ISSUED.ordinal
        val em = MockEntityManager(header = listOf(headerRow(invoiceId, sent)), items = emptyList(), jobs = failedJob)
        val repo = InvoiceReadRepositoryImpl(em, metrics, time = FIXED_TIME_SOURCE)

        val dto = repo.findInvoiceDetailDto(invoiceId)
        assertEquals("Typst compile failed", dto?.documentGenerationFailure?.reason)
    }

    private fun headerRow(invoiceId: Long, status: Int = 0): Row = arrayOf(
            invoiceId,
            "RE-1",
            status,
            LocalDate.now(),
            BigDecimal("100"),
            BigDecimal("19"),
            BigDecimal("119"),
            10L,
            "S",
            "TAX-123",
            "DE123456789",
            "sender@example.org",
            "+49-111",
            "HRB 42",
            "Berlin",
            "AG Berlin",
            "DE21701500000585916554",
            20L,
            "R",
            "CUST-001",
            "DE987654321",
            "recipient@example.org",
            "+49-222",
            "Max Mustermann",
            "992-XYZ",
            7L,
            LocalDate.now().plusDays(14),
            null,
            null,
            "Bitte überweisen",
            null,
            "SELLER_DEFAULT",
            null,
            "SELLER_DEFAULT",
            false,
            "30 Tage netto",
            // Skonto — snapshotDiscountPercent, snapshotDiscountDays, termDiscountPercent,
            // termDiscountDays, hasPaymentTermSnapshot; this fixture's invoice has no payment
            // term at all, so InvoiceDetailCashDiscountFieldMapper.map() returns null.
            null,
            null,
            null,
            null,
            false,
            301L,
            "Senderweg 1",
            "10115",
            "Berlin",
            "DE",
            401L,
            "Lieferweg 2",
            "50667",
            "Koeln",
            "DE",
            "PO-2026-001",
            // BT-13 — its own column in the projection, split off from BT-10.
            "BST-2026-0042",
            "ANG-2026-0007",
            LocalDate.of(2026, 5, 20),
            de.littletrotter.shared.Currency.CHF.ordinal,
            // BT-11 — projectNumber, added to the projection (ProjectNumberModule wiring).
            "PROJ-77",
            // Indices 46..48 — mandatory fields for the footer and payment block, appended at
            // the end of the position-based projection.
            "Marty McFly",
            "Postbank",
            "PBNKDEFFXXX",
            // appended at the end of the header projection:
            // validationStatus, invoiceFormat, sellerVatRegistrationCountry, paymentTermId,
            // recipientB2bProfileId.
            0,
            0,
            null,
            7L,
            null,
            // PO 04.09.2026 — recipientInvoiceFormat, the customer's own "deutsche/internationale
            // Rechnung" setting the print-time template choice follows.
            de.littletrotter.domain.shared.InvoiceFormat.INTERNATIONAL,
            // typeCode — appended at the end of the header projection since the Cancellation/VOIDED
            // work (InvoiceReadRepositoryImpl); 0 = InvoiceTypeCode.COMMERCIAL_INVOICE.
            0,
            // documentLanguage, appended after typeCode. The projection is positional:
            // a column added there without a value added here shifts nothing, it simply runs
            // off the end of this row.
            "en",
            // mailSent / mailSentAt, the mail send read model (H-7). Both nullable, and
            // null is the normal state of an instance without the mail component; a non-null pair
            // is asserted further down so the projection is proven to carry it either way.
            true,
            OffsetDateTime.parse("2026-09-07T10:15:30Z"),
            // createdAt, appended last (HALFTER H3): the creation timestamp the cockpit shows.
            OffsetDateTime.parse("2026-09-01T08:00:00Z"),
        )

    /**
     * one positional row matching [findInvoiceDetailDto]'s header projection's exact
     * `AS alias` order (see [de.littletrotter.test.MockEntityManager]'s KDoc), with every column
     * defaulted to the same values the `findInvoiceDetailDto assembles dto from queries` fixture
     * above uses. Lets the boundary tests below override only the handful of columns whose raw
     * projection *type* — not value — decides which `parseXxx` branch runs, without repeating all
     * 64 positions each time.
     */
    private fun headerRow(
        invoiceNumber: Any? = "RE-1",
        status: Any? = 0,
        netAmount: Any? = BigDecimal("100"),
        vatAmount: Any? = BigDecimal("19"),
        grossAmount: Any? = BigDecimal("119"),
        currency: Any? = de.littletrotter.shared.Currency.CHF.ordinal,
        validationStatus: Any? = 0,
        invoiceFormat: Any? = 0,
        typeCode: Any? = 0,
        version: Any? = 7L,
    ): Row = arrayOf(
        1L, invoiceNumber, status, LocalDate.now(), netAmount, vatAmount, grossAmount,
        10L, "S", "TAX-123", "DE123456789", "sender@example.org", "+49-111",
        "HRB 42", "Berlin", "AG Berlin", "DE21701500000585916554",
        20L, "R", "CUST-001", "DE987654321", "recipient@example.org",
        "+49-222", "Max Mustermann", "992-XYZ",
        version, LocalDate.now().plusDays(14), null, null, "Bitte überweisen", null, "SELLER_DEFAULT", null, "SELLER_DEFAULT",
        false, "30 Tage netto",
        // Skonto — snapshotDiscountPercent, snapshotDiscountDays, termDiscountPercent,
        // termDiscountDays, hasPaymentTermSnapshot (see the literal header fixture above).
        null, null, null, null, false,
        301L, "Senderweg 1", "10115", "Berlin", "DE",
        401L, "Lieferweg 2", "50667", "Koeln", "DE",
        "PO-2026-001", "BST-2026-0042", "ANG-2026-0007", LocalDate.of(2026, 5, 20), currency, "PROJ-77",
        "Marty McFly", "Postbank", "PBNKDEFFXXX",
        validationStatus, invoiceFormat, null, 7L, null,
        de.littletrotter.domain.shared.InvoiceFormat.INTERNATIONAL, typeCode, "en", true,
        OffsetDateTime.parse("2026-09-07T10:15:30Z"), OffsetDateTime.parse("2026-09-01T08:00:00Z"),
    )

    @Test
    fun `boundary - a null header projection decodes to the empty-string, DRAFT and null defaults`() {
        val em = MockEntityManager(
            header = listOf(
                headerRow(
                    invoiceNumber = null, status = null, currency = null,
                    validationStatus = null, invoiceFormat = null, typeCode = null,
                ),
            ),
        )
        val repo = InvoiceReadRepositoryImpl(em, metrics, time = FIXED_TIME_SOURCE)

        val dto = repo.findInvoiceDetailDto(1L)

        assertNotNull(dto)
        assertEquals("", dto?.invoiceNumber, "a null invoiceNumber cell must decode to the empty string, not crash")
        assertEquals(
            de.littletrotter.domain.invoice.InvoiceStatus.DRAFT.ordinal,
            dto?.status,
            "a null status cell must default to DRAFT",
        )
        assertNull(dto?.currency)
        assertNull(dto?.validationStatus)
        assertNull(dto?.invoiceFormat)
        assertNull(dto?.typeCode)
    }

    @Test
    fun `boundary - a name or numeric-string status, currency and ordinal cell decodes tolerantly`() {
        val em = MockEntityManager(
            header = listOf(
                headerRow(
                    invoiceNumber = 42L,
                    // Neither Int-parseable nor an InvoiceStatus instance — must resolve by enum name.
                    status = "ISSUED",
                    currency = "1",
                    validationStatus = "0",
                    netAmount = 100,
                    vatAmount = "19.00",
                ),
            ),
        )
        val repo = InvoiceReadRepositoryImpl(em, metrics, time = FIXED_TIME_SOURCE)

        val dto = repo.findInvoiceDetailDto(1L)

        assertNotNull(dto)
        assertEquals("42", dto?.invoiceNumber, "a non-String, non-InvoiceNumber cell falls back to toString()")
        assertEquals(
            de.littletrotter.domain.invoice.InvoiceStatus.ISSUED.ordinal,
            dto?.status,
            "a status name string must resolve via InvoiceStatus.entries, not just toIntOrNull()",
        )
        assertEquals(1, dto?.currency)
        assertEquals(0, dto?.validationStatus)
        assertEquals(de.littletrotter.shared.Money(BigDecimal("100")), dto?.netAmount, "an Int amount cell still decodes via toString().toBigDecimalOrNull()")
        assertEquals(de.littletrotter.shared.Money(BigDecimal("19.00")), dto?.vatAmount, "a String amount cell decodes via toBigDecimalOrNull()")
    }

    @Test
    fun `boundary - an unresolvable status name and a wholly unexpected cell type both fall back to their else branch`() {
        val emUnresolvable = MockEntityManager(
            header = listOf(headerRow(status = "NOT_A_STATUS", currency = true, validationStatus = true, grossAmount = true)),
        )
        val dtoUnresolvable = InvoiceReadRepositoryImpl(emUnresolvable, metrics, time = FIXED_TIME_SOURCE).findInvoiceDetailDto(1L)
        assertEquals(
            de.littletrotter.domain.invoice.InvoiceStatus.DRAFT.ordinal,
            dtoUnresolvable?.status,
            "a status string matching no InvoiceStatus name must still default to DRAFT",
        )
        assertNull(dtoUnresolvable?.currency, "a currency cell of an unexpected type must decode to null")
        assertNull(dtoUnresolvable?.validationStatus, "a validationStatus cell of an unexpected type must decode to null")
        assertNull(dtoUnresolvable?.grossAmount, "a grossAmount cell of an unexpected type must decode to null")

        val emElseStatus = MockEntityManager(header = listOf(headerRow(status = true)))
        val dtoElseStatus = InvoiceReadRepositoryImpl(emElseStatus, metrics, time = FIXED_TIME_SOURCE).findInvoiceDetailDto(1L)
        assertEquals(
            de.littletrotter.domain.invoice.InvoiceStatus.DRAFT.ordinal,
            dtoElseStatus?.status,
            "a status cell that is neither Number, InvoiceStatus nor String must default to DRAFT via the else branch",
        )
    }

    // the raw Tuple cell for a converter-backed enum column comes back as the converted
    // entity-attribute type (the enum instance itself, not its ordinal) whenever Hibernate applies
    // the AttributeConverter to a scalar JPQL path select; the `is InvoiceNumber`/`is InvoiceStatus`/
    // `is Currency`/`is Enum<*>` arms below are exactly that real-world shape, distinct from the
    // tolerant Int/String fallbacks the other boundary tests already cover.
    @Test
    fun `boundary - a converter-decoded enum or value-object cell is read via its own instance branch`() {
        val em = MockEntityManager(
            header = listOf(
                headerRow(
                    invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-0009"),
                    status = de.littletrotter.domain.invoice.InvoiceStatus.ISSUED,
                    currency = de.littletrotter.shared.Currency.EUR,
                    validationStatus = de.littletrotter.domain.shared.ValidationStatus.VALID,
                ),
            ),
        )
        val repo = InvoiceReadRepositoryImpl(em, metrics, time = FIXED_TIME_SOURCE)

        val dto = repo.findInvoiceDetailDto(1L)

        assertEquals("RE-2026-0009", dto?.invoiceNumber, "an InvoiceNumber cell decodes via its own instance branch")
        assertEquals(
            de.littletrotter.domain.invoice.InvoiceStatus.ISSUED.ordinal,
            dto?.status,
            "an InvoiceStatus cell decodes via its own instance branch",
        )
        assertEquals(de.littletrotter.shared.Currency.EUR.ordinal, dto?.currency, "a Currency cell decodes via its own instance branch")
        assertEquals(
            de.littletrotter.domain.shared.ValidationStatus.VALID.ordinal,
            dto?.validationStatus,
            "a converter-backed enum cell decodes via the generic Enum<*> instance branch",
        )
    }

    // -- the header's own `version` cell and several of the ITEMS projection's genuinely
    // optional columns (unit, taxRateRefId, itemVersion, category -- unlike the -marked
    // non-nullable ones right next to them) each have their own `?.`/`as? Number?` null-safe-call
    // branch that every other fixture in this file always sets to a non-null value.
    @Test
    fun `boundary - a null header version and null-optional item columns decode without throwing`() {
        val em = MockEntityManager(
            header = listOf(headerRow(version = null)),
            items = listOf(
                arrayOf(
                    12L, "1", "d", BigDecimal("2"), null, BigDecimal("50"), BigDecimal("19"), null,
                    BigDecimal("100"), BigDecimal("19"), BigDecimal("119"), null, null, 0, "LS-2025-0042",
                    null, null, null, null, null,
                    false, null, null, null,
                    null,
                ),
            ),
        )
        val repo = InvoiceReadRepositoryImpl(em, metrics, time = FIXED_TIME_SOURCE)

        val dto = repo.findInvoiceDetailDto(1L)

        assertNotNull(dto)
        assertNull(dto?.version, "a null version cell must decode to null, not throw")
        val item = dto?.items?.single()
        assertNull(item?.unit, "a null unit cell must decode to null")
        assertNull(item?.taxRateRefId, "a null taxRateRefId cell must decode to null")
        assertNull(item?.version, "a null item version cell must decode to null")
        assertNull(item?.category, "a null category cell must decode to null, and never enter the TRIP branch")
    }

    @Test
    fun `returns null when header missing`() {
        val em = MockEntityManager(header = emptyList())
        val repo = InvoiceReadRepositoryImpl(em, metrics, time = FIXED_TIME_SOURCE)
        assertNull(repo.findInvoiceDetailDto(999L))
    }

    // AP-EXC-1 — the wrapper's own catch block (record the failure metric, log, rethrow
    // unchanged) is generic error-handling control flow, not a row-shape assumption about what
    // Postgres returns; a plain Mockito EntityManager double that fails outright exercises it
    // without pretending to know a real row shape (the persistence-adapter convention this
    // module follows for the query mapping itself does not apply to this path).
    @Test
    fun `findInvoiceDetailDto records a failure metric and rethrows unchanged when the query fails`() {
        val em = mock<EntityManager>()
        val failure = IllegalStateException("boom")
        whenever(em.createQuery(any<String>(), eq(jakarta.persistence.Tuple::class.java))).thenThrow(failure)
        val registry = SimpleMeterRegistry()
        val localMetrics = InvoiceReadMetrics(registry)
        val repo = InvoiceReadRepositoryImpl(em, localMetrics, time = FIXED_TIME_SOURCE)

        val thrown = assertThrows(IllegalStateException::class.java) { repo.findInvoiceDetailDto(1L) }

        assertEquals("boom", thrown.message)
        assertEquals(1.0, registry.get("invoice.read.failures").counter().count())
    }
}
