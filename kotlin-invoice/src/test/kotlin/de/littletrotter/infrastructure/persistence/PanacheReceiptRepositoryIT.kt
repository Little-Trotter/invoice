package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber

/**
 * [PanacheReceiptRepository] already had substantial coverage (60.78 %) from other
 * invoice-item integration tests that exercise the `InvoiceItem`/`Invoice` join methods
 * ([PanacheReceiptRepository.findForItem], `findReceiptUsage*`, `findItemReceiptsForInvoice`,
 * `countForItem`/`countForInvoice`) through real invoice fixtures. This class deliberately does
 * NOT duplicate that setup; it only targets the methods that need nothing beyond [Receipt] rows
 * of their own: `findAll`/`count` (free-text filter on `fileName`/`description`, both `String` —
 * unlike [PanacheMileageRateRepositoryIT], no `Country`-conversion bug here), `findByChecksum`,
 * `findAssignable`/`countAssignable`/its shared `assignableWhere` builder (all three of
 * `filterText`, `excludeItemId`, `excludeInvoiceId` independently null/non-null — the exclusion
 * subqueries work as ordinary correlated `NOT IN` clauses even against an id with no matching
 * `InvoiceItem`/`Invoice` row, so no real invoice graph is needed to exercise them), and the
 * save/saveAndFlush/delete/unlink lifecycle. Distinctively-marked fixture rows only (see the
 * `source`/`fileName` literals below), the scoping convention [PanacheNumberRangeRepositoryIT]
 * uses for the same shared, uncleaned database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheReceiptRepositoryIT {

    private companion object {
        /** Receipts of other tests share the table and may be newer; an unfiltered query must not depend on a small first page. */
        const val EVERY_RECEIPT = 100_000
    }

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: ReceiptRepository

    private fun newReceipt(fileName: String, description: String?, objectKey: String, checksum: String) = Receipt().apply {
        this.fileName = fileName
        // receipt-mandatory-fields: description and document_date are NOT NULL at the DB level;
        // rows persisted here bypass ReceiptUploadService.upload, so the fixture fills both itself.
        this.description = description ?: "Beleg $fileName"
        this.objectKey = objectKey
        this.sizeBytes = 1024L
        this.sha256Checksum = checksum
        this.uploadedAt = OffsetDateTime.now()
        this.documentDate = LocalDate.of(2026, 3, 14)
    }

    private fun persist(receipt: Receipt): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            em.persist(receipt)
            em.flush()
            receipt.id!!
        }

    @Test
    fun `findAll and count filter across fileName and description`() {
        val id = persist(
            newReceipt(
                "ZZ-AP-COV-4-receipt.pdf", "AP-COV-4 Beschreibung", "zz-ap-cov-4/receipt-1",
                "ZZ-AP-COV-4-CHECKSUM-FINDALL",
            ),
        )

        val unfiltered = repository.findAll(filterText = null)
        assertTrue(unfiltered.any { it.id == id })

        val byFileName = repository.findAll(0, 10, "zz-ap-cov-4-receipt.pdf")
        assertEquals(listOf(id), byFileName.map { it.id })
        assertEquals(1L, repository.count("zz-ap-cov-4-receipt.pdf"))

        // Matches only via description, not fileName — proves that half of the OR too.
        val byDescription = repository.findAll(0, 10, "ap-cov-4 beschreibung")
        assertEquals(listOf(id), byDescription.map { it.id })

        assertTrue(repository.findAll(0, 10, "no-such-receipt-marker").isEmpty())
        assertEquals(0L, repository.count("no-such-receipt-marker"))
    }

    @Test
    fun `findByChecksum returns the most recent match and null for an unknown checksum`() {
        persist(
            newReceipt(
                "ZZ-AP-COV-4-checksum.pdf", null, "zz-ap-cov-4/receipt-checksum",
                "ZZ-AP-COV-4-CHECKSUM-UNIQUE",
            ),
        )

        val found = repository.findByChecksum("ZZ-AP-COV-4-CHECKSUM-UNIQUE")
        assertNotNull(found)
        assertEquals("ZZ-AP-COV-4-checksum.pdf", found!!.fileName)

        assertNull(repository.findByChecksum("no-such-checksum-marker"))
    }

    // ── findFileNames had no real-Postgres test at all: its whole point (one query ─────────────
    // for every id, never per row) was untested.
    @Test
    fun `happy path - findFileNames resolves file names for every requested id in one query`() {
        val id1 = persist(
            newReceipt(
                "ZZ-AP-COV-4-filenames-1.pdf", null, "zz-ap-cov-4/receipt-filenames-1",
                "ZZ-AP-COV-4-CHECKSUM-FILENAMES-1",
            ),
        )
        val id2 = persist(
            newReceipt(
                "ZZ-AP-COV-4-filenames-2.pdf", null, "zz-ap-cov-4/receipt-filenames-2",
                "ZZ-AP-COV-4-CHECKSUM-FILENAMES-2",
            ),
        )
        val noSuchId = 999_999_003L

        val names = repository.findFileNames(listOf(id1, id2, noSuchId))

        assertEquals(
            mapOf(id1 to "ZZ-AP-COV-4-filenames-1.pdf", id2 to "ZZ-AP-COV-4-filenames-2.pdf"),
            names,
        )
    }

    @Test
    fun `boundary - findFileNames returns an empty map for an empty id collection without querying`() {
        assertEquals(emptyMap<Long, String>(), repository.findFileNames(emptyList()))
    }

    @Test
    fun `findAssignable and countAssignable combine the filter with either exclusion independently`() {
        val id = persist(
            newReceipt(
                "ZZ-AP-COV-4-assignable.pdf", null, "zz-ap-cov-4/receipt-assignable",
                "ZZ-AP-COV-4-CHECKSUM-ASSIGNABLE",
            ),
        )
        // No InvoiceItem/Invoice actually references this id — the correlated NOT IN subquery
        // still runs (and matches nothing to exclude), which is all that's needed to exercise
        // assignableWhere's excludeItemId/excludeInvoiceId branches without a real invoice graph.
        val noSuchItemId = 999_999_001L
        val noSuchInvoiceId = 999_999_002L

        // clauses.isEmpty() == true: no filter, no exclusions.
        assertTrue(repository.findAssignable(0, EVERY_RECEIPT).any { it.id == id })

        // filterText only.
        val byFilter = repository.findAssignable(0, 10, filterText = "zz-ap-cov-4-assignable.pdf")
        assertEquals(listOf(id), byFilter.map { it.id })
        assertEquals(1L, repository.countAssignable(filterText = "zz-ap-cov-4-assignable.pdf"))

        // excludeItemId only, no filter — the NOT IN clause alone builds the WHERE.
        assertTrue(repository.findAssignable(0, EVERY_RECEIPT, excludeItemId = noSuchItemId).any { it.id == id })
        assertTrue(repository.countAssignable(excludeItemId = noSuchItemId) >= 1)

        // excludeInvoiceId only.
        assertTrue(repository.findAssignable(0, EVERY_RECEIPT, excludeInvoiceId = noSuchInvoiceId).any { it.id == id })
        assertTrue(repository.countAssignable(excludeInvoiceId = noSuchInvoiceId) >= 1)

        // All three combined — every clause in the AND-joined list at once.
        val combined = repository.findAssignable(
            0, 10, filterText = "zz-ap-cov-4-assignable.pdf",
            excludeItemId = noSuchItemId, excludeInvoiceId = noSuchInvoiceId,
        )
        assertEquals(listOf(id), combined.map { it.id })
    }

    // ── The excludeReceiptIds clause, never exercised by the test above ─────────────────────────

    @Test
    fun `findAssignable and countAssignable exclude receipts explicitly listed via excludeReceiptIds (RC-2)`() {
        val marker = "zz-cov3-excludeids"
        val keep = persist(
            newReceipt("ZZ-COV3-EXCLUDEIDS-keep.pdf", marker, "zz-cov3-excludeids/receipt-keep", "ZZ-COV3-EXCLUDEIDS-CHECKSUM-KEEP"),
        )
        val excluded = persist(
            newReceipt("ZZ-COV3-EXCLUDEIDS-excluded.pdf", marker, "zz-cov3-excludeids/receipt-excluded", "ZZ-COV3-EXCLUDEIDS-CHECKSUM-EXCLUDED"),
        )

        // clauses.isNotEmpty() == true via excludeReceiptIds alone -- no filterText, no other exclusion.
        val withoutExclusion = repository.findAssignable(0, 10, filterText = marker)
        assertEquals(setOf(keep, excluded), withoutExclusion.map { it.id }.toSet())
        assertEquals(2L, repository.countAssignable(filterText = marker))

        val withExclusion = repository.findAssignable(0, 10, filterText = marker, excludeReceiptIds = setOf(excluded))
        assertEquals(listOf(keep), withExclusion.map { it.id })
        assertEquals(1L, repository.countAssignable(filterText = marker, excludeReceiptIds = setOf(excluded)))
    }

    // ── findReceiptUsageBatch had no real-Postgres test at all: every existing reference ────────
    // ── (ReceiptServiceTest/-CleanupTest/-InvoiceLevelTest) mocks ReceiptRepository outright ──────

    @Test
    fun `findReceiptUsageBatch returns an empty map for an empty id list without querying`() {
        assertEquals(emptyMap<Long, Any>(), repository.findReceiptUsageBatch(emptyList()))
    }

    @Test
    fun `findReceiptUsageBatch reports item-level and invoice-level usage with real invoiceReadOnly and invoiceIssued flags`() {
        val marker = "zz-cov3-usagebatch"

        data class ItemUsageFixture(val receiptId: Long, val invoiceId: Long, val itemId: Long, val invoiceNumber: String)
        val itemFixture = QuarkusTransaction.requiringNew().call<ItemUsageFixture> {
            // InvoiceNumber's own format validator caps the prefix at 10 alnum chars
            // (PREFIX-YYYY-MM-NNNNN); SenderProfile.invoicePrefix carries the same @Size(max=10).
            val sender = SenderProfile().apply { name = "$marker Sender"; invoicePrefix = "COV3I-"; registerCourt = "AG Musterstadt" }
            val recipient = Recipient().apply { customerNumber = CustomerNumber("$marker-item"); name = "$marker Kunde" }
            em.persist(sender)
            em.persist(recipient)
            val invoice = Invoice().apply {
                invoiceNumber = InvoiceNumber("COV3I-2026-06-00001")
                senderProfile = sender
                this.recipient = recipient
                invoiceDate = LocalDate.of(2026, 6, 1)
                paymentTermsDays = 14
                addItem(
                    InvoiceItem().apply {
                        positionId = "POS-0001"
                        description = "Beratung"
                        quantity = BigDecimal.ONE
                        unitPriceNet = Money(BigDecimal("100.00"))
                    },
                )
            }
            // SENT (issued, read-only) -- exercises the true side of both status?.isReadOnly and
            // status?.isIssued for the item-level usage row.
            invoice.forceStatusForTest(InvoiceStatus.SENT)
            em.persist(invoice)
            val receipt = newReceipt(
                "ZZ-COV3-USAGEBATCH-item.pdf", null, "zz-cov3-usagebatch/receipt-item", "ZZ-COV3-USAGEBATCH-CHECKSUM-ITEM",
            )
            em.persist(receipt)
            invoice.itemList.first().receipts.add(receipt)
            em.flush()
            ItemUsageFixture(receipt.id!!, invoice.id!!, invoice.itemList.first().id!!, invoice.invoiceNumber.value)
        }

        data class InvoiceUsageFixture(val receiptId: Long, val invoiceId: Long, val invoiceNumber: String)
        val invoiceFixture = QuarkusTransaction.requiringNew().call<InvoiceUsageFixture> {
            val sender = SenderProfile().apply { name = "$marker Sender2"; invoicePrefix = "COV3D-"; registerCourt = "AG Musterstadt" }
            val recipient = Recipient().apply { customerNumber = CustomerNumber("$marker-inv"); name = "$marker Kunde2" }
            em.persist(sender)
            em.persist(recipient)
            val invoice = Invoice().apply {
                invoiceNumber = InvoiceNumber("COV3D-2026-06-00002")
                senderProfile = sender
                this.recipient = recipient
                invoiceDate = LocalDate.of(2026, 6, 1)
                paymentTermsDays = 14
            }
            // DRAFT (not issued, not read-only) -- the false side of both flags, and the
            // invoice-level (positionId/invoiceItemId = null) half of the method.
            em.persist(invoice)
            val receipt = newReceipt(
                "ZZ-COV3-USAGEBATCH-invoice.pdf", null, "zz-cov3-usagebatch/receipt-invoice", "ZZ-COV3-USAGEBATCH-CHECKSUM-INVOICE",
            )
            em.persist(receipt)
            invoice.receipts.add(receipt)
            em.flush()
            InvoiceUsageFixture(receipt.id!!, invoice.id!!, invoice.invoiceNumber.value)
        }

        // A receipt with no usage at all must simply be absent from the result map.
        val unusedReceiptId = persist(
            newReceipt("ZZ-COV3-USAGEBATCH-unused.pdf", null, "zz-cov3-usagebatch/receipt-unused", "ZZ-COV3-USAGEBATCH-CHECKSUM-UNUSED"),
        )

        val usage = repository.findReceiptUsageBatch(listOf(itemFixture.receiptId, invoiceFixture.receiptId, unusedReceiptId))

        val itemUsages = usage.getValue(itemFixture.receiptId)
        assertEquals(1, itemUsages.size)
        val iu = itemUsages.single()
        assertEquals(itemFixture.itemId, iu.invoiceItemId)
        assertEquals(itemFixture.invoiceId, iu.invoiceId)
        assertEquals(itemFixture.invoiceNumber, iu.invoiceNumber)
        assertEquals("POS-0001", iu.positionId)
        assertTrue(iu.invoiceReadOnly, "a SENT invoice's item-level usage must report read-only")
        assertTrue(iu.invoiceIssued, "a SENT invoice's item-level usage must report issued")

        val invoiceUsages = usage.getValue(invoiceFixture.receiptId)
        assertEquals(1, invoiceUsages.size)
        val vu = invoiceUsages.single()
        assertNull(vu.invoiceItemId, "invoice-level usage carries no item id")
        assertEquals(invoiceFixture.invoiceId, vu.invoiceId)
        assertEquals(invoiceFixture.invoiceNumber, vu.invoiceNumber)
        assertNull(vu.positionId, "invoice-level usage carries no position id")
        assertFalse(vu.invoiceReadOnly, "a DRAFT invoice's invoice-level usage must not report read-only")
        assertFalse(vu.invoiceIssued, "a DRAFT invoice's invoice-level usage must not report issued")

        assertFalse(usage.containsKey(unusedReceiptId), "a receipt with no usage must be absent, not mapped to an empty list")
    }

    /** Belegdatum — pins the tiebreak order (`document_date DESC, uploaded_at DESC, id DESC`)
     *  shared by findAll/findAssignable (and, via the same constant, findForItem/findForInvoice):
     *  the newest document date leads even when an older document was uploaded later, and a later
     *  upload wins between two same-dated rows. Date-less rows cannot exist any more —
     *  document_date is NOT NULL (receipt-mandatory-fields). */
    @Test
    fun `findAll and findAssignable order by document date desc with uploadedAt and id tiebreaks`() {
        val marker = "zz-docdate-order"
        val base = OffsetDateTime.parse("2026-08-01T10:00:00+02:00")

        fun persistDated(suffix: String, documentDate: LocalDate, uploadedAt: OffsetDateTime): Long =
            persist(
                newReceipt(
                    "ZZ-DOCDATE-ORDER-$suffix.pdf", null, "zz-docdate-order/receipt-$suffix",
                    "ZZ-DOCDATE-ORDER-CHECKSUM-$suffix",
                ).apply {
                    this.documentDate = documentDate
                    this.uploadedAt = uploadedAt
                },
            )

        // An older document uploaded LAST — the document date must still outrank the fresh upload.
        val datedOld = persistDated("old", LocalDate.of(2026, 1, 5), base.plusDays(9))
        // Two same-dated receipts; the later upload must come first (uploadedAt tiebreak).
        val sameDatedEarlierUpload = persistDated("same-a", LocalDate.of(2026, 8, 20), base)
        val samedatedLaterUpload = persistDated("same-b", LocalDate.of(2026, 8, 20), base.plusDays(1))
        // The oldest document with the newest upload — the date still decides, not the upload.
        val datedOldest = persistDated("oldest", LocalDate.of(2025, 11, 30), base.plusDays(30))

        val expected = listOf(samedatedLaterUpload, sameDatedEarlierUpload, datedOld, datedOldest)
        assertEquals(expected, repository.findAll(0, 10, marker).map { it.id })
        assertEquals(expected, repository.findAssignable(0, 10, filterText = marker).map { it.id })
    }

    @Test
    fun `two same-dated receipts with the same upload instant fall back to id desc`() {
        val marker = "zz-docdate-idtie"
        val instant = OffsetDateTime.parse("2026-08-01T10:00:00+02:00")

        fun persistTie(suffix: String): Long =
            persist(
                newReceipt(
                    "ZZ-DOCDATE-IDTIE-$suffix.pdf", null, "zz-docdate-idtie/receipt-$suffix",
                    "ZZ-DOCDATE-IDTIE-CHECKSUM-$suffix",
                ).apply {
                    this.documentDate = LocalDate.of(2026, 8, 20)
                    this.uploadedAt = instant
                },
            )

        val first = persistTie("a")
        val second = persistTie("b")

        // BIGSERIAL: second > first, so id DESC puts the later insert on top — stable paging.
        assertEquals(listOf(second, first), repository.findAll(0, 10, marker).map { it.id })
    }

    @Test
    fun `save inserts, saveAndFlush updates immediately, delete removes it`() {
        val receipt = newReceipt(
            "ZZ-AP-COV-4-save-v1.pdf", null, "zz-ap-cov-4/receipt-save",
            "ZZ-AP-COV-4-CHECKSUM-SAVE",
        )

        val saved = QuarkusTransaction.requiringNew().call<Receipt> { repository.save(receipt) }
        assertNotNull(saved.id)

        saved.fileName = "ZZ-AP-COV-4-save-v2.pdf"
        QuarkusTransaction.requiringNew().run { repository.saveAndFlush(saved) }

        val reloaded = repository.findById(saved.id!!)
        assertEquals("ZZ-AP-COV-4-save-v2.pdf", reloaded?.fileName)

        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
        em.clear()
        assertNull(repository.findById(saved.id!!))

        // Deleting an id that is already gone must be a no-op, not an error — exercises the
        // `em.find(...)?.let { ... }` null-safety branch's "not found" side.
        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
    }

    // ── receipt-mandatory-fields: the DB enforces NOT NULL beneath every service guard ─────────

    @Test
    fun `persisting a receipt without a document date violates the NOT NULL constraint`() {
        val receipt = newReceipt(
            "ZZ-RMF-nodate.pdf", null, "zz-rmf/receipt-nodate",
            "ZZ-RMF-CHECKSUM-NODATE",
        ).apply { documentDate = null }

        val ex = assertThrows(RuntimeException::class.java) {
            QuarkusTransaction.requiringNew().run {
                em.persist(receipt)
                em.flush()
            }
        }
        assertTrue(hasConstraintViolationCause(ex), "expected a NOT NULL constraint violation, got: $ex")
    }

    @Test
    fun `inserting a receipt row with a null description violates the NOT NULL constraint`() {
        // Receipt.description is a non-null Kotlin String, so a null can only reach the DB via
        // raw SQL — exactly the backstop this test pins.
        val ex = assertThrows(RuntimeException::class.java) {
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery(
                    "INSERT INTO receipt (file_name, description, object_key, size_bytes, sha256_checksum, uploaded_at, document_date, version) " +
                        "VALUES ('ZZ-RMF-nodesc.pdf', NULL, 'zz-rmf/receipt-nodesc', 1024, 'ZZ-RMF-CHECKSUM-NODESC', now(), DATE '2026-03-14', 0)",
                ).executeUpdate()
            }
        }
        assertTrue(hasConstraintViolationCause(ex), "expected a NOT NULL constraint violation, got: $ex")
    }

    /** Walks the cause chain for the Postgres not-null violation — QuarkusTransaction wraps the
     *  JPA exception, whose own cause carries the actual SQL state. */
    private fun hasConstraintViolationCause(ex: Throwable): Boolean =
        generateSequence(ex) { it.cause }.any {
            it.message?.contains("null value", ignoreCase = true) == true ||
                it.message?.contains("not-null", ignoreCase = true) == true
        }
}
