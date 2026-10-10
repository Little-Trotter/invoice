package de.littletrotter.application

import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.application.error.ApplicationNotFoundException
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.invoice.ReceiptAssignedToInvoiceException
import de.littletrotter.application.invoice.ReceiptBudgetService
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadMode
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.exception.ReceiptNotFoundException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.isIssued
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.repository.ReceiptUsage
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import de.littletrotter.test.FIXED_TIME_SOURCE

class ReceiptServiceTest {

    // ── upload ────────────────────────────────────────────────────────────────

    @Test
    fun `upload accepts valid PDF bytes`() {
        val (service, store) = build()

        val receipt = service.upload.upload("test.pdf", "Hotel Berlin", validPdfBytes(), LocalDate.of(2026, 3, 14))

        assertEquals("test.pdf", receipt.fileName)
        assertEquals("Hotel Berlin", receipt.description)
        assertEquals(1, store.objects.size)
    }

    @Test
    fun `upload rejects non-PDF bytes`() {
        val (service) = build()

        assertThrows(ApplicationValidationException::class.java) {
            service.upload.upload("not-a-pdf.pdf", "Beleg not-a-pdf.pdf", byteArrayOf(0x00, 0x01, 0x02, 0x03), LocalDate.of(2026, 3, 14))
        }
    }

    @Test
    fun `upload rejects empty bytes`() {
        val (service) = build()

        assertThrows(ApplicationValidationException::class.java) {
            service.upload.upload("empty.pdf", "Beleg empty.pdf", byteArrayOf(), LocalDate.of(2026, 3, 14))
        }
    }

    @Test
    fun `upload with KEEP_UNREDUCED rejects PDF over the 1 MiB limit`() {
        val (service) = build()
        val oversized = validPdfBytes() + ByteArray(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES.toInt())

        val ex = assertThrows(ApplicationValidationException::class.java) {
            service.upload.upload(
                "big.pdf", "Beleg big.pdf", oversized, LocalDate.of(2026, 3, 14),
                mode = ReceiptUploadMode.KEEP_UNREDUCED,
            )
        }
        assertTrue(ex.message!!.contains("1 MiB"))
    }

    @Test
    fun `upload with KEEP_UNREDUCED accepts PDF exactly at the 1 MiB limit`() {
        val (service) = build()
        val atLimit = validPdfBytes() +
            ByteArray((ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES - validPdfBytes().size).toInt())

        val receipt = service.upload.upload(
            "exact.pdf", "Beleg exact.pdf", atLimit, LocalDate.of(2026, 3, 14),
            mode = ReceiptUploadMode.KEEP_UNREDUCED,
        )

        assertEquals(ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE_BYTES, receipt.sizeBytes)
    }

    @Test
    fun `upload stores the document date on a fresh receipt`() {
        val (service) = build()
        val date = LocalDate.of(2026, 8, 15)

        val dated = service.upload.upload("dated.pdf", "Beleg dated.pdf", validPdfBytes(), date)
        assertEquals(date, dated.documentDate)
    }

    @Test
    fun `upload refuses a missing document date`() {
        val (service, store) = build()

        assertThrows(ApplicationValidationException::class.java) {
            service.upload.upload("undated.pdf", "Beleg undated.pdf", validPdfBytes(), null)
        }
        assertEquals(0, store.objects.size)
    }

    @Test
    fun `upload refuses a null description`() {
        val (service, store) = build()

        assertThrows(ApplicationValidationException::class.java) {
            service.upload.upload("no-desc.pdf", null, validPdfBytes(), LocalDate.of(2026, 3, 14))
        }
        assertEquals(0, store.objects.size)
    }

    @Test
    fun `upload refuses a blank description`() {
        val (service, store) = build()

        assertThrows(ApplicationValidationException::class.java) {
            service.upload.upload("blank-desc.pdf", "", validPdfBytes(), LocalDate.of(2026, 3, 14))
        }
        assertEquals(0, store.objects.size)
    }

    @Test
    fun `upload refuses a whitespace-only description`() {
        val (service, store) = build()

        assertThrows(ApplicationValidationException::class.java) {
            service.upload.upload("ws-desc.pdf", "   \t ", validPdfBytes(), LocalDate.of(2026, 3, 14))
        }
        assertEquals(0, store.objects.size)
    }

    @Test
    fun `upload persists the trimmed description`() {
        val (service) = build()

        val receipt = service.upload.upload("trim.pdf", "  Hotel Bonn  ", validPdfBytes(), LocalDate.of(2026, 3, 14))

        assertEquals("Hotel Bonn", receipt.description)
    }

    @Test
    fun `a dedup hit keeps the first upload's description and document date instead of overwriting them`() {
        val (service) = build()
        val original = service.upload.upload("first.pdf", "Beleg first.pdf", validPdfBytes(), LocalDate.of(2026, 1, 5))

        val dedup = service.upload.upload("second.pdf", "Beleg second.pdf", validPdfBytes(), LocalDate.of(2026, 8, 15))

        assertEquals(original.id, dedup.id)
        assertEquals("Beleg first.pdf", dedup.description)
        assertEquals(LocalDate.of(2026, 1, 5), dedup.documentDate)
    }

