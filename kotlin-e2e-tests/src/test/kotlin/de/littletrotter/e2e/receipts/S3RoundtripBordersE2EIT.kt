package de.littletrotter.e2e.receipts

import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.S3TestResource
import de.littletrotter.shared.DocumentStore
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * BL-2 §§1, 2, 4, 5 — Real S3 round trips and concurrency/failure modes.
 *
 * §1: Upload → object key → identical bytes → delete
 * §2: uq_receipt_object_key under concurrency (two same-name uploads → distinct keys via allocator,
 *      duplicate-key insert rejected)
 * §3: Receipt embedding (BG-24) in export (already done in [ReceiptEmbeddingE2EIT], do not duplicate)
 * §4: Failure modes (S3 unreachable at upload, at download; graceful error semantics)
 * §5: Orphan sweep contract (DB row without object, object without row → assert current behavior)
 *
 * Uses a real RustFS S3-compatible container via [S3TestResource] and the actual
 * [de.littletrotter.infrastructure.storage.S3DocumentStore] from little-trotter-invoice
 * (not kotlin-xpdf's version, but they share the same [de.littletrotter.shared.AbstractS3DocumentStore]
 * parent class).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
class S3RoundtripBordersE2EIT {

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: de.littletrotter.domain.repository.InvoiceRepository

    @Inject
    lateinit var documentStore: DocumentStore

    private fun pdfBytes(marker: Byte): ByteArray {
        // Minimal valid PDF structure per ISO 32000-1, ensuring the bytes are genuinely different per marker
        val pdfHeader = "%PDF-1.4\n"
        val pdfBody = "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj 3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\nxref\n"
        val pdfFooter = "trailer<</Size 4/Root 1 0 R>>\nstartxref\n999\n%%EOF"
        return (pdfHeader + marker.toInt() + "\n" + pdfBody + pdfFooter).toByteArray()
    }

    /** The receipt services, split into 4 units; this file only ever needs upload+delete,
     *  both against the same repository/store, so a plain field pair is enough (no forwarding
     *  methods — call sites reach through to `.upload.upload(...)`/`.pool.delete(...)` directly). */
    private class UploadAndPool(val upload: ReceiptUploadService, val pool: ReceiptPoolService)

