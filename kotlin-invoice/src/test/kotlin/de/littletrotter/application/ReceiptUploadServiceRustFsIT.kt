package de.littletrotter.application

import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.receipt.ReceiptPageProfile
import de.littletrotter.shared.receipt.ReceiptRasterKind
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The receipt upload against a real RustFS object store and Postgres: how long the checksum-lock
 * transaction lasts for a receipt-sized PDF, and that a concurrent delete of the same content never
 * leaves a row without its object.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(RustFsTestResource::class)
class ReceiptUploadServiceRustFsIT {

    @Inject
    lateinit var uploadService: ReceiptUploadService

    @Inject
    lateinit var poolService: ReceiptPoolService

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var documentStore: DocumentStore

    @Inject
    lateinit var em: EntityManager

    /**
     * The insert and the checksum lock must run inside an active transaction: with none, the
     * flushing save fails with a missing-transaction error and `pg_advisory_xact_lock` would not
     * serialise against a delete. The row is read back in a separate transaction, so it is committed.
     */
    @Test
    fun `upload through the container inserts the row in a committed transaction`() {
        clearReceipts()
        try {
            val pdf = syntheticPdf(2_048, seed = 99)
            val saved = inRequest { uploadService.upload("tx.pdf", "tx", pdf, DATE) }
            val row = QuarkusTransaction.requiringNew().call { receiptRepository.findByChecksum(sha256(pdf)) }
            assertTrue(row != null && row.id == saved.id, "receipt row must be committed after upload returns")
        } finally {
            clearReceipts()
        }
    }

    @Test
    fun `row and object coexist after concurrent upload and delete of the same content`() {
        clearReceipts()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(RACE_ROUNDS) { round ->
                val pdf = syntheticPdf(2_048, seed = round)
                val checksum = sha256(pdf)
                val seeded = inRequest { uploadService.upload("seed.pdf", "seed", pdf, DATE) }
                val start = CountDownLatch(1)
                val upload = pool.submit(Callable { start.await(); inRequest { uploadService.upload("race.pdf", "race", pdf, DATE) } })
                val delete = pool.submit(Callable { start.await(); inRequest { runCatching { poolService.delete(seeded.id!!) } } })
                start.countDown()
                upload.get(30, TimeUnit.SECONDS)
                delete.get(30, TimeUnit.SECONDS)

                val row = QuarkusTransaction.requiringNew().call { receiptRepository.findByChecksum(checksum) }
                if (row != null) {
                    assertTrue(documentStore.exists(row.objectKey), "round $round: row without object ${row.objectKey}")
                }
            }
        } finally {
            pool.shutdownNow()
            clearReceipts()
        }
    }

    /**
     * Wall time of the one transaction that takes the checksum lock, for a 440 KiB PDF. "cold" runs it
     * with the object absent from the store, "warm" with the object already stored — the state the
     * upload leaves behind once the put has moved ahead of the lock. Both values are logged so a
     * run on either implementation shows what the normal-path transaction costs.
     */
    @Test
    fun `lock transaction duration for a 440 KiB receipt`() {
        clearReceipts()
        val cold = measureLockTransaction(syntheticPdf(440 * 1024, seed = 1), putBeforehand = false)
        val warm = measureLockTransaction(syntheticPdf(440 * 1024, seed = 2), putBeforehand = true)
        println("ABLAGE-MEASURE lockTx cold(ms)=$cold warm(ms)=$warm")
        clearReceipts()
    }

    private fun measureLockTransaction(pdf: ByteArray, putBeforehand: Boolean): Long {
        val checksum = sha256(pdf)
        val objectKey = "receipt-pool/$checksum.pdf"
        if (putBeforehand) documentStore.put(objectKey, pdf, "application/pdf")
        val spec = ReceiptUploadService.ReceiptUploadSpec(
            fileName = "measure.pdf",
            description = "measure",
            documentDate = DATE,
            reduced = ReceiptUploadService.ReducedPayload(
                pdf,
                ReceiptNormalisationStage.NONE,
                pdf.size.toLong(),
                ReceiptPageProfile(pageCount = 1, a4Factor = 1.0, rasterKind = ReceiptRasterKind.IMAGE_PDF, annexBytes = 1_000L),
            ),
            checksum = checksum,
            objectKey = objectKey,
        )
        val started = System.nanoTime()
        inRequest { uploadService.storeUnderChecksumLock(spec) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(documentStore.exists(objectKey))
        return elapsedMs
    }

    private fun <T> inRequest(block: () -> T): T {
        val requestContext = Arc.container().requestContext()
        val owned = !requestContext.isActive
        if (owned) requestContext.activate()
        try {
            return block()
        } finally {
            if (owned) requestContext.terminate()
        }
    }

    private fun clearReceipts() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM invoice_item_receipt").executeUpdate()
            em.createNativeQuery("DELETE FROM invoice_receipt").executeUpdate()
            em.createNativeQuery("DELETE FROM receipt").executeUpdate()
        }
    }

    private fun syntheticPdf(size: Int, seed: Int): ByteArray {
        val header = "%PDF-1.7\n".toByteArray()
        val body = ByteArray(size - header.size) { ((it * 31 + seed * 17) and 0x7F).toByte() }
        return header + body
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private companion object {
        const val RACE_ROUNDS = 20
        val DATE: LocalDate = LocalDate.of(2026, 3, 14)
    }
}
