package de.littletrotter.application.invoice

import de.littletrotter.application.cache.InvoiceCachePort
import de.littletrotter.application.error.ApplicationNotFoundException
import de.littletrotter.domain.exception.ReceiptLockedByIssuedInvoiceException
import de.littletrotter.domain.exception.ReceiptNotFoundException
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.repository.ReceiptUsage
import de.littletrotter.domain.shared.GridSort
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.shared.DocumentStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * [ReceiptPoolService] on its own, every port mocked (unit layer): CRUD/usage concerns of
 * the receipt pool (listing, byte retrieval, edit-lock, usage summaries, delete-guard).
 */
class ReceiptPoolServiceTest {

    private val receiptRepository: ReceiptRepository = mock()
    private val documentStore: DocumentStore = mock()
    private val cache: InvoiceCachePort = mock()

    private fun service() = ReceiptPoolService(receiptRepository, documentStore, cache)

    private fun receipt(id: Long = 1L, objectKey: String = "receipt-pool/abc.pdf", checksum: String? = "checksum-abc") = Receipt().apply {
        this.id = id
        fileName = "beleg.pdf"
        description = "Werkzeugkauf"
        this.objectKey = objectKey
        documentDate = LocalDate.of(2026, 2, 1)
        sizeBytes = 1024L
        sha256Checksum = checksum
        uploadedAt = java.time.OffsetDateTime.now()
    }

    // ── happy path ──────────────────────────────────────────────────────────────

    @Test
    fun `happy path - findAll findAllSorted countAll and findById all delegate to the repository`() {
        val r = receipt()
        whenever(receiptRepository.findAll(0, 60, null)).thenReturn(listOf(r))
        whenever(receiptRepository.findAllSorted(1, 20, "abc", GridSort("description", SortDirection.ASC))).thenReturn(listOf(r))
        whenever(receiptRepository.count("abc")).thenReturn(1L)
        whenever(receiptRepository.findById(1L)).thenReturn(r)

        val sut = service()

        assertThat(sut.findAll()).containsExactly(r)
        assertThat(sut.findAllSorted(1, 20, "abc", GridSort("description", SortDirection.ASC))).containsExactly(r)
        assertThat(sut.countAll("abc")).isEqualTo(1L)
        assertThat(sut.findById(1L)).isEqualTo(r)
    }

    @Test
    fun `the paged findAll overload delegates page index size and filter text verbatim`() {
        val r = receipt()
        whenever(receiptRepository.findAll(2, 10, "xyz")).thenReturn(listOf(r))

        assertThat(service().findAll(2, 10, "xyz")).containsExactly(r)
    }

    @Test
    fun `findReceiptUsage delegates to the repository unchanged`() {
        val usage = listOf(ReceiptUsage(invoiceId = 1L, invoiceNumber = "RE-1"))
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(usage)

        assertThat(service().findReceiptUsage(1L)).isEqualTo(usage)
    }

    // ── boundary: findById on an unknown id translates to ReceiptNotFoundException ─────────

    @Test
    fun `boundary - findById on an unknown id throws ApplicationNotFoundException`() {
        whenever(receiptRepository.findById(404L)).thenReturn(null)

        assertThatThrownBy { service().findById(404L) }
            .isInstanceOf(ApplicationNotFoundException::class.java)
            .hasMessageContaining("404")
    }

    // ── boundary: getBytes translates a store-side failure to ReceiptNotFoundException ─────

    @Test
    fun `boundary - getBytes translates a missing store object into ReceiptNotFoundException with the store failure chained`() {
        val r = receipt()
        whenever(receiptRepository.findById(1L)).thenReturn(r)
        val storeFailure = NoSuchElementException("no such key")
        whenever(documentStore.get(r.objectKey)).thenThrow(storeFailure)

        assertThatThrownBy { service().getBytes(1L) }
            .isInstanceOf(ReceiptNotFoundException::class.java)
            .hasCause(storeFailure)
    }

    @Test
    fun `getBytes returns the store's bytes for a receipt that resolves cleanly`() {
        val r = receipt()
        whenever(receiptRepository.findById(1L)).thenReturn(r)
        whenever(documentStore.get(r.objectKey)).thenReturn(byteArrayOf(1, 2, 3))

        assertThat(service().getBytes(1L)).isEqualTo(byteArrayOf(1, 2, 3))
    }

