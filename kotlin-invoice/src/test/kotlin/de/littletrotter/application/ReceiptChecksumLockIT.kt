package de.littletrotter.application

import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
import io.quarkus.arc.Arc
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Receipt objects are content-addressed, so deleting a receipt and uploading the same bytes at the
 * same moment share one object. Both sides serialise on the content hash; whichever order wins,
 * a surviving row must still have its object.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class ReceiptChecksumLockIT {

    @Inject
    lateinit var uploadService: ReceiptUploadService

    @Inject
    lateinit var poolService: ReceiptPoolService

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var documentStore: DocumentStore

    private fun pdf(marker: Int): ByteArray =
        byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, (marker shr 8).toByte(), marker.toByte(), 0x7A)

    @Test
    fun `concurrent delete and upload of the same bytes never leave a row without its object`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(ROUNDS) { round ->
                val bytes = pdf(round)
                val date = LocalDate.of(2026, 3, 14)
                val first = uploadService.upload("lock-$round.pdf", "Beleg lock-$round", bytes, date)
                val start = CountDownLatch(1)
                val deleter = executor.submit {
                    inRequestContext {
                        start.await()
                        poolService.delete(first.id!!)
                    }
                }
                val uploader = executor.submit {
                    inRequestContext {
                        start.await()
                        uploadService.upload("lock-$round.pdf", "Beleg lock-$round", bytes, date)
                    }
                }
                start.countDown()
                deleter.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                uploader.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

                val survivor = receiptRepository.findByChecksum(first.sha256Checksum!!)
                if (survivor != null) {
                    assertTrue(documentStore.exists(survivor.objectKey), "round $round: row without object")
                    poolService.delete(survivor.id!!)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    // Each worker gets its own request context, as every UI request has in production.
    private fun inRequestContext(block: () -> Unit) {
        val requestContext = Arc.container().requestContext()
        requestContext.activate()
        try {
            block()
        } finally {
            requestContext.terminate()
        }
    }

    private companion object {
        const val ROUNDS = 40
        const val TIMEOUT_SECONDS = 30L
    }
}
