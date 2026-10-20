package de.littletrotter.application.invoice

import de.littletrotter.application.error.ApplicationNotFoundException
import de.littletrotter.application.invoice.port.inbound.ReceiptSort
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.shared.GridSort
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.fixture.aReceipt
import de.littletrotter.shared.DocumentStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime

/** The receipt pool's read-model methods: entity-to-view mapping and the paged/single lookups. */
class ReceiptPoolServiceViewTest {

    private val repository: ReceiptRepository = mock()
    private val service = ReceiptPoolService(repository, mock<DocumentStore>())

    private val uploaded = OffsetDateTime.parse("2026-04-02T08:00:00+02:00")

    private fun fullReceipt(): Receipt = aReceipt {
        id = 3L
        fileName = "taxi.pdf"
        description = "Taxi Berlin"
        objectKey = "receipt-pool/abc.pdf"
        sizeBytes = 2048L
        uploadedAt = uploaded
        documentDate = LocalDate.of(2026, 4, 1)
        normalisationStage = ReceiptNormalisationStage.IMAGES_DOWNSAMPLED
        originalSizeBytes = 4096L
    }

    @Test
    fun `toView copies every field`() {
        val view = fullReceipt().toView()

        assertEquals(3L, view.id)
        assertEquals("taxi.pdf", view.fileName)
        assertEquals("Taxi Berlin", view.description)
        assertEquals("receipt-pool/abc.pdf", view.objectKey)
        assertEquals(2048L, view.sizeBytes)
        assertEquals(uploaded, view.uploadedAt)
        assertEquals(LocalDate.of(2026, 4, 1), view.documentDate)
        assertEquals(ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, view.normalisationStage)
        assertEquals(4096L, view.originalSizeBytes)
    }

    @Test
    fun `toView keeps optional fields null on a bare receipt`() {
        val view = Receipt().toView()

        assertNull(view.id)
        assertNull(view.sizeBytes)
        assertNull(view.uploadedAt)
        assertNull(view.documentDate)
        assertNull(view.originalSizeBytes)
        assertEquals("", view.fileName)
        assertEquals(ReceiptNormalisationStage.NONE, view.normalisationStage)
    }

    @Test
    fun `findReceiptViews maps the repository page`() {
        whenever(repository.findAll(1, 50, "taxi")).thenReturn(listOf(fullReceipt()))

        assertEquals(listOf(fullReceipt().toView()), service.findReceiptViews(1, 50, "taxi"))
    }

    @Test
    fun `findReceiptViewsSorted maps the sorted repository page`() {
        val sort = GridSort("fileName", SortDirection.DESC)
        whenever(repository.findAllSorted(0, 25, null, sort)).thenReturn(listOf(fullReceipt()))

        assertEquals(listOf(fullReceipt().toView()), service.findReceiptViewsSorted(0, 25, null, ReceiptSort("fileName", true)))
    }

    @Test
    fun `findReceiptViewsSorted sorts ascending when descending is false`() {
        val sort = GridSort("description", SortDirection.ASC)
        whenever(repository.findAllSorted(2, 10, "x", sort)).thenReturn(listOf(fullReceipt()))

        assertEquals(listOf(fullReceipt().toView()), service.findReceiptViewsSorted(2, 10, "x", ReceiptSort("description", false)))
    }

    @Test
    fun `findReceiptView maps the receipt`() {
        whenever(repository.findById(3L)).thenReturn(fullReceipt())

        assertEquals(fullReceipt().toView(), service.findReceiptView(3L))
    }

    @Test
    fun `findReceiptView throws a user-facing error for an unknown id`() {
        whenever(repository.findById(404L)).thenReturn(null)

        assertThrows(ApplicationNotFoundException::class.java) { service.findReceiptView(404L) }
    }
}
