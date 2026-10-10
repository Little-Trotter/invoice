package de.littletrotter.application

import de.littletrotter.shared.DocumentStore
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.repository.ReceiptRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import jakarta.persistence.PersistenceException
import de.littletrotter.test.FIXED_TIME_SOURCE

class ReceiptUploadServiceCleanupTest {

    @Test
    fun `a failing row save propagates and leaves at most an unreferenced object`() {
        val store = object : DocumentStore {
            var lastPutKey: String? = null
            val deletedKeys = mutableListOf<String>()
            val objects = mutableMapOf<String, ByteArray>()
            override fun put(objectKey: String, data: ByteArray, contentType: String) {
                lastPutKey = objectKey
                objects[objectKey] = data
            }

            override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not found: $objectKey")
            override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {
                objects.remove(objectKey)
                deletedKeys.add(objectKey)
            }
        }

        val failingRepo = object : ReceiptRepository {
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<Receipt> = emptyList()
            override fun count(filterText: String?): Long = 0L
            override fun findById(id: Long): Receipt? = null
            override fun findByChecksum(sha256Checksum: String): Receipt? = null
            override fun save(receipt: Receipt): Receipt {
                throw PersistenceException("DB save failed")
            }
            override fun delete(id: Long) {}
            override fun countForItem(invoiceItemId: Long): Long = 0L
            override fun findForItem(invoiceItemId: Long, pageIndex: Int, pageSize: Int): List<Receipt> = emptyList()
            override fun findReceiptUsage(receiptId: Long): List<de.littletrotter.domain.repository.ReceiptUsage> = emptyList()
            override fun findAssignable(
                pageIndex: Int,
                pageSize: Int,
                filterText: String?,
                excludeItemId: Long?,
                excludeInvoiceId: Long?,
                excludeReceiptIds: Set<Long>,
            ): List<Receipt> = emptyList()
            override fun countAssignable(
                filterText: String?,
                excludeItemId: Long?,
                excludeInvoiceId: Long?,
                excludeReceiptIds: Set<Long>,
            ): Long = 0L
            override fun findForInvoice(invoiceId: Long, pageIndex: Int, pageSize: Int): List<Receipt> = emptyList()
            override fun countForInvoice(invoiceId: Long): Long = 0L
            override fun findItemReceiptsForInvoice(invoiceId: Long): Map<Long, List<de.littletrotter.domain.masterdata.Receipt>> = emptyMap()
            override fun findReceiptUsageBatch(receiptIds: List<Long>): Map<Long, List<de.littletrotter.domain.repository.ReceiptUsage>> = emptyMap()
        }
        val service = ReceiptUploadService(failingRepo, store, time = FIXED_TIME_SOURCE)

        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        val thrown = assertThrows(RuntimeException::class.java) {
            service.upload("fail.pdf", "Beleg fail.pdf", pdf, LocalDate.of(2026, 3, 14))
        }
        assertTrue(thrown.message?.contains("DB save failed") == true)

        // The object is content-addressed and may be shared; without a row it is only an orphan, so
        // the upload must not delete it (a concurrent delete/upload pair owns removal under the lock).
        assertNotNull(store.lastPutKey)
        assertTrue(store.deletedKeys.isEmpty())
    }
}
