package de.littletrotter.application

import de.littletrotter.shared.DocumentStore
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.repository.ReceiptRepository
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import de.littletrotter.test.FIXED_TIME_SOURCE

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ReceiptUploadServiceIT {

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var uploadService: ReceiptUploadService

    @Test
    @Transactional
    fun `upload persists the description and reuses the checksum-backed receipt on identical bytes`() {
        em.createNativeQuery("DELETE FROM invoice_item_receipt").executeUpdate()
        em.createNativeQuery("DELETE FROM invoice_receipt").executeUpdate()
        em.createNativeQuery("DELETE FROM receipt").executeUpdate()

        val store = TrackingStore()
        val service = ReceiptUploadService(receiptRepository, store, time = FIXED_TIME_SOURCE)
        val pdf = validPdfBytes()

        val first = service.upload("hotel.pdf", "Beleg hotel.pdf", pdf, LocalDate.of(2026, 3, 14))
        val second = service.upload("hotel.pdf", "Beleg hotel.pdf", pdf, LocalDate.of(2026, 3, 14))

        em.flush()
        em.clear()

        val persisted = receiptRepository.findById(first.id!!)
        assertNotNull(persisted)
        assertEquals("Beleg hotel.pdf", persisted!!.description, "the (mandatory) description round-trips")
        assertEquals(first.id, second.id)
        assertEquals(1L, ReceiptPoolService(receiptRepository, store).countAll())
        assertEquals(1, store.putCalls)
        assertArrayEquals(pdf, store.get(first.objectKey))
    }

    @Test
    @Transactional
    fun `upload restores missing store object for an already persisted checksum`() {
        em.createNativeQuery("DELETE FROM invoice_item_receipt").executeUpdate()
        em.createNativeQuery("DELETE FROM invoice_receipt").executeUpdate()
        em.createNativeQuery("DELETE FROM receipt").executeUpdate()

        val store = TrackingStore()
        val service = ReceiptUploadService(receiptRepository, store, time = FIXED_TIME_SOURCE)
        val pdf = validPdfBytes()

        val first = service.upload("taxi.pdf", "Taxi", pdf, LocalDate.of(2026, 3, 14))
        store.delete(first.objectKey)
        assertTrue(!store.exists(first.objectKey))

        val second = service.upload("taxi.pdf", "Taxi", pdf, LocalDate.of(2026, 3, 14))

        assertEquals(first.id, second.id)
        assertTrue(store.exists(first.objectKey))
        assertEquals(2, store.putCalls)
    }

    /** The dedup guarantee under concurrency: two uploads of the same bytes
     *  that both pass the check-then-act window must still end in exactly one receipt row,
     *  enforced by `uq_receipt_sha256_checksum` and resolved by the recovery path in
     *  [ReceiptUploadService.upload]. Deliberately NOT @Transactional: each worker needs its own
     *  real transaction against the index; a shared test transaction would serialize them. */
    @Test
    fun `concurrent uploads of identical bytes persist exactly one receipt`() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM invoice_item_receipt").executeUpdate()
            em.createNativeQuery("DELETE FROM invoice_receipt").executeUpdate()
            em.createNativeQuery("DELETE FROM receipt").executeUpdate()
        }

        // The CDI bean, not a hand-built instance: the per-checksum lock lives in its transaction.
        val service = uploadService
        val start = CountDownLatch(1)
        // Distinct bytes from the other tests: rows of this test are committed, not rolled back.
        val pdf = validPdfBytes() + byteArrayOf(0x0A, 0x25, 0x25)

        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            // Each worker gets its own request context, as every UI request has in production —
            // without one the pre-lock lookup has no EntityManager session.
            val futures = List(2) {
                pool.submit(Callable {
                    val requestContext = Arc.container().requestContext()
                    requestContext.activate()
                    try {
                        start.await()
                        service.upload("race.pdf", "Beleg race.pdf", pdf, LocalDate.of(2026, 3, 14))
                    } finally {
                        requestContext.terminate()
                    }
                })
            }
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(results[0].id, results[1].id)
        QuarkusTransaction.requiringNew().run {
            val rows = (em.createNativeQuery("SELECT COUNT(*) FROM receipt").singleResult as Number).toLong()
            assertEquals(1L, rows)
            em.createNativeQuery("DELETE FROM receipt").executeUpdate()
        }
    }

    private fun validPdfBytes(): ByteArray =
        byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37)

    private open class TrackingStore : DocumentStore {
        private val objects = linkedMapOf<String, ByteArray>()
        var putCalls: Int = 0

        override fun put(objectKey: String, data: ByteArray, contentType: String) {
            putCalls += 1
            objects[objectKey] = data
        }

        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not found: $objectKey")

        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)

        override fun enableImmutability(objectKey: String) {}

        override fun delete(objectKey: String) {
            objects.remove(objectKey)
        }
    }

    /** Deterministic race point: blocks both uploaders inside [put] until each has
     *  passed the pre-insert duplicate checks, so both proceed towards the INSERT and the
     *  unique index (`uq_receipt_sha256_checksum`) has to decide. */
}