    @Test
    fun `even a dedup-identical upload is refused when the mandatory fields are missing`() {
        val (service) = build()
        service.upload.upload("first.pdf", "Beleg first.pdf", validPdfBytes(), LocalDate.of(2026, 1, 5))

        assertThrows(ApplicationValidationException::class.java) {
            service.upload.upload("second.pdf", "", validPdfBytes(), LocalDate.of(2026, 8, 15))
        }
        assertThrows(ApplicationValidationException::class.java) {
            service.upload.upload("second.pdf", "Beleg second.pdf", validPdfBytes(), null)
        }
    }

    @Test
    fun `updateDetails updates description and document date together`() {
        val (service) = build()
        val receipt = service.upload.upload("edit.pdf", "old", validPdfBytes(), LocalDate.of(2026, 1, 5))

        service.pool.updateDetails(receipt.id!!, "new", LocalDate.of(2026, 8, 15))
        val updated = service.pool.findById(receipt.id!!)
        assertEquals("new", updated.description)
        assertEquals(LocalDate.of(2026, 8, 15), updated.documentDate)
    }

    @Test
    fun `updateDetails succeeds while every linked invoice is still a draft`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val r = service.upload.upload("draft.pdf", "old", uniquePdfBytes(0x60), LocalDate.of(2026, 1, 5))
        val draft = Invoice().apply { id = 1L; invoiceNumber = InvoiceNumber("RE-2026-08-00020") }.also { it.forceStatusForTest(InvoiceStatus.DRAFT) }
        invoiceRepo.addItem(60L, draft, r)

        service.pool.updateDetails(r.id!!, "new", LocalDate.of(2026, 8, 15))