    // ── boundary: updateDetails refuses a blank description / missing date / locked receipt ──

    @Test
    fun `boundary - updateDetails refuses a blank description before ever touching the repository`() {
        assertThatThrownBy { service().updateDetails(1L, "   ", LocalDate.now()) }
            .isInstanceOf(de.littletrotter.application.error.ApplicationValidationException::class.java)
        verify(receiptRepository, never()).findById(any())
    }

    @Test
    fun `boundary - updateDetails refuses a missing document date`() {
        assertThatThrownBy { service().updateDetails(1L, "Werkzeugkauf", null) }
            .isInstanceOf(de.littletrotter.application.error.ApplicationValidationException::class.java)
        verify(receiptRepository, never()).findById(any())
    }

    @Test
    fun `boundary - updateDetails refuses editing a receipt whose only linked invoice is already issued`() {
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(
            listOf(ReceiptUsage(invoiceId = 9L, invoiceNumber = "RE-1", invoiceIssued = true)),
        )

        assertThatThrownBy { service().updateDetails(1L, "Werkzeugkauf", LocalDate.now()) }
            .isInstanceOf(ReceiptLockedByIssuedInvoiceException::class.java)
        verify(receiptRepository, never()).save(any())
    }

    @Test
    fun `updateDetails saves the trimmed description and date when the receipt is editable`() {
        val r = receipt()
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(emptyList())
        whenever(receiptRepository.findById(1L)).thenReturn(r)
        whenever(receiptRepository.save(any())).thenAnswer { it.arguments[0] }

        service().updateDetails(1L, "  Neue Beschreibung  ", LocalDate.of(2026, 3, 1))

        assertThat(r.description).isEqualTo("Neue Beschreibung")
        assertThat(r.documentDate).isEqualTo(LocalDate.of(2026, 3, 1))
        verify(receiptRepository).save(r)
    }

    @Test
    fun `updateDetails is not locked when the only linked invoice is a draft`() {
        val r = receipt()
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(
            listOf(ReceiptUsage(invoiceId = 9L, invoiceNumber = "", invoiceIssued = false)),
        )
        whenever(receiptRepository.findById(1L)).thenReturn(r)
        whenever(receiptRepository.save(any())).thenAnswer { it.arguments[0] }

        service().updateDetails(1L, "Werkzeugkauf", LocalDate.now())

        verify(receiptRepository).save(r)
    }

    // ── findLockedReceiptIds ──────────────────────────────────────────────────────

    @Test
    fun `findLockedReceiptIds returns only ids whose usage includes an issued invoice`() {
        whenever(receiptRepository.findReceiptUsageBatch(listOf(1L, 2L))).thenReturn(
            mapOf(
                1L to listOf(ReceiptUsage(invoiceId = 9L, invoiceNumber = "RE-1", invoiceIssued = true)),
                2L to listOf(ReceiptUsage(invoiceId = 10L, invoiceNumber = "RE-2", invoiceIssued = false)),
            ),
        )

        assertThat(service().findLockedReceiptIds(listOf(1L, 2L))).containsExactly(1L)
    }

    // ── findReceiptUsageSummary ──────────────────────────────────────────────────

    @Test
    fun `boundary - findReceiptUsageSummary falls back to the unused text when there is no usage at all`() {
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(emptyList())

        assertThat(service().findReceiptUsageSummary(1L, "Unbenutzt", "gesamte Rechnung"))
            .isEqualTo("Unbenutzt")
    }

    @Test
    fun `findReceiptUsageSummary joins invoice-level and position-level usage labels`() {
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(
            listOf(
                ReceiptUsage(invoiceId = 1L, invoiceNumber = "RE-1", positionId = "3"),
                ReceiptUsage(invoiceId = 2L, invoiceNumber = "RE-2", positionId = null),
            ),
        )

        assertThat(service().findReceiptUsageSummary(1L, "Unbenutzt", "gesamte Rechnung"))
            .isEqualTo("RE-1 3, RE-2 gesamte Rechnung")
    }

    // ── checkDeleteEligibility / delete ───────────────────────────────────────────

    @Test
    fun `checkDeleteEligibility reports the distinct, non-blank invoice numbers and the raw usage count`() {
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(
            listOf(
                ReceiptUsage(invoiceId = 1L, invoiceNumber = "RE-1"),
                ReceiptUsage(invoiceId = 1L, invoiceNumber = "RE-1"),
                ReceiptUsage(invoiceId = 2L, invoiceNumber = ""),
            ),
        )

        val check = service().checkDeleteEligibility(1L)

        assertThat(check.assignedInvoiceNumbers).containsExactly("RE-1")
        assertThat(check.usageCount).isEqualTo(3)
        assertThat(check.deletable).isFalse()
    }