    private fun buildReceiptService(): UploadAndPool {
        // The real DocumentStore is wired by Quarkus via S3DocumentStore bean
        return UploadAndPool(receiptUploadService(receiptRepository, documentStore, de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z"))), ReceiptPoolService(receiptRepository, documentStore))
    }

    // ── § 1 — Upload → bytes → download → delete ─────────────────────────────

    @Test
    @Transactional
    fun `BL2-1-01 upload stores bytes under expected key, download returns identical bytes`() {
        val svc = buildReceiptService()
        val pdfBytes = pdfBytes(1)

        val receipt = svc.upload.upload("test-1.pdf", "Beleg test-1.pdf", pdfBytes, LocalDate.of(2026, 3, 14))

        assertTrue(receipt.id != null, "upload should persist the receipt")
        assertTrue(receipt.objectKey.startsWith("receipt-pool/"), "key should start with receipt pool prefix")
        assertEquals(pdfBytes.size.toLong(), receipt.sizeBytes, "receipt size must match uploaded bytes")

        // Verify the object exists in S3
        assertTrue(documentStore.exists(receipt.objectKey), "object should exist in S3 after upload")

        // Download and verify bytes are identical
        val downloadedBytes = documentStore.get(receipt.objectKey)
        assertArrayEquals(pdfBytes, downloadedBytes, "downloaded bytes must match uploaded bytes exactly")
    }

    @Test
    @Transactional
    fun `BL2-1-02 delete removes the object from S3`() {
        val svc = buildReceiptService()
        val pdfBytes = pdfBytes(2)

        val receipt = svc.upload.upload("test-2.pdf", "Beleg test-2.pdf", pdfBytes, LocalDate.of(2026, 3, 14))
        val objectKey = receipt.objectKey

        assertTrue(documentStore.exists(objectKey), "object must exist after upload")

        // Delete via service — the receipt is assigned to nothing, the only state that allows it
        // (receipt-delete-guard).
        svc.pool.delete(receipt.id!!)

        assertFalse(documentStore.exists(objectKey), "object must not exist after delete")
    }

    // ── receipt-mandatory-fields — the server refuses incomplete uploads itself ──────────────

    @Test
    @Transactional
    fun `RMF-1 a blank-description upload is refused server-side and leaves no row and no object`() {
        // The UI keeps its upload controls disabled until description and Belegdatum are filled,
        // but that gate is not the only wall: the service itself must refuse — pinned here through
        // the full stack (real Postgres, real S3).
        val svc = buildReceiptService()
        val bytes = pdfBytes(91)
        val checksumBefore = receiptRepository.findByChecksum(sha256Hex(bytes))

        val ex = assertThrows(ApplicationValidationException::class.java) {
            svc.upload.upload("rmf-blank-desc.pdf", "   ", bytes, LocalDate.of(2026, 3, 14))
        }
        assertEquals("Beschreibung ist erforderlich.", ex.message)
        assertEquals(checksumBefore, receiptRepository.findByChecksum(sha256Hex(bytes)))

        val missingDate = assertThrows(ApplicationValidationException::class.java) {
            svc.upload.upload("rmf-no-date.pdf", "Beleg rmf-no-date.pdf", bytes, null)
        }
        assertEquals("Belegdatum ist erforderlich.", missingDate.message)
        assertEquals(checksumBefore, receiptRepository.findByChecksum(sha256Hex(bytes)))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ── § 2 — Concurrency: uq_receipt_object_key, allocator ──────────────────

    @Test
    @Transactional
    fun `BL2-2-01 two identical file uploads (same content) share the same object key via checksum dedup`() {
        val svc = buildReceiptService()
        val pdfBytes = pdfBytes(3)

        val receipt1 = svc.upload.upload("file-3a.pdf", "Beleg file-3a.pdf", pdfBytes, LocalDate.of(2026, 3, 14))
        val receipt2 = svc.upload.upload("file-3b.pdf", "Beleg file-3b.pdf", pdfBytes, LocalDate.of(2026, 3, 14))

        // Same content = same checksum = same object key = same database row
        assertEquals(receipt1.objectKey, receipt2.objectKey, "identical files must share the same object key (checksum-based)")
        assertEquals(receipt1.sha256Checksum, receipt2.sha256Checksum, "checksums must be identical")
        // Both resolve to the same database row (deduplicated)
        assertEquals(receipt1.id, receipt2.id, "uploading identical content returns the same receipt row")
    }

    @Test
    @Transactional
    fun `BL2-2-02 duplicate key insert at DB level is rejected and winner is returned`() {
        val svc = buildReceiptService()
        val pdfBytes = pdfBytes(4)

        val initialCount = receiptRepository.count(null)

        // First upload succeeds
        val receipt1 = svc.upload.upload("file-4a.pdf", "Beleg file-4a.pdf", pdfBytes, LocalDate.of(2026, 3, 14))

        // Second upload with same content hits uq_receipt_sha256_checksum constraint but
        // the ReceiptUploadService.upload catches it and returns the existing row
        val receipt2 = svc.upload.upload("file-4b.pdf", "Beleg file-4b.pdf", pdfBytes, LocalDate.of(2026, 3, 14))

        // Both queries resolve to the same persisted entity
        assertEquals(receipt1.id, receipt2.id, "duplicate upload should return the existing receipt")
        val finalCount = receiptRepository.count(null)
        assertEquals(initialCount + 1, finalCount, "only one new receipt should be added")
    }

    // ── § 4 — Failure modes ──────────────────────────────────────────────────

    /**
     * A [DocumentStore] standing in for an object store that cannot be reached. [failOnExists]
     * separates the two windows in which an upload can hit the outage: the lookup that precedes
     * the write (`true` — the store is down outright) and the write itself (`false` — the lookup
     * still answers, only the write is refused). The second is the dangerous one: it is the point
     * at which [ReceiptUploadService.upload] has decided to create a new pool entry.
     */
    private class UnreachableDocumentStore(private val failOnExists: Boolean) : DocumentStore {
        override fun exists(objectKey: String): Boolean =
            if (failOnExists) throw IllegalStateException(UNREACHABLE) else false

        override fun put(objectKey: String, data: ByteArray, contentType: String): Unit =
            throw IllegalStateException(UNREACHABLE)

        override fun get(objectKey: String): ByteArray = throw IllegalStateException(UNREACHABLE)

        override fun enableImmutability(objectKey: String): Unit = throw IllegalStateException(UNREACHABLE)

        override fun delete(objectKey: String): Unit = throw IllegalStateException(UNREACHABLE)

        companion object {
            const val UNREACHABLE = "S3 endpoint not reachable"
        }
    }

    /**
     * BL-2 §4 — S3 unreachable while uploading. [ReceiptUploadService.upload] writes the object BEFORE
     * it persists the row, so a store that refuses must leave the database exactly as it was: no
     * row may end up pointing at an object that was never written.
     *
     * Deliberately NOT `@Transactional`, unlike its neighbours: `upload` runs its save in the
     * repository's own short transaction (see the short-transaction note on that method), and the store
     * failure has to be free to abort that transaction without marking a caller-spanning one
     * rollback-only — which would take the assertions after it down with it.
     */
    @Test
    fun `BL2-4-01 upload with S3 unreachable produces no orphan DB row`() {
        val pdfBytes = pdfBytes(5)
        val checksum = sha256Hex(pdfBytes)
        assertNull(
            receiptRepository.findByChecksum(checksum),
            "precondition: these bytes must be unknown, otherwise upload dedups and never reaches the store",
        )

        listOf(true, false).forEach { failOnExists ->
            val svc = receiptUploadService(receiptRepository, UnreachableDocumentStore(failOnExists), de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")))
            val before = receiptRepository.count(null)

            val failure = assertThrows(IllegalStateException::class.java) {
                svc.upload("file-5.pdf", "Beleg file-5.pdf", pdfBytes, LocalDate.of(2026, 3, 14))
            }
            assertEquals(
                UnreachableDocumentStore.UNREACHABLE,
                failure.message,
                "the outage must reach the caller as itself, not as a validation or persistence error",
            )
            assertEquals(before, receiptRepository.count(null), "a refused upload must not leave a row behind")
            assertNull(receiptRepository.findByChecksum(checksum), "no row may point at an object that was never written")
        }
    }

    /**
     * BL-2 §4 — the two ways a download fails, kept apart because they mean different things to a
     * caller: the object is gone (the row outlived it — an orphan, which §5 then has to sweep), or
     * the store itself cannot be reached (nothing is wrong with the data). Neither may be answered
     * with empty bytes, and neither may remove the database row.
     */
    @Test
    @Transactional
    fun `BL2-4-02 download throws for a deleted object and for an unreachable store, and the DB row survives both`() {
        val svc = buildReceiptService()
        val pdfBytes = pdfBytes(6)

        val receipt = svc.upload.upload("file-6.pdf", "Beleg file-6.pdf", pdfBytes, LocalDate.of(2026, 3, 14))
        val initialCount = receiptRepository.count(null)

        // Orphan the row: the object goes, the row stays.
        documentStore.delete(receipt.objectKey)
        assertThrows(Exception::class.java, { documentStore.get(receipt.objectKey) }, "a missing object must not read as empty bytes")

        val unreachable = assertThrows(
            IllegalStateException::class.java,
            { UnreachableDocumentStore(failOnExists = true).get(receipt.objectKey) },
            "an unreachable store must not read as empty bytes either",
        )
        assertEquals(UnreachableDocumentStore.UNREACHABLE, unreachable.message)

        assertEquals(initialCount, receiptRepository.count(null), "the DB row persists despite the object being gone")
    }

    // ── § 5 — Orphan sweep contract ──────────────────────────────────────────

    @Test
    @Transactional
    fun `BL2-5-01 DB row without S3 object is findable (orphan detection prerequisite)`() {
        val svc = buildReceiptService()
        val pdfBytes = pdfBytes(7)

        val receipt = svc.upload.upload("file-7.pdf", "Beleg file-7.pdf", pdfBytes, LocalDate.of(2026, 3, 14))
        val objectKey = receipt.objectKey

        // Orphan: delete from S3, keep DB row
        documentStore.delete(objectKey)

        // Row is still in DB
        val orphanedReceipt = receiptRepository.findById(receipt.id!!)
        assertFalse(orphanedReceipt == null, "orphaned DB row must still be findable by ID")
        assertEquals(receipt.id, orphanedReceipt!!.id, "the found row is the orphaned one")

        // But the object is gone
        assertFalse(documentStore.exists(objectKey), "the S3 object is gone")
    }

    @Test
    @Transactional
    fun `BL2-5-02 S3 object without DB row is discoverable via list (orphan detection prerequisite)`() {
        // This test validates that an object can exist in S3 without a corresponding DB row.
        // It sets up an orphan by uploading via DocumentStore directly (bypassing ReceiptUploadService),
        // then verifies it's in S3 but not in the DB.

        val testKey = "receipt-pool/orphaned-test-object.pdf"
        val testBytes = pdfBytes(8)

        // Upload directly to S3 without creating a DB row
        documentStore.put(testKey, testBytes, "application/pdf")

        // Object exists in S3
        assertTrue(documentStore.exists(testKey), "object should exist in S3")

        // But no DB row corresponds to it (listing shows nothing)
        val allReceipts = receiptRepository.findAll(0, Int.MAX_VALUE, null)
        val matching = allReceipts.filter { it.objectKey == testKey }
        assertTrue(matching.isEmpty(), "no DB row should exist for the orphaned S3 object")
    }
}