        assertEquals("new", service.pool.findById(r.id!!).description)
    }

    @Test
    fun `updateDetails refuses once a linked invoice is issued (fix-receipt-editable-after-invoice-issued)`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val r = service.upload.upload("issued.pdf", "old", uniquePdfBytes(0x61), LocalDate.of(2026, 1, 5))
        val sent = Invoice().apply { id = 1L; invoiceNumber = InvoiceNumber("RE-2026-08-00021") }.also { it.forceStatusForTest(InvoiceStatus.SENT) }
        invoiceRepo.addItem(61L, sent, r)

        val ex = assertThrows(de.littletrotter.domain.exception.ReceiptLockedByIssuedInvoiceException::class.java) {
            service.pool.updateDetails(r.id!!, "new", LocalDate.of(2026, 8, 15))
        }

        assertTrue(ex.message!!.contains("RE-2026-08-00021"))
        assertEquals("old", service.pool.findById(r.id!!).description, "refused write leaves the receipt unchanged")
    }

    @Test
    fun `updateDetails is locked when only one of several linked invoices is issued`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val r = service.upload.upload("mixed.pdf", "old", uniquePdfBytes(0x62), LocalDate.of(2026, 1, 5))
        val draft = Invoice().apply { id = 1L; invoiceNumber = InvoiceNumber("RE-2026-08-00022") }.also { it.forceStatusForTest(InvoiceStatus.DRAFT) }
        val sent = Invoice().apply { id = 2L; invoiceNumber = InvoiceNumber("RE-2026-08-00023") }.also { it.forceStatusForTest(InvoiceStatus.SENT) }
        invoiceRepo.addItem(62L, draft, r)
        invoiceRepo.addItem(63L, sent, r)

        assertThrows(de.littletrotter.domain.exception.ReceiptLockedByIssuedInvoiceException::class.java) {
            service.pool.updateDetails(r.id!!, "new", LocalDate.of(2026, 8, 15))
        }
    }

    @Test
    fun `updateDetails refuses clearing either mandatory field`() {
        val (service) = build()
        val receipt = service.upload.upload("edit.pdf", "old", validPdfBytes(), LocalDate.of(2026, 1, 5))

        assertThrows(ApplicationValidationException::class.java) {
            service.pool.updateDetails(receipt.id!!, null, LocalDate.of(2026, 8, 15))
        }
        assertThrows(ApplicationValidationException::class.java) {
            service.pool.updateDetails(receipt.id!!, "  ", LocalDate.of(2026, 8, 15))
        }
        assertThrows(ApplicationValidationException::class.java) {
            service.pool.updateDetails(receipt.id!!, "new", null)
        }

        val unchanged = service.pool.findById(receipt.id!!)
        assertEquals("old", unchanged.description)
        assertEquals(LocalDate.of(2026, 1, 5), unchanged.documentDate)
    }

    @Test
    fun `upload uses fallback filename when null`() {
        val (service) = build()

        val receipt = service.upload.upload(null, "Beleg", validPdfBytes(), LocalDate.of(2026, 3, 14))

        assertEquals("receipt.pdf", receipt.fileName)
    }

    @Test
    fun `upload is idempotent for identical pdf bytes`() {
        val (service, store) = build()

        val first = service.upload.upload("same.pdf", "A", validPdfBytes(), LocalDate.of(2026, 3, 14))
        val second = service.upload.upload("same.pdf", "A", validPdfBytes(), LocalDate.of(2026, 3, 14))

        assertEquals(first.id, second.id)
        assertEquals(first.objectKey, second.objectKey)
        assertEquals(1, service.pool.findAll().size)
        assertEquals(1, store.objects.size)
    }

    @Test
    fun `upload restores missing object for existing checksum`() {
        val (service, store) = build()

        val first = service.upload.upload("same.pdf", "A", validPdfBytes(), LocalDate.of(2026, 3, 14))
        store.objects.remove(first.objectKey)
        assertTrue(!store.objects.containsKey(first.objectKey))

        val second = service.upload.upload("same.pdf", "A", validPdfBytes(), LocalDate.of(2026, 3, 14))

        assertEquals(first.id, second.id)
        assertTrue(store.objects.containsKey(first.objectKey))
    }

    @Test
    fun `boundary - findAllSorted delegates to the repository's sorted lookup`() {
        val (service) = build()
        service.upload.upload("a.pdf", "A", validPdfBytes(), LocalDate.of(2026, 3, 14))

        val result = service.pool.findAllSorted(0, 60, null, de.littletrotter.domain.shared.GridSort("fileName", de.littletrotter.domain.shared.SortDirection.ASC))

        assertEquals(1, result.size)
    }

    @Test
    fun `upload does not write object again when object key already exists but checksum is new in repository`() {
        val sharedStore = InMemoryDocumentStore()
        val firstUploadService = ReceiptUploadService(InMemoryReceiptRepository(), sharedStore, time = FIXED_TIME_SOURCE)
        val pdf = validPdfBytes()

        val first = firstUploadService.upload("first.pdf", "Beleg first.pdf", pdf, LocalDate.of(2026, 3, 14))
        val putsAfterFirstUpload = sharedStore.putCalls

        val secondReceiptRepo = InMemoryReceiptRepository()
        val secondUploadService = ReceiptUploadService(secondReceiptRepo, sharedStore, time = FIXED_TIME_SOURCE)
        val second = secondUploadService.upload("second.pdf", "Beleg second.pdf", pdf, LocalDate.of(2026, 3, 14))

        assertEquals(putsAfterFirstUpload, sharedStore.putCalls)
        assertEquals(first.objectKey, second.objectKey)
        assertEquals(1, ReceiptPoolService(secondReceiptRepo, sharedStore).findAll().size)
    }

    // ── findById ──────────────────────────────────────────────────────────────

    @Test
    fun `findById throws when receipt does not exist`() {
        val (service) = build()

        assertThrows(ApplicationNotFoundException::class.java) {
            service.pool.findById(99L)
        }
    }

    @Test
    fun `findById returns existing receipt`() {
        val repo = InMemoryReceiptRepository()
        val (service) = build(repo = repo)
        val saved = service.upload.upload("receipt.pdf", "Beleg receipt.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))

        val found = service.pool.findById(saved.id!!)

        assertEquals(saved.id, found.id)
    }

    // ── getBytes ──────────────────────────────────────────────────────────────

    @Test
    fun `getBytes returns bytes from document store`() {
        val pdfBytes = validPdfBytes()
        val (service) = build()
        val receipt = service.upload.upload("doc.pdf", "Beleg doc.pdf", pdfBytes, LocalDate.of(2026, 3, 14))

        val result = service.pool.getBytes(receipt.id!!)

        assertArrayEquals(pdfBytes, result)
    }

    // ST-B2 — a receipt row can outlive its object (deleted directly in the store, a failed
    // upload cleanup, ...); getBytes must translate whatever the store throws for a missing key
    // into the same domain "not found" findById already throws for a missing row, not let an
    // infrastructure-specific exception escape.
    @Test
    fun `getBytes throws ReceiptNotFoundException when the object is missing from the store`() {
        val pdfBytes = validPdfBytes()
        val (service, store) = build()
        val receipt = service.upload.upload("doc.pdf", "Beleg doc.pdf", pdfBytes, LocalDate.of(2026, 3, 14))
        store.objects.remove(receipt.objectKey)

        assertThrows(ReceiptNotFoundException::class.java) { service.pool.getBytes(receipt.id!!) }
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete removes receipt and its object from store`() {
        val (service, store) = build()
        val receipt = service.upload.upload("invoice.pdf", "Beleg invoice.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))
        val key = receipt.objectKey

        service.pool.delete(receipt.id!!)

        assertThrows(ApplicationNotFoundException::class.java) { service.pool.findById(receipt.id!!) }
        assertTrue(!store.objects.containsKey(key))
    }

    @Test
    fun `delete throws when receipt does not exist`() {
        val (service) = build()

        assertThrows(ApplicationNotFoundException::class.java) {
            service.pool.delete(999L)
        }
    }

    @Test
    fun `delete still removes receipt when store delete fails`() {
        val repo = InMemoryReceiptRepository()
        val store = object : DocumentStore {
            val objects = linkedMapOf<String, ByteArray>()
            override fun put(objectKey: String, data: ByteArray, contentType: String) {
                objects[objectKey] = data
            }
            override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not found: $objectKey")
            override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {
                // A realistic backend failure (runDocumentStoreOp's own catch set).
                throw java.io.IOException("store temporarily unavailable")
            }
        }
        val uploadService = ReceiptUploadService(repo, store, time = FIXED_TIME_SOURCE)
        val poolService = ReceiptPoolService(repo, store)
        val receipt = uploadService.upload("delete-fail.pdf", "Beleg delete-fail.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))

        poolService.delete(receipt.id!!)

        assertThrows(ApplicationNotFoundException::class.java) {
            poolService.findById(receipt.id!!)
        }
    }

    @Test
    fun `delete removes the stored object only after the receipt row is gone`() {
        val repo = InMemoryReceiptRepository()
        var rowGoneWhenObjectDeleted: Boolean? = null
        lateinit var receiptId: Number
        val store = object : DocumentStore {
            val objects = linkedMapOf<String, ByteArray>()
            override fun put(objectKey: String, data: ByteArray, contentType: String) {
                objects[objectKey] = data
            }
            override fun get(objectKey: String): ByteArray = objects[objectKey] ?: error("not found: $objectKey")
            override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {
                rowGoneWhenObjectDeleted = repo.findById(receiptId.toLong()) == null
                objects.remove(objectKey)
            }
        }
        val uploadService = ReceiptUploadService(repo, store, time = FIXED_TIME_SOURCE)
        val receipt = uploadService.upload("order.pdf", "Beleg order.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))
        receiptId = receipt.id!!

        ReceiptPoolService(repo, store).delete(receipt.id!!)

        assertEquals(true, rowGoneWhenObjectDeleted)
        assertTrue(store.objects.isEmpty())
    }

    // ── findAll ───────────────────────────────────────────────────────────────

    @Test
    fun `findAll returns empty list when no receipts exist`() {
        val (service) = build()

        assertEquals(0, service.pool.findAll().size)
    }

    @Test
    fun `findAll returns all uploaded receipts`() {
        val (service) = build()
        service.upload.upload("a.pdf", "Beleg a.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))
        service.upload.upload("b.pdf", "Beleg b.pdf", anotherValidPdfBytes(), LocalDate.of(2026, 3, 14))

        assertEquals(2, service.pool.findAll().size)
    }

    @Test
    fun `findAll with paging forwards page size and filter`() {
        val repo = InMemoryReceiptRepository()
        val (service) = build(repo)

        service.pool.findAll(2, 25, "hotel")

        assertEquals(2, repo.lastPageIndex)
        assertEquals(25, repo.lastPageSize)
        assertEquals("hotel", repo.lastFilter)
    }

    @Test
    fun `countAll forwards filter`() {
        val repo = InMemoryReceiptRepository()
        val (service) = build(repo)

        service.pool.countAll("taxi")

        assertEquals("taxi", repo.lastCountFilter)
    }

    // ── item receipt relations ────────────────────────────────────────────────

    @Test
    fun `setReceiptsForItem replaces existing receipt list`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val oldReceipt = service.upload.upload("old.pdf", "Beleg old.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))
        val newReceiptA = service.upload.upload("new-a.pdf", "Beleg new-a.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))
        val newReceiptB = service.upload.upload("new-b.pdf", "Beleg new-b.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))
        invoiceRepo.addItem(10L, oldReceipt)

        service.link.setReceiptsForItem(10L, listOf(newReceiptA.id!!, newReceiptB.id!!))

        val attachedIds = invoiceRepo.requireItem(10L).receipts.mapNotNull { it.id }
        assertEquals(listOf(newReceiptA.id, newReceiptB.id), attachedIds)
    }

    @Test
    fun `setReceiptsForItem throws when item does not exist`() {
        val (service) = build()

        assertThrows(ApplicationValidationException::class.java) {
            service.link.setReceiptsForItem(404L, emptyList())
        }
    }

    @Test
    fun `setReceiptsForItem refuses to touch an item of a finalized invoice`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val receipt = service.upload.upload("locked.pdf", "Beleg locked.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))
        val invoice = Invoice().apply {
            id = 1L
            invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("RE-2026-08-00001")
            forceStatusForTest(InvoiceStatus.SENT)
        }
        invoiceRepo.addItem(10L, invoice, receipt)

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            service.link.setReceiptsForItem(10L, emptyList())
        }
    }

    @Test
    fun `setReceiptsForItem throws when at least one receipt is unknown`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        invoiceRepo.addItem(11L)

        assertThrows(ApplicationNotFoundException::class.java) {
            service.link.setReceiptsForItem(11L, listOf(999L))
        }
    }

    @Test
    fun `countReceiptsForItem returns number of linked receipts`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val receiptRepo = InMemoryReceiptRepository(invoiceRepo)
        val store = InMemoryDocumentStore()
        val service = testReceiptServices(receiptRepo, invoiceRepo, store)
        val a = service.upload.upload("a.pdf", "Beleg a.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))
        val b = service.upload.upload("b.pdf", "Beleg b.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))
        invoiceRepo.addItem(12L, a, b)

        val count = service.link.countReceiptsForItem(12L)

        assertEquals(2L, count)
    }

    @Test
    fun `countReceiptsForItem throws when item does not exist`() {
        val (service) = build()

        assertThrows(ApplicationValidationException::class.java) {
            service.link.countReceiptsForItem(505L)
        }
    }

    @Test
    fun `findReceiptsForItem returns linked receipts`() {
        val invoiceRepo = InMemoryInvoiceRepository()
        val receiptRepo = InMemoryReceiptRepository(invoiceRepo)
        val store = InMemoryDocumentStore()
        val service = testReceiptServices(receiptRepo, invoiceRepo, store)
        val a = service.upload.upload("attached-a.pdf", "Beleg attached-a.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))
        val b = service.upload.upload("attached-b.pdf", "Beleg attached-b.pdf", validPdfBytes(), LocalDate.of(2026, 3, 14))
        invoiceRepo.addItem(13L, a, b)

        val receipts = service.link.findReceiptsForItem(13L)

        assertEquals(listOf(a.id, b.id), receipts.map { it.id })
    }

    @Test
    fun `findReceiptsForItem throws when item does not exist`() {
        val (service) = build()

        assertThrows(ApplicationValidationException::class.java) {
            service.link.findReceiptsForItem(606L)
        }
    }

    // ── Additive/removal assignment (n:m) ────────────────────────────────

    @Test
    fun `addReceiptsToItem adds to an existing list without replacing it`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val a = service.upload.upload("a.pdf", "Beleg a.pdf", uniquePdfBytes(0x11), LocalDate.of(2026, 3, 14))
        val b = service.upload.upload("b.pdf", "Beleg b.pdf", uniquePdfBytes(0x12), LocalDate.of(2026, 3, 14))
        val c = service.upload.upload("c.pdf", "Beleg c.pdf", uniquePdfBytes(0x13), LocalDate.of(2026, 3, 14))
        invoiceRepo.addItem(20L, a)

        service.link.addReceiptsToItem(20L, listOf(b.id!!, c.id!!))

        assertEquals(
            listOf(a.id, b.id, c.id),
            invoiceRepo.requireItem(20L).receipts.mapNotNull { it.id },
        )
    }

    @Test
    fun `addReceiptsToItem deduplicates against already attached receipts`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val a = service.upload.upload("a.pdf", "Beleg a.pdf", uniquePdfBytes(0x21), LocalDate.of(2026, 3, 14))
        val b = service.upload.upload("b.pdf", "Beleg b.pdf", uniquePdfBytes(0x22), LocalDate.of(2026, 3, 14))
        invoiceRepo.addItem(21L, a)

        service.link.addReceiptsToItem(21L, listOf(a.id!!, b.id!!))

        assertEquals(
            listOf(a.id, b.id),
            invoiceRepo.requireItem(21L).receipts.mapNotNull { it.id },
        )
    }

    @Test
    fun `addReceiptsToItem refuses on a finalized invoice`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val a = service.upload.upload("a.pdf", "Beleg a.pdf", uniquePdfBytes(0x31), LocalDate.of(2026, 3, 14))
        val locked = Invoice().apply {
            id = 1L
            invoiceNumber = InvoiceNumber("RE-2026-08-00002")
            forceStatusForTest(InvoiceStatus.SENT)
        }
        invoiceRepo.addItem(22L, locked, a)

        assertThrows(de.littletrotter.domain.exception.InvoiceImmutableException::class.java) {
            service.link.addReceiptsToItem(22L, listOf(a.id!!))
        }
    }

    @Test
    fun `removeReceiptsFromItem removes only the requested links`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val a = service.upload.upload("a.pdf", "Beleg a.pdf", uniquePdfBytes(0x41), LocalDate.of(2026, 3, 14))
        val b = service.upload.upload("b.pdf", "Beleg b.pdf", uniquePdfBytes(0x42), LocalDate.of(2026, 3, 14))
        val c = service.upload.upload("c.pdf", "Beleg c.pdf", uniquePdfBytes(0x43), LocalDate.of(2026, 3, 14))
        invoiceRepo.addItem(23L, a, b, c)

        service.link.removeReceiptsFromItem(23L, listOf(b.id!!))

        assertEquals(
            listOf(a.id, c.id),
            invoiceRepo.requireItem(23L).receipts.mapNotNull { it.id },
        )
    }

    // ── Usage lookup and delete guard (proof may never vanish) ───────

    @Test
    fun `findReceiptUsage reports where a receipt is attached across items and invoices`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val r = service.upload.upload("shared.pdf", "Beleg shared.pdf", uniquePdfBytes(0x51), LocalDate.of(2026, 3, 14))
        val draft = Invoice().apply { id = 1L; invoiceNumber = InvoiceNumber("RE-2026-08-00011") }.also { it.forceStatusForTest(InvoiceStatus.DRAFT) }
        val finalized = Invoice().apply { id = 2L; invoiceNumber = InvoiceNumber("RE-2026-08-00012") }.also { it.forceStatusForTest(InvoiceStatus.SENT) }
        invoiceRepo.addItem(30L, draft, r)
        invoiceRepo.addItem(31L, finalized, r)

        val usage = service.pool.findReceiptUsage(r.id!!)

        assertEquals(2, usage.size)
        assertEquals(setOf(true, false), usage.map { it.invoiceReadOnly }.toSet())
        assertTrue(usage.any { it.invoiceNumber == "RE-2026-08-00012" && it.invoiceReadOnly })
        assertTrue(usage.any { it.invoiceNumber == "RE-2026-08-00011" && !it.invoiceReadOnly })
    }

    @Test
    fun `checkDeleteEligibility names every assigned invoice and blocks the delete`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val r = service.upload.upload("shared.pdf", "Beleg shared.pdf", uniquePdfBytes(0x52), LocalDate.of(2026, 3, 14))
        val draft = Invoice().apply { id = 1L; invoiceNumber = InvoiceNumber("RE-2026-08-00013") }.also { it.forceStatusForTest(InvoiceStatus.DRAFT) }
        val cancelled = Invoice().apply { id = 2L; invoiceNumber = InvoiceNumber("RE-2026-08-00014") }.also { it.forceStatusForTest(InvoiceStatus.CANCELLED) }
        invoiceRepo.addItem(40L, draft, r)
        invoiceRepo.addItem(41L, cancelled, r)

        val check = service.pool.checkDeleteEligibility(r.id!!)

        // receipt-delete-guard — draft and finalized alike block the delete; no split any more.
        assertEquals(listOf("RE-2026-08-00013", "RE-2026-08-00014"), check.assignedInvoiceNumbers.sorted())
        assertEquals(2, check.usageCount)
        assertEquals(false, check.deletable)
    }

    @Test
    fun `checkDeleteEligibility omits the blank number of a draft that has not been sent yet`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val r = service.upload.upload("draft-only.pdf", "Beleg draft-only.pdf", uniquePdfBytes(0x57), LocalDate.of(2026, 3, 14))
        val draft = Invoice().apply { id = 3L; invoiceNumber = InvoiceNumber.UNASSIGNED }.also { it.forceStatusForTest(InvoiceStatus.DRAFT) }
        invoiceRepo.addItem(42L, draft, r)

        val check = service.pool.checkDeleteEligibility(r.id!!)

        assertTrue(check.assignedInvoiceNumbers.isEmpty(), "a draft carries no number to name")
        assertEquals(1, check.usageCount)
        assertEquals(false, check.deletable)
    }

    @Test
    fun `findLockedReceiptIds reports only receipts attached to an issued invoice`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val locked = service.upload.upload("locked.pdf", "Beleg locked.pdf", uniquePdfBytes(0x58), LocalDate.of(2026, 3, 14))
        val open = service.upload.upload("open.pdf", "Beleg open.pdf", uniquePdfBytes(0x59), LocalDate.of(2026, 3, 14))
        val sent = Invoice().apply { id = 1L; invoiceNumber = InvoiceNumber("RE-2026-08-00019") }.also { it.forceStatusForTest(InvoiceStatus.SENT) }
        val draft = Invoice().apply { id = 2L; invoiceNumber = InvoiceNumber("RE-2026-08-00018") }.also { it.forceStatusForTest(InvoiceStatus.DRAFT) }
        invoiceRepo.addItem(43L, sent, locked)
        invoiceRepo.addItem(44L, draft, open)

        val ids = service.pool.findLockedReceiptIds(listOf(locked.id!!, open.id!!))

        assertEquals(setOf(locked.id), ids)
    }

    @Test
    fun `findReceiptUsage for an unused receipt is empty`() {
        val (service, _) = build()
        val r = service.upload.upload("unused.pdf", "Beleg unused.pdf", uniquePdfBytes(0x53), LocalDate.of(2026, 3, 14))

        assertTrue(service.pool.findReceiptUsage(r.id!!).isEmpty())
    }

    @Test
    fun `delete of a receipt referenced by a finalized invoice is rejected (V4-T07)`() {
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val r = service.upload.upload("proof.pdf", "Beleg proof.pdf", uniquePdfBytes(0x54), LocalDate.of(2026, 3, 14))
        val finalized = Invoice().apply { id = 1L; invoiceNumber = InvoiceNumber("RE-2026-08-00015") }.also { it.forceStatusForTest(InvoiceStatus.SENT) }
        invoiceRepo.addItem(50L, finalized, r)

        val ex = assertThrows(ReceiptAssignedToInvoiceException::class.java) {
            service.pool.delete(r.id!!)
        }

        assertTrue(ex.message!!.contains("RE-2026-08-00015"))
        // still present
        assertNotNull(service.pool.findById(r.id!!))
    }

    @Test
    fun `delete of a receipt referenced by a draft is rejected too (receipt-delete-guard)`() {
        // Was "requires confirmation": a draft link is a reference like any other, and
        // the pool no longer offers a way to break it — that happens on the invoice itself.
        val (service, _, invoiceRepo) = buildWithInvoiceRepo()
        val r = service.upload.upload("draft-proof.pdf", "Beleg draft-proof.pdf", uniquePdfBytes(0x55), LocalDate.of(2026, 3, 14))
        val draft = Invoice().apply { id = 1L; invoiceNumber = InvoiceNumber("RE-2026-08-00016") }.also { it.forceStatusForTest(InvoiceStatus.DRAFT) }
        invoiceRepo.addItem(51L, draft, r)

        val ex = assertThrows(ReceiptAssignedToInvoiceException::class.java) {
            service.pool.delete(r.id!!)
        }
        assertTrue(ex.message!!.contains("RE-2026-08-00016"))
        assertEquals(1, ex.usageCount)
        assertNotNull(service.pool.findById(r.id!!), "the receipt survives the refused delete")

        // Releasing the assignment on the invoice is what makes it deletable — and only then.
        service.link.removeReceiptsFromItem(51L, listOf(r.id!!))
        service.pool.delete(r.id!!)
        assertThrows(de.littletrotter.application.error.ApplicationNotFoundException::class.java) {
            service.pool.findById(r.id!!)
        }
    }

    @Test
    fun `delete of an unused receipt goes through (V4-T04 and T07 baseline)`() {
        val (service, _) = build()
        val r = service.upload.upload("unused.pdf", "Beleg unused.pdf", uniquePdfBytes(0x56), LocalDate.of(2026, 3, 14))

        service.pool.delete(r.id!!)

        assertThrows(de.littletrotter.application.error.ApplicationNotFoundException::class.java) {
            service.pool.findById(r.id!!)
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun validPdfBytes(): ByteArray =
        byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34) // %PDF-1.4

    private fun anotherValidPdfBytes(): ByteArray =
        byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37) // %PDF-1.7

    /** A valid-PDF byte array with a unique trailer byte, so each upload yields a distinct
     *  checksum and thus a distinct receipt (the plain [validPdfBytes] dedups on purpose). */
    private fun uniquePdfBytes(tag: Byte): ByteArray =
        validPdfBytes() + byteArrayOf(tag, 0x00, 0x01, 0x02)

    private fun build(repo: InMemoryReceiptRepository = InMemoryReceiptRepository()): Pair<TestReceiptServices, InMemoryDocumentStore> {
        val store = InMemoryDocumentStore()
        val service = testReceiptServices(repo, InMemoryInvoiceRepository(), store)
        return service to store
    }

    private fun buildWithInvoiceRepo(): Triple<TestReceiptServices, InMemoryDocumentStore, InMemoryInvoiceRepository> {
        val store = InMemoryDocumentStore()
        val invoiceRepo = InMemoryInvoiceRepository()
        val service = testReceiptServices(InMemoryReceiptRepository(invoiceRepo), invoiceRepo, store)
        return Triple(service, store, invoiceRepo)
    }

    /** `ReceiptService` no longer exists: this file's own `service.<method>()` calls
     *  below are unchanged, routed through the matching unit field instead of one shared service. */
    private class TestReceiptServices(
        val upload: ReceiptUploadService,
        val link: ReceiptLinkService,
        val budget: ReceiptBudgetService,
        val pool: ReceiptPoolService,
    )

    private fun testReceiptServices(
        receiptRepo: ReceiptRepository,
        invoiceRepo: InvoiceRepository,
        store: DocumentStore,
    ): TestReceiptServices {
        val pool = ReceiptPoolService(receiptRepo, store)
        val budget = ReceiptBudgetService(receiptRepo)
        return TestReceiptServices(
            upload = ReceiptUploadService(receiptRepo, store, time = FIXED_TIME_SOURCE),
            link = ReceiptLinkService(receiptRepo, invoiceRepo, pool, budget),
            budget = budget,
            pool = pool,
        )
    }

    private class InMemoryInvoiceRepository : InvoiceRepository {
        private val items = linkedMapOf<Long, InvoiceItem>()
        private val owningInvoices = linkedMapOf<Long, Invoice>()

        /** No explicit owning invoice given: a synthetic, non-finalized [Invoice] is used so the
         *  read-only guard behaves as if no invoice were attached (same as before A3). */
        fun addItem(id: Long, vararg receipts: Receipt) {
            val syntheticInvoice = Invoice()
            syntheticInvoice.id = -id
            addItem(id, syntheticInvoice, *receipts)
        }

        fun addItem(id: Long, invoice: Invoice, vararg receipts: Receipt) {
            val item = InvoiceItem().apply {
                this.id = id
                this.invoice = invoice
                this.receipts.addAll(receipts)
            }
            invoice.itemList.asMutableForTest().add(item)
            items[id] = item
            owningInvoices[id] = invoice
        }

        fun requireItem(id: Long): InvoiceItem = items[id] ?: error("item not found: $id")
        fun allItems(): List<InvoiceItem> = items.values.toList()

        override fun findInvoiceByItemId(itemId: Long): Invoice? = owningInvoices[itemId]

        // detachReceipts (the single removal path removeReceiptsFromItem now goes through)
        // re-fetches the invoice by its own id, so this must resolve the same instance
        // findInvoiceByItemId already found, not the unconditional null it used to be.
        override fun findById(id: Long): Invoice? = owningInvoices.values.firstOrNull { it.id == id }

        // ── unused stubs ─────────────────────────────────────────────────────
        override fun findByIdWithItems(id: Long): Invoice? = null
        override fun findByInvoiceNumber(number: String): Invoice? = null
        override fun findBySourceQuoteId(quoteId: java.util.UUID): Invoice? = null
        override fun findAll(pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
        override fun count(): Long = 0L
        override fun countByStatus(status: InvoiceStatus): Long = 0L
        override fun findByRecipientId(recipientId: Long, pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
        override fun findBySenderProfileId(senderProfileId: Long, pageIndex: Int, pageSize: Int): List<Invoice> = emptyList()
        override fun existsByBillingAddressId(addressId: Long): Boolean = false
        override fun existsByDeliveryAddressId(addressId: Long): Boolean = false
        override fun findOverviewPage(pageIndex: Int, pageSize: Int, filterText: String?, status: InvoiceStatus?): List<Invoice> = emptyList()
        override fun countForOverview(filterText: String?, status: InvoiceStatus?): Long = 0L
        override fun findAllForRecalculation(): List<Invoice> = emptyList()
        override fun save(invoice: Invoice): Invoice = invoice
        override fun forceVersionIncrement(invoice: Invoice) { invoice.version = (invoice.version ?: 0L) + 1L }
    }

    private class InMemoryReceiptRepository(
        private val invoiceRepo: InMemoryInvoiceRepository? = null,
    ) : ReceiptRepository {
        private val data = linkedMapOf<Long, Receipt>()
        private var seq = 100L
        var lastPageIndex: Int? = null
        var lastPageSize: Int? = null
        var lastFilter: String? = null
        var lastCountFilter: String? = null

        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<Receipt> {
            lastPageIndex = pageIndex
            lastPageSize = pageSize
            lastFilter = filterText
            return data.values.toList()
        }
        override fun count(filterText: String?): Long {
            lastCountFilter = filterText
            return data.size.toLong()
        }
        override fun findById(id: Long): Receipt? = data[id]
        override fun findByChecksum(sha256Checksum: String): Receipt? =
            data.values.firstOrNull { it.sha256Checksum == sha256Checksum }
        override fun save(receipt: Receipt): Receipt {
            val id = receipt.id ?: ++seq
            val stored = Receipt().also { s ->
                s.id = id; s.fileName = receipt.fileName; s.description = receipt.description
                s.objectKey = receipt.objectKey; s.sizeBytes = receipt.sizeBytes
                s.sha256Checksum = receipt.sha256Checksum; s.uploadedAt = receipt.uploadedAt
                s.documentDate = receipt.documentDate
            }
            data[id] = stored
            return stored
        }
        override fun delete(id: Long) { data.remove(id) }
        override fun countForItem(invoiceItemId: Long): Long =
            invoiceRepo?.requireItem(invoiceItemId)?.receipts?.size?.toLong() ?: 0L
        override fun findForItem(invoiceItemId: Long, pageIndex: Int, pageSize: Int): List<Receipt> =
            invoiceRepo?.requireItem(invoiceItemId)?.receipts?.toList() ?: emptyList()
        var lastExcludeItemId: Long? = null

        /** belege-improve — mirrors the real query: filter, then drop what the item already has. */
        override fun findAssignable(
            pageIndex: Int,
            pageSize: Int,
            filterText: String?,
            excludeItemId: Long?,
            excludeInvoiceId: Long?,
            excludeReceiptIds: Set<Long>,
        ): List<Receipt> {
            lastExcludeItemId = excludeItemId
            lastPageIndex = pageIndex
            lastPageSize = pageSize
            lastFilter = filterText
            return assignableRows(filterText, excludeItemId, excludeInvoiceId, excludeReceiptIds)
        }

        override fun countAssignable(
            filterText: String?,
            excludeItemId: Long?,
            excludeInvoiceId: Long?,
            excludeReceiptIds: Set<Long>,
        ): Long = assignableRows(filterText, excludeItemId, excludeInvoiceId, excludeReceiptIds).size.toLong()

        private fun assignableRows(
            filterText: String?,
            excludeItemId: Long?,
            excludeInvoiceId: Long?,
            excludeReceiptIds: Set<Long> = emptySet(),
        ): List<Receipt> {
            val attached = excludeItemId
                ?.let { invoiceRepo?.requireItem(it)?.receipts?.mapNotNull { r -> r.id }?.toSet() }
                .orEmpty()
            val invoiceLevel = excludeInvoiceId
                ?.let { invoiceRepo?.allItems()?.flatMap { it.invoice?.receipts ?: emptyList() }?.mapNotNull { r -> r.id }?.toSet() }
                .orEmpty()
            val needle = filterText?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            return data.values.filter { r ->
                val matches = needle == null ||
                    r.fileName.lowercase().contains(needle) ||
                    (r.description ?: "").lowercase().contains(needle)
                matches && r.id !in attached && r.id !in invoiceLevel && r.id !in excludeReceiptIds
            }
        }

        override fun findForInvoice(invoiceId: Long, pageIndex: Int, pageSize: Int): List<Receipt> =
            invoiceRepo?.allItems()
                ?.mapNotNull { it.invoice }
                ?.firstOrNull { it.id == invoiceId }
                ?.receipts
                ?.toList() ?: emptyList()

        override fun countForInvoice(invoiceId: Long): Long = findForInvoice(invoiceId).size.toLong()

        override fun findItemReceiptsForInvoice(invoiceId: Long): Map<Long, List<de.littletrotter.domain.masterdata.Receipt>> =
            invoiceRepo?.allItems()
                ?.filter { it.invoice?.id == invoiceId }
                ?.mapNotNull { item -> item.id?.let { iid -> iid to item.receipts.toList() } }
                ?.toMap() ?: emptyMap()

        override fun findReceiptUsageBatch(receiptIds: List<Long>): Map<Long, List<de.littletrotter.domain.repository.ReceiptUsage>> =
            receiptIds.associateWith { findReceiptUsage(it) }.filterValues { it.isNotEmpty() }

        /** n:m usage lookup backed by the in-memory item graph (+ each item's owning invoice). */
        override fun findReceiptUsage(receiptId: Long): List<de.littletrotter.domain.repository.ReceiptUsage> {
            return invoiceRepo?.allItems()
                ?.filter { item -> item.receipts.any { it.id == receiptId } }
                ?.map { item ->
                    val inv = item.invoice
                    de.littletrotter.domain.repository.ReceiptUsage(
                        invoiceItemId = item.id ?: -1L,
                        invoiceId = inv?.id ?: -1L,
                        invoiceNumber = inv?.invoiceNumber?.value.orEmpty(),
                        positionId = item.positionId.orEmpty(),
                        invoiceReadOnly = inv != null && inv.isReadOnly(),
                        invoiceIssued = inv != null && inv.status.isIssued,
                    )
                } ?: emptyList()
        }
    }

    private class InMemoryDocumentStore : DocumentStore {
        val objects = linkedMapOf<String, ByteArray>()
        var putCalls: Int = 0
        override fun put(objectKey: String, data: ByteArray, contentType: String) {
            putCalls += 1
            objects[objectKey] = data
        }
        // Same exception type de.littletrotter.infrastructure.storage
        // .LocalFileSystemDocumentStore.get() actually throws for a missing key, so
        // runDocumentStoreOp's catch (real backend contract, not a blanket Exception) sees it.
        override fun get(objectKey: String): ByteArray = objects[objectKey] ?: throw NoSuchElementException("not found: $objectKey")
        override fun exists(objectKey: String): Boolean = objects.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { objects.remove(objectKey) }
    }
}
