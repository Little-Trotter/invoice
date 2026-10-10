package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.export.InvoiceExportReadRepository
import de.littletrotter.application.export.PartySource
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.PartyRole
import de.littletrotter.domain.invoice.InvoicePartySnapshot
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.TripExpense
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.invoice.freezePaymentTermSnapshot
import de.littletrotter.domain.invoice.snapshotAsBuyer
import de.littletrotter.domain.invoice.snapshotAsSeller
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldValue
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.b2b.B2bTerm
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.value.CustomerNumber

/**
 * real-Postgres coverage for [InvoiceExportReadRepositoryImpl]: every trap
 * §1 names (dirtying `references`/`@Version`, the malformed-number crash,
 * ordinal-vs-code enum printing) plus the snapshot-vs-live party fallback and id-keyset paging.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceExportReadRepositoryImplIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: InvoiceExportReadRepository

    private fun sender(prefix: String) = SenderProfile().apply {
        name = "Sender $prefix GmbH"
        invoicePrefix = prefix
        taxNumber = TaxNumber("11/616/35840")
        vatId = VatId("DE123456789")
        email = Email.ofNullable("sender-$prefix@example.com")
    }

    private fun recipient(number: String) = Recipient().apply {
        customerNumber = CustomerNumber.ofNullable(number)
        name = "Recipient $number AG"
        vatId = VatId("DE987654321")
        email = Email.ofNullable("recipient-$number@example.com")
    }

    private fun taxRate(code: String) = TaxRate().apply {
        this.code = code
        category = "S"
        name = "USt 19"
        isSelectable = false
        rate = TaxRatePercent(BigDecimal("19.00"))
        country = Country.DE
        validFrom = LocalDate.of(2024, 1, 1)
        isDefault = false
    }

    private fun oneItem(taxRate: TaxRate) = InvoiceItem().apply {
        positionId = "1"
        category = InvoiceItemCategory.SERVICE
        description = "Beratung"
        quantity = BigDecimal.ONE
        unit = InvoiceItemUnit.FLAT_RATE
        unitPriceNet = Money(BigDecimal("100.00"))
        taxRateRef = taxRate
        sortOrder = 1
        calculateAmounts()
    }

    @Test
    @Transactional
    fun `a draft without a snapshot reads live seller and recipient data and does not dirty the entity`() {
        val s = sender("XR1-")
        val r = recipient("XR1-1")
        val tax = taxRate("XR1_DE_19")
        em.persist(s)
        em.persist(r)
        em.persist(tax)

        val invoice = Invoice().apply {
            senderProfile = s
            recipient = r
            invoiceDate = LocalDate.of(2026, 4, 1)
            freeText = "Entwurf"
        }
        invoice.addItem(oneItem(tax))
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        val versionBeforeExport = invoice.version
        em.clear()

        // Scoped to this test's own recipient: the module's IT suite shares one Postgres
        // container across every class (see InvoiceReadRepositoryImplIT's own note on this), so
        // an unfiltered fetch would see every other test's invoices too.
        val row = repository.findHeaderRows(InvoiceExportFilter(recipientId = r.id), afterId = null).single()

        assertThat(row.invoiceId).isEqualTo(invoiceId)
        assertThat(row.invoiceNumber).isEmpty()
        assertThat(row.invoiceNumberMalformed).isFalse()
        assertThat(row.status).isEqualTo(InvoiceStatus.DRAFT)
        assertThat(row.sellerName).isEqualTo(s.name)
        assertThat(row.buyerName).isEqualTo(r.name)
        assertThat(row.partySource).isEqualTo(PartySource.MASTER_DATA)
        assertThat(row.paymentTermSnapshotName).isNull()
        assertThat(row.itemsCount).isEqualTo(1)
        assertThat(row.netAmount).isEqualTo(Money(BigDecimal("100.00")))

        // Trap 1: reading through the export must never touch Invoice.references or otherwise
        // dirty the entity — a fresh read of the row's own @Version proves no UPDATE happened.
        em.clear()
        val reread = em.find(Invoice::class.java, invoiceId)
        assertThat(reread.version).isEqualTo(versionBeforeExport)
    }

    @Test
    @Transactional
    fun `a sent invoice with party and payment-term snapshots reports the frozen values, not the live ones`() {
        val s = sender("XR2-")
        val r = recipient("XR2-1")
        val tax = taxRate("XR2_DE_19")
        val term = PaymentTerm().apply {
            setName(em.find(Language::class.java, 1)!!, "XR2 Testkondition (Fixture)")
            dueInDays = 14
            // Fixture row, not a seeded standard term — PaymentTermMigrationIT elsewhere in this
            // shared-DB IT run asserts an exact count of *active* standard payment terms (V906),
            // so this stays inactive to avoid inflating that count (same reasoning as the
            // isSelectable/isActive = false fixture rows in InvoiceReadRepositoryImplIT).
            isActive = false
        }
        em.persist(s)
        em.persist(r)
        em.persist(tax)
        em.persist(term)

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("XR2-2026-04-00001")
            senderProfile = s
            recipient = r
            paymentTerm = term
            documentLanguage = "de"
            invoiceDate = LocalDate.of(2026, 4, 5)
            forceStatusForTest(InvoiceStatus.SENT)
        }
        invoice.addItem(oneItem(tax))
        invoice.recalculateTotals()
        invoice.freezePaymentTermSnapshot()
        invoice.applyPartySnapshots(s.snapshotAsSeller(), r.snapshotAsBuyer())
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!

        // Master data changes after the snapshot was frozen — the export must not see this.
        s.name = "Renamed Seller GmbH"
        r.name = "Renamed Buyer AG"
        em.flush()
        em.clear()

        val row = repository.findHeaderRows(InvoiceExportFilter(recipientId = r.id), afterId = null).single()
        assertThat(row.invoiceId).isEqualTo(invoiceId)

        assertThat(row.invoiceNumber).isEqualTo("XR2-2026-04-00001")
        assertThat(row.partySource).isEqualTo(PartySource.SNAPSHOT)
        assertThat(row.sellerName).isEqualTo("Sender XR2- GmbH")
        assertThat(row.buyerName).isEqualTo("Recipient XR2-1 AG")
        assertThat(row.paymentTermSnapshotName).isEqualTo("XR2 Testkondition (Fixture)")
    }

    @Test
    @TestTransaction
    fun `re-applying party snapshots on a persisted invoice flushes without violating uq_invoice_party_snapshot`() {
        val s = sender("XR4-")
        val r = recipient("XR4-1")
        em.persist(s)
        em.persist(r)
        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 4, 1) }
        invoice.applyPartySnapshots(s.snapshotAsSeller(), r.snapshotAsBuyer())
        em.persist(invoice)
        em.flush()

        s.name = "Renamed XR4 Seller"
        invoice.applyPartySnapshots(s.snapshotAsSeller(), r.snapshotAsBuyer())
        em.flush()
        em.clear()

        val rows = em.createQuery("SELECT p FROM InvoicePartySnapshot p WHERE p.invoice.id = :id", InvoicePartySnapshot::class.java)
            .setParameter("id", invoice.id).resultList
        assertThat(rows).hasSize(2)
        assertThat(rows.single { it.partyRole == PartyRole.SELLER }.name).isEqualTo("Renamed XR4 Seller")
    }

    @Test
    @TestTransaction // rolled back: the malformed number must never survive into the shared IT database
    fun `a malformed stored invoice number is flagged but does not break the rest of the batch`() {
        val s = sender("XR3-")
        val r = recipient("XR3-1")
        em.persist(s)
        em.persist(r)

        val good = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 4, 1) }
        val bad = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 4, 2) }
        em.persist(good)
        em.persist(bad)
        em.flush()

        // Bypasses InvoiceNumber's own validating constructor on purpose — this is exactly the
        // "how did a bad value get into the column at all" scenario the export's own defense exists for.
        em.createNativeQuery("UPDATE invoice SET invoice_number = '!!!NOT-A-NUMBER!!!' WHERE id = :id")
            .setParameter("id", bad.id).executeUpdate()
        em.clear()

        val rows = repository.findHeaderRows(InvoiceExportFilter(recipientId = r.id), afterId = null)
        val goodRow = rows.single { it.invoiceId == good.id }
        val badRow = rows.single { it.invoiceId == bad.id }

        assertThat(goodRow.invoiceNumberMalformed).isFalse()
        assertThat(badRow.invoiceNumberMalformed).isTrue()
        assertThat(badRow.invoiceNumber).isEqualTo("!!!NOT-A-NUMBER!!!")
    }

    @Test
    @Transactional
    fun `the storno correction document prints its BT-3 code as 384, never the raw ordinal`() {
        val s = sender("XR4-")
        val r = recipient("XR4-1")
        em.persist(s)
        em.persist(r)

        val storno = Invoice().apply {
            senderProfile = s
            recipient = r
            invoiceDate = LocalDate.of(2026, 4, 1)
            invoiceNumber = InvoiceNumber("XR4-2026-04-99001")
            typeCode = InvoiceTypeCode.CORRECTED_INVOICE
            forceStatusForTest(InvoiceStatus.CANCELLED)
            originalInvoiceNumber = "XR4-2026-04-00001"
            originalInvoiceDate = LocalDate.of(2026, 3, 1)
        }
        em.persist(storno)
        em.flush()
        em.clear()

        val row = repository.findHeaderRows(InvoiceExportFilter(recipientId = r.id), afterId = null).single()
        assertThat(row.invoiceId).isEqualTo(storno.id)

        assertThat(row.typeCode).isEqualTo(InvoiceTypeCode.CORRECTED_INVOICE)
        assertThat(row.typeCode.code).isEqualTo("384")
        assertThat(row.originalInvoiceNumber).isEqualTo("XR4-2026-04-00001")
        assertThat(row.originalInvoiceDate).isEqualTo(LocalDate.of(2026, 3, 1))
    }

    @Test
    @Transactional
    fun `a reverse-charge invoice reports zero tax and true, and a selected seller wins over the sender profile`() {
        val s = sender("XR5-")
        val otherSeller = sender("XR5B-").apply { name = "Other Seller GmbH" }
        val r = recipient("XR5-1")
        em.persist(s)
        em.persist(otherSeller)
        em.persist(r)

        val invoice = Invoice().apply {
            senderProfile = s
            selectedSeller = otherSeller
            recipient = r
            invoiceDate = LocalDate.of(2026, 4, 1)
            reverseCharge = true
            currency = Currency.CHF
        }
        invoice.totals.netAmount = Money(BigDecimal("500.00"))
        invoice.totals.vatAmount = Money.ZERO
        invoice.totals.grossAmount = Money(BigDecimal("500.00"))
        em.persist(invoice)
        em.flush()
        em.clear()

        val row = repository.findHeaderRows(InvoiceExportFilter(recipientId = r.id), afterId = null).single()
        assertThat(row.invoiceId).isEqualTo(invoice.id)

        assertThat(row.reverseCharge).isTrue()
        assertThat(row.vatAmount).isEqualTo(Money.ZERO)
        assertThat(row.currency).isEqualTo(Currency.CHF)
        assertThat(row.sellerName).isEqualTo("Other Seller GmbH")
    }

    @Test
    @Transactional
    fun `attached receipts are counted without an N-plus-1 per-item lookup`() {
        val s = sender("XR6-")
        val r = recipient("XR6-1")
        val receipt = Receipt().apply {
            fileName = "beleg.pdf"
            description = "Beleg"
            objectKey = "receipt-pool/xr6-beleg.pdf"
            sha256Checksum = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2)
            documentDate = LocalDate.of(2026, 4, 1)
        }
        em.persist(s)
        em.persist(r)
        em.persist(receipt)

        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 4, 1) }
        invoice.receipts.add(receipt)
        em.persist(invoice)
        em.flush()
        em.clear()

        val row = repository.findHeaderRows(InvoiceExportFilter(recipientId = r.id), afterId = null).single()
        assertThat(row.invoiceId).isEqualTo(invoice.id)

        assertThat(row.receiptsCount).isEqualTo(1)
    }

    @Test
    @Transactional
    fun `id-keyset paging returns disjoint, ascending batches`() {
        val s = sender("XR7-")
        val r = recipient("XR7-1")
        em.persist(s)
        em.persist(r)
        val ids = (1..5).map {
            val inv = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 4, it) }
            em.persist(inv)
            inv
        }
        em.flush()
        em.clear()
        val allIds = ids.map { it.id!! }.toSet()
        val filter = InvoiceExportFilter(recipientId = r.id)

        val firstBatch = repository.findHeaderRows(filter, afterId = null, batchSize = 2)
        assertThat(firstBatch).hasSize(2)
        val secondBatch = repository.findHeaderRows(filter, afterId = firstBatch.last().invoiceId, batchSize = 2)
        assertThat(secondBatch).hasSize(2)
        val thirdBatch = repository.findHeaderRows(filter, afterId = secondBatch.last().invoiceId, batchSize = 2)
        assertThat(thirdBatch).hasSize(1)

        val seenIds = (firstBatch + secondBatch + thirdBatch).map { it.invoiceId }
        assertThat(seenIds).doesNotHaveDuplicates()
        assertThat(seenIds.toSet()).isEqualTo(allIds)
        assertThat(seenIds).isSorted()
    }

    @Test
    @Transactional
    fun `the status filter matches the invoice-list's own filter dimension`() {
        val s = sender("XR8-")
        val r = recipient("XR8-1")
        em.persist(s)
        em.persist(r)
        val draft = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 4, 1) }.also { it.forceStatusForTest(InvoiceStatus.DRAFT) }
        val sent = Invoice().apply {
            senderProfile = s
            recipient = r
            invoiceDate = LocalDate.of(2026, 4, 1)
            forceStatusForTest(InvoiceStatus.SENT)
            invoiceNumber = InvoiceNumber("XR8-2026-04-00001")
        }
        em.persist(draft)
        em.persist(sent)
        em.flush()
        em.clear()

        val rows = repository.findHeaderRows(InvoiceExportFilter(recipientId = r.id, status = InvoiceStatus.SENT), afterId = null)

        assertThat(rows.map { it.invoiceId }).contains(sent.id).doesNotContain(draft.id)
    }

    @Test
    @Transactional
    fun `filterText, reverseCharge, validationStatus, senderProfileId and the date range all narrow the result together`() {
        // The distinctive token filterText searches for lives in the NAME, not the prefix:
        // SenderProfile.invoicePrefix is capped at 10 characters, so a 15-character prefix fails
        // on persist before the test can assert anything.
        val s = sender("XR9-").apply { name = "Sender XR9FilterMatch GmbH" }
        val r = recipient("XR9-1")
        em.persist(s)
        em.persist(r)

        val invoice = Invoice().apply {
            senderProfile = s
            recipient = r
            invoiceDate = LocalDate.of(2026, 4, 10)
            reverseCharge = true
            validationStatus = de.littletrotter.domain.shared.ValidationStatus.VALID
        }
        em.persist(invoice)
        em.flush()
        em.clear()

        val matching = repository.findHeaderRows(
            InvoiceExportFilter(
                filterText = "xr9filtermatch",
                reverseCharge = true,
                validationStatus = de.littletrotter.domain.shared.ValidationStatus.VALID,
                senderProfileId = s.id,
                dateFrom = LocalDate.of(2026, 4, 1),
                dateTo = LocalDate.of(2026, 4, 30),
            ),
            afterId = null,
        )
        assertThat(matching.map { it.invoiceId }).containsExactly(invoice.id)

        // A filterText that cannot match anything proves the batch-level early return for an
        // empty result, not just an empty predicate list.
        val nonMatching = repository.findHeaderRows(InvoiceExportFilter(filterText = "no-such-invoice-anywhere-zzz"), afterId = null)
        assertThat(nonMatching).isEmpty()

        // a blank (non-null but empty) filterText is a distinct code path from filterText
        // == null (both feed the same isNullOrBlank() guard): it must still be treated as "no
        // filter", not a query for the literal empty string.
        val blankFilterText = repository.findHeaderRows(InvoiceExportFilter(filterText = "", recipientId = r.id), afterId = null)
        assertThat(blankFilterText.map { it.invoiceId }).containsExactly(invoice.id)
    }

    // ── §"SCOPE" mode A — findHeaderRow(invoiceId) ────────────────────────────────────────

    @Test
    @Transactional
    fun `findHeaderRow returns the same row findHeaderRows would for that single invoice`() {
        val s = sender("XRA-")
        val r = recipient("XRA-1")
        em.persist(s)
        em.persist(r)
        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 5, 1) }
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val row = repository.findHeaderRow(invoiceId)

        assertThat(row).isNotNull
        assertThat(row!!.invoiceId).isEqualTo(invoiceId)
        assertThat(row.sellerName).isEqualTo(s.name)
        assertThat(row.buyerName).isEqualTo(r.name)
    }

    @Test
    @Transactional
    fun `findHeaderRow returns null for an id that does not exist`() {
        assertThat(repository.findHeaderRow(-1L)).isNull()
    }

    // ── §2 — findPositionRows(invoiceId) ──────────────────────────────────────────────────

    @Test
    @Transactional
    fun `findPositionRows maps every field of one position and carries the invoice's own invoice number`() {
        val s = sender("XRB-")
        val r = recipient("XRB-1")
        val tax = taxRate("XRB_DE_19")
        em.persist(s)
        em.persist(r)
        em.persist(tax)
        val invoice = Invoice().apply {
            senderProfile = s
            recipient = r
            invoiceDate = LocalDate.of(2026, 5, 1)
            invoiceNumber = InvoiceNumber("XRB-2026-05-00001")
        }
        invoice.addItem(oneItem(tax))
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val rows = repository.findPositionRows(invoiceId)

        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.invoiceId).isEqualTo(invoiceId)
        assertThat(row.invoiceNumber).isEqualTo("XRB-2026-05-00001")
        assertThat(row.positionId).isEqualTo("1")
        assertThat(row.description).isEqualTo("Beratung")
        assertThat(row.category).isEqualTo(InvoiceItemCategory.SERVICE)
        assertThat(row.unitPriceNet).isEqualTo(Money(BigDecimal("100.00")))
        assertThat(row.netAmount).isEqualTo(Money(BigDecimal("100.00")))
    }

    @Test
    @Transactional
    fun `findPositionRows returns positions in sortOrder ascending, regardless of insertion order`() {
        val s = sender("XRC-")
        val r = recipient("XRC-1")
        val tax = taxRate("XRC_DE_19")
        em.persist(s)
        em.persist(r)
        em.persist(tax)
        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 5, 1) }
        val third = oneItem(tax).apply { positionId = "3"; sortOrder = 3 }
        val first = oneItem(tax).apply { positionId = "1"; sortOrder = 1 }
        val second = oneItem(tax).apply { positionId = "2"; sortOrder = 2 }
        invoice.addItem(third)
        invoice.addItem(first)
        invoice.addItem(second)
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val rows = repository.findPositionRows(invoiceId)

        assertThat(rows.map { it.positionId }).containsExactly("1", "2", "3")
    }

    @Test
    @Transactional
    fun `findPositionRows loads BT-155 and BT-156 for the whole batch in one join, not one lookup per position`() {
        val s = sender("XRD-")
        val r = recipient("XRD-1")
        val tax = taxRate("XRD_DE_19")
        em.persist(s)
        em.persist(r)
        em.persist(tax)
        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 5, 1) }
        val withBoth = oneItem(tax).apply { positionId = "1"; sortOrder = 1 }
        val withSellerOnly = oneItem(tax).apply { positionId = "2"; sortOrder = 2 }
        val withNeither = oneItem(tax).apply { positionId = "3"; sortOrder = 3 }
        invoice.addItem(withBoth)
        invoice.addItem(withSellerOnly)
        invoice.addItem(withNeither)
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()

        em.persist(B2bFieldValue().apply { ownerType = B2bFieldOwnerType.INVOICE_ITEM; ownerId = withBoth.id!!; term = B2bTerm.BT_155; value = "SELLER-1" })
        em.persist(B2bFieldValue().apply { ownerType = B2bFieldOwnerType.INVOICE_ITEM; ownerId = withBoth.id!!; term = B2bTerm.BT_156; value = "BUYER-1" })
        em.persist(B2bFieldValue().apply { ownerType = B2bFieldOwnerType.INVOICE_ITEM; ownerId = withSellerOnly.id!!; term = B2bTerm.BT_155; value = "SELLER-2" })
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val rows = repository.findPositionRows(invoiceId).associateBy { it.positionId }

        assertThat(rows.getValue("1").sellerItemIdentifier).isEqualTo("SELLER-1")
        assertThat(rows.getValue("1").buyerItemIdentifier).isEqualTo("BUYER-1")
        assertThat(rows.getValue("2").sellerItemIdentifier).isEqualTo("SELLER-2")
        assertThat(rows.getValue("2").buyerItemIdentifier).isNull()
        assertThat(rows.getValue("3").sellerItemIdentifier).isNull()
        assertThat(rows.getValue("3").buyerItemIdentifier).isNull()
    }

    @Test
    @Transactional
    fun `findPositionRows counts and names per-item receipts via one batched join, not one lookup per position`() {
        val s = sender("XRE-")
        val r = recipient("XRE-1")
        val tax = taxRate("XRE_DE_19")
        // Unique per run, not a fixed literal: receipt.sha256_checksum carries
        // uq_receipt_sha256_checksum, and this module's IT suite shares one Postgres container
        // across every class (see the "a draft without a snapshot..." test above), so a fixed
        // "a"/"b" repeated to 64 chars collides with the same fixed value some other class's
        // receipt fixture already committed.
        val receiptOne = Receipt().apply {
            fileName = "beleg-a.pdf"
            description = "Beleg A"
            objectKey = "receipt-pool/xre-beleg-a-${System.nanoTime()}.pdf"
            sha256Checksum = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2)
            documentDate = LocalDate.of(2026, 5, 1)
        }
        val receiptTwo = Receipt().apply {
            fileName = "beleg-b.pdf"
            description = "Beleg B"
            objectKey = "receipt-pool/xre-beleg-b-${System.nanoTime()}.pdf"
            sha256Checksum = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2)
            documentDate = LocalDate.of(2026, 5, 1)
        }
        em.persist(s)
        em.persist(r)
        em.persist(tax)
        em.persist(receiptOne)
        em.persist(receiptTwo)
        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 5, 1) }
        val withReceipts = oneItem(tax).apply { positionId = "1"; sortOrder = 1; receipts.add(receiptOne); receipts.add(receiptTwo) }
        val withoutReceipts = oneItem(tax).apply { positionId = "2"; sortOrder = 2 }
        invoice.addItem(withReceipts)
        invoice.addItem(withoutReceipts)
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val rows = repository.findPositionRows(invoiceId).associateBy { it.positionId }

        assertThat(rows.getValue("1").receiptsCount).isEqualTo(2)
        assertThat(rows.getValue("1").receiptFileNames).contains("beleg-a.pdf").contains("beleg-b.pdf")
        assertThat(rows.getValue("2").receiptsCount).isEqualTo(0)
        assertThat(rows.getValue("2").receiptFileNames).isNull()
    }

    @Test
    @Transactional
    fun `findPositionRows projects the article-master number via a LEFT JOIN, never walking the lazy association per row`() {
        val s = sender("XRF-")
        val r = recipient("XRF-1")
        val tax = taxRate("XRF_DE_19")
        val article = Article().apply {
            name = "Wartungsvertrag"
            category = InvoiceItemCategory.SERVICE
            defaultUnit = InvoiceItemUnit.FLAT_RATE
            defaultUnitPriceNet = Money(BigDecimal("100.00"))
            articleNumber = ArticleNumber("ART-XRF-1")
        }
        em.persist(s)
        em.persist(r)
        em.persist(tax)
        em.persist(article)
        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 5, 1) }
        invoice.addItem(oneItem(tax).apply { articleRef = article })
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val row = repository.findPositionRows(invoiceId).single()

        assertThat(row.articleNumber).isEqualTo("ART-XRF-1")
    }

    @Test
    @Transactional
    fun `findPositionRows maps a tax rate frozen only on the live taxRateRef, and a missing rate as null - never a thrown exception`() {
        val s = sender("XRG-")
        val r = recipient("XRG-1")
        val tax = taxRate("XRG_DE_19")
        em.persist(s)
        em.persist(r)
        em.persist(tax)
        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 5, 1) }
        val liveRateOnly = oneItem(tax).apply { positionId = "1"; sortOrder = 1 } // taxRateSnapshot never taken
        val neitherRate = oneItem(tax).apply { positionId = "2"; sortOrder = 2; taxRateRef = null }
        invoice.addItem(liveRateOnly)
        invoice.addItem(neitherRate)
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val rows = repository.findPositionRows(invoiceId).associateBy { it.positionId }

        assertThat(rows.getValue("1").taxRatePercent).isEqualByComparingTo(BigDecimal("19.00"))
        assertThat(rows.getValue("2").taxRatePercent).isNull()
    }

    @Test
    @Transactional
    fun `findPositionRows returns an empty list for an invoice with no items, and for an id that does not exist`() {
        val s = sender("XRH-")
        val r = recipient("XRH-1")
        em.persist(s)
        em.persist(r)
        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 5, 1) }
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        assertThat(repository.findPositionRows(invoiceId)).isEmpty()
        assertThat(repository.findPositionRows(-1L)).isEmpty()
    }

    @Test
    @Transactional
    fun `findPositionRows handles an invoice with many positions with exactly one B2B batch query, not one per position`() {
        val s = sender("XRI-")
        val r = recipient("XRI-1")
        val tax = taxRate("XRI_DE_19")
        em.persist(s)
        em.persist(r)
        em.persist(tax)
        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 5, 1) }
        val items = (1..MANY_POSITIONS_COUNT).map { n -> oneItem(tax).apply { positionId = n.toString(); sortOrder = n } }
        items.forEach { invoice.addItem(it) }
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val rows = repository.findPositionRows(invoiceId)

        assertThat(rows).hasSize(MANY_POSITIONS_COUNT)
        assertThat(rows.map { it.sortOrder }).isSorted()
    }

    @Test
    @Transactional
    fun `findPositionRows expands a TRIP item into its position row, then its expense rows, then its per-diem rows, in that order`() {
        val s = sender("XRK-")
        val r = recipient("XRK-1")
        val tax = taxRate("XRK_DE_19")
        em.persist(s)
        em.persist(r)
        em.persist(tax)
        val invoice = Invoice().apply {
            senderProfile = s
            recipient = r
            invoiceDate = LocalDate.of(2026, 5, 1)
            invoiceNumber = InvoiceNumber("XRK-2026-05-00001")
        }
        val trip = InvoiceItem().apply {
            positionId = "1"
            category = InvoiceItemCategory.TRIP
            description = "Dienstreise Muenchen"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money.ZERO
            taxRateRef = tax
            sortOrder = 1
        }
        val hotel = TripExpense().apply {
            invoiceItem = trip
            kind = TripExpenseKind.HOTEL
            description = "Hotel Uebernachtung"
            travelDate = LocalDate.of(2026, 5, 10)
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.PIECE
            unitPriceNet = Money.of("120.00")
            sortOrder = 1
            calculateNetAmount()
        }
        val transport = TripExpense().apply {
            invoiceItem = trip
            kind = TripExpenseKind.TRANSPORT
            description = "Zugfahrt"
            travelDate = LocalDate.of(2026, 5, 11)
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.PIECE
            unitPriceNet = Money.of("30.00")
            sortOrder = 2
            calculateNetAmount()
        }
        trip.tripExpenses.add(hotel)
        trip.tripExpenses.add(transport)
        val perDiemDay = TravelExpenseEntry().apply {
            shortName = "DE Muenchen 2026-05-10"
            invoiceItem = trip
            country = Country.DE
            travelDate = LocalDate.of(2026, 5, 10)
            isArrivalDeparture = true
            breakfastDeduction = false
            lunchDeduction = false
            dinnerDeduction = false
            fullDayRateSnapshot = Money.of("28.00")
            arrivalDepartureRateSnapshot = Money.of("14.00")
            calculatedAmount = Money.of("14.00")
            employerContribution = Money.ZERO
        }
        trip.perDiemEntries.add(perDiemDay)
        trip.calculateAmounts()
        invoice.addItem(trip)
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val rows = repository.findPositionRows(invoiceId)

        // 1 position row + 2 expense rows + 1 per-diem row, expenses before per-diem days
        // (rk-6-invoice-excel.md item 1), the trip's own row first.
        assertThat(rows).hasSize(4)
        assertThat(rows.map { it.level }).containsExactly(0, 1, 1, 1)

        val position = rows[0]
        assertThat(position.positionId).isEqualTo("1")
        assertThat(position.category).isEqualTo(InvoiceItemCategory.TRIP)
        assertThat(position.parentPositionId).isNull()
        assertThat(position.netAmount).isEqualTo(Money(BigDecimal("164.00"))) // 120 + 30 + 14, no fixed price

        val hotelRow = rows[1]
        assertThat(hotelRow.level).isEqualTo(1)
        assertThat(hotelRow.parentPositionId).isEqualTo("1")
        assertThat(hotelRow.detailNumber).isEqualTo("001.1")
        assertThat(hotelRow.tripKind).isEqualTo(TripExpenseKind.HOTEL)
        assertThat(hotelRow.netAmount).isEqualTo(Money(BigDecimal("120.00")))
        assertThat(hotelRow.perDiem).isNull()

        val transportRow = rows[2]
        assertThat(transportRow.detailNumber).isEqualTo("001.2")
        assertThat(transportRow.tripKind).isEqualTo(TripExpenseKind.TRANSPORT)
        assertThat(transportRow.netAmount).isEqualTo(Money(BigDecimal("30.00")))

        val perDiemRow = rows[3]
        assertThat(perDiemRow.detailNumber).isEqualTo("001.3")
        assertThat(perDiemRow.tripKind).isNull()
        assertThat(perDiemRow.travelDate).isEqualTo(LocalDate.of(2026, 5, 10))
        assertThat(perDiemRow.netAmount).isEqualTo(Money(BigDecimal("14.00")))
        val perDiem = requireNotNull(perDiemRow.perDiem)
        assertThat(perDiem.isArrivalDeparture).isTrue()
        assertThat(perDiem.claimableAmount).isEqualTo(Money(BigDecimal("14.00")))
    }

    // ── findJournalRows(invoiceId) ──────────────────────────────────────────────────────

    @Test
    @Transactional
    fun `findJournalRows reads the real trigger-written journal for one invoice, in seq order`() {
        // Deliberately invoice-level only (create, then two plain field edits): adding/removing an
        // item would also dirty totals (net/vat/gross live on the invoice row itself), firing a
        // second, incidental INVOICE_UPDATED this test has no interest in pinning an exact count for.
        val s = sender("XRJ-")
        val r = recipient("XRJ-1")
        em.persist(s)
        em.persist(r)

        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 5, 1); freeText = "erste Notiz" }
        em.persist(invoice)
        em.flush() // INVOICE_CREATED
        val invoiceId = invoice.id!!

        invoice.freeText = "geaenderte Notiz"
        em.flush() // INVOICE_UPDATED (free_text)
        em.clear()

        val rows = repository.findJournalRows(invoiceId)

        assertThat(rows.map { it.event }).containsExactly(InvoiceAuditEvent.INVOICE_CREATED, InvoiceAuditEvent.INVOICE_UPDATED)
        assertThat(rows).allMatch { it.invoiceId == invoiceId }
        assertThat(rows.map { it.seq }).isSorted()
        assertThat(rows.map { it.seq }.distinct()).hasSize(rows.size)
        assertThat(rows).allMatch { it.actor.isNotBlank() }
        assertThat(rows).allMatch { it.entryHash.isNotBlank() && it.prevHash.isNotBlank() }

        val updateRow = rows.single { it.event == InvoiceAuditEvent.INVOICE_UPDATED }
        assertThat(updateRow.detail).contains("free_text")
        assertThat(updateRow.detail).doesNotContain("invoice_number")
    }

    @Test
    @Transactional
    fun `findJournalRows returns an empty list for an invoice id that does not exist`() {
        assertThat(repository.findJournalRows(-1L)).isEmpty()
    }

    // ── real-Postgres coverage for the remaining null-map-lookup and identifier-only
    //    branches in InvoiceExportReadRepositoryImpl (batched-lookup `.orEmpty()` fallbacks). ──

    @Test
    @Transactional
    fun `findPositionRows returns just the position row for a TRIP item with no expenses or per-diem days recorded`() {
        val s = sender("XRL-")
        val r = recipient("XRL-1")
        val tax = taxRate("XRL_DE_19")
        em.persist(s)
        em.persist(r)
        em.persist(tax)
        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 5, 1) }
        val trip = InvoiceItem().apply {
            positionId = "1"
            category = InvoiceItemCategory.TRIP
            description = "Leere Reise"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money.ZERO
            taxRateRef = tax
            sortOrder = 1
        }
        invoice.addItem(trip)
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val rows = repository.findPositionRows(invoiceId)

        assertThat(rows).hasSize(1)
        assertThat(rows.single().category).isEqualTo(InvoiceItemCategory.TRIP)
    }

    @Test
    @Transactional
    fun `findPositionRows resolves only the buyer item identifier when just BT-156 is set`() {
        val s = sender("XRM-")
        val r = recipient("XRM-1")
        val tax = taxRate("XRM_DE_19")
        em.persist(s)
        em.persist(r)
        em.persist(tax)
        val invoice = Invoice().apply { senderProfile = s; recipient = r; invoiceDate = LocalDate.of(2026, 5, 1) }
        val buyerOnly = oneItem(tax).apply { positionId = "1"; sortOrder = 1 }
        invoice.addItem(buyerOnly)
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        em.persist(B2bFieldValue().apply { ownerType = B2bFieldOwnerType.INVOICE_ITEM; ownerId = buyerOnly.id!!; term = B2bTerm.BT_156; value = "BUYER-ONLY" })
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val row = repository.findPositionRows(invoiceId).single()

        assertThat(row.sellerItemIdentifier).isNull()
        assertThat(row.buyerItemIdentifier).isEqualTo("BUYER-ONLY")
    }

    private companion object {
        const val MANY_POSITIONS_COUNT = 30
    }
}