    @Test
    fun `boundary - delete refuses a receipt that is still assigned to any invoice`() {
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(listOf(ReceiptUsage(invoiceId = 1L, invoiceNumber = "RE-1")))

        assertThatThrownBy { service().delete(1L) }.isInstanceOf(ReceiptAssignedToInvoiceException::class.java)
        verify(receiptRepository, never()).delete(any())
    }

    @Test
    fun `delete removes the row, the object and evicts the checksum for an unassigned receipt`() {
        val r = receipt(checksum = "checksum-abc")
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(emptyList())
        whenever(receiptRepository.findById(1L)).thenReturn(r)

        service().delete(1L)

        verify(receiptRepository).delete(1L)
        verify(documentStore).delete(r.objectKey)
        verify(cache).evictChecksum("checksum-abc")
    }

    @Test
    fun `boundary - delete tolerates an orphaned store object, logging instead of failing`() {
        val r = receipt()
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(emptyList())
        whenever(receiptRepository.findById(1L)).thenReturn(r)
        whenever(documentStore.delete(r.objectKey)).thenThrow(NoSuchElementException("already gone"))

        service().delete(1L)

        verify(receiptRepository).delete(1L)
        verify(cache).evictChecksum("checksum-abc")
    }

    @Test
    fun `delete never evicts a checksum that was never recorded`() {
        val r = receipt(checksum = null)
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(emptyList())
        whenever(receiptRepository.findById(1L)).thenReturn(r)

        service().delete(1L)

        verify(cache, never()).evictChecksum(any())
    }

    @Test
    fun `boundary - delete never evicts a checksum that is present but blank, distinct from absent`() {
        val r = receipt(checksum = "   ")
        whenever(receiptRepository.findReceiptUsage(1L)).thenReturn(emptyList())
        whenever(receiptRepository.findById(1L)).thenReturn(r)

        service().delete(1L)

        verify(cache, never()).evictChecksum(any())
    }

    // ── findUsageSummaries ────────────────────────────────────────────────────────

    @Test
    fun `findUsageSummaries builds one label per id, falling back to the unused text for ids with no usage`() {
        whenever(receiptRepository.findReceiptUsageBatch(listOf(1L, 2L))).thenReturn(
            mapOf(1L to listOf(ReceiptUsage(invoiceId = 9L, invoiceNumber = "RE-1", positionId = "2"))),
        )

        val summaries = service().findUsageSummaries(listOf(1L, 2L), "Unbenutzt", "gesamte Rechnung")

        assertThat(summaries).containsExactlyInAnyOrderEntriesOf(mapOf(1L to "RE-1 2", 2L to "Unbenutzt"))
    }

    // ── ReceiptDeleteCheck / summaryLabel / exception message ─────────────────────

    @Test
    fun `summaryLabel names the position when one is present and the invoice-level label otherwise`() {
        val positionUsage = ReceiptUsage(invoiceId = 1L, invoiceNumber = "RE-1", positionId = "5")
        val invoiceUsage = ReceiptUsage(invoiceId = 1L, invoiceNumber = "RE-1", positionId = null)

        assertThat(positionUsage.summaryLabel("gesamte Rechnung")).isEqualTo("RE-1 5")
        assertThat(invoiceUsage.summaryLabel("gesamte Rechnung")).isEqualTo("RE-1 gesamte Rechnung")
    }

    @Test
    fun `boundary - ReceiptAssignedToInvoiceException names every assigned invoice number in its message`() {
        val ex = ReceiptAssignedToInvoiceException(listOf("RE-1", "RE-2"), usageCount = 3)

        assertThat(ex.message).contains("3 invoice reference(s)").contains("#RE-1").contains("#RE-2")
    }

    @Test
    fun `ReceiptAssignedToInvoiceException omits the invoice-list suffix when no invoice number is known yet`() {
        val ex = ReceiptAssignedToInvoiceException(emptyList(), usageCount = 1)

        assertThat(ex.message).isEqualTo("Receipt is assigned to 1 invoice reference(s) and cannot be deleted")
    }
}
