package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import de.littletrotter.application.invoice.port.inbound.HardToReadPage
import de.littletrotter.application.invoice.port.inbound.PreviewReceiptReductionQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptAutoReduceQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptReductionPreview
import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentUseCase
import de.littletrotter.application.quote.port.inbound.AttachQuoteFileUseCase
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryFile
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentUpload
import de.littletrotter.application.quote.port.inbound.RemoveQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.inbound.StoredQuoteFile
import de.littletrotter.application.quote.port.inbound.StoredQuoteReduction
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.DirectPollTicks
import de.littletrotter.ui.view.invoice.ReceiptUploadAdvice
import de.littletrotter.ui.view.invoice.ReceiptUploadAdviceSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Quote attachment uploads follow the invoice receipt rule at every entry point: with the receipt
 * setting on a large PDF is reduced without a question and reported as "before to after"; with it
 * off the receipt question dialog decides. Only PDFs above the target size are ever asked about.
 */
class QuoteUploadReductionKaribuTest {

    private val autoReduce: ReceiptAutoReduceQueryPort = mock()
    private val preview: PreviewReceiptReductionQueryPort = mock()
    private val query: QuoteAttachmentQueryPort = mock()
    private val attach: AttachQuoteFileUseCase = mock()
    private val readability: ReceiptReadabilityQueryPort = mock()
    private val originalProvider = ReceiptUploadAdviceSource.provider
    private val largeBytes = 900 * 1024
    private val storedBytes = 300L * 1024
    private val large = ByteArray(largeBytes)

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        MockVaadin.setup()
        ReceiptUploadAdviceSource.provider = { ReceiptUploadAdvice(autoReduce, preview) }
        whenever(readability.findHardToReadPages(any())).thenReturn(emptyList())
        whenever(autoReduce.isAutoReduceEnabled()).thenReturn(true)
        whenever(preview.previewReduction(any())).thenReturn(
            ReceiptReductionPreview(DocumentSize.ofBytes(largeBytes.toLong()), DocumentSize.ofBytes(storedBytes), true, byteArrayOf(1)),
        )
        whenever(attach.attach(any(), any())).thenReturn(StoredQuoteFile("f-9", StoredQuoteReduction(ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, storedBytes)))
    }

    @AfterEach
    fun teardown() {
        ReceiptUploadAdviceSource.provider = originalProvider
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun tab(): QuoteAttachmentsTab {
        val library: AttachmentLibraryQueryPort = mock()
        whenever(library.entries()).thenReturn(emptyList())
        val ports = QuoteAttachmentsTabPorts(
            query, attach, mock<RemoveQuoteAttachmentUseCase>(), mock<AssignQuoteAttachmentUseCase>(), library, mock<AttachLibraryDocumentUseCase>(), readability,
        )
        return QuoteAttachmentsTab(testHost(), ports).also {
            UI.getCurrent().add(it)
            it.bind("q-1", quoteDetail().items)
        }
    }

    private fun itemDialog() =
        QuoteItemAttachmentsDialog(testHost(), "q-1", quoteItem(1), QuoteItemAttachmentPorts(query, attach, mock(), readability = readability)) { }.also { it.open() }

    private fun sentUpload(): QuoteAttachmentUpload =
        argumentCaptor<QuoteAttachmentUpload>().also { verify(attach).attach(org.mockito.kotlin.eq("q-1"), it.capture()) }.firstValue

    private fun fromTo() = UiTranslations.tr(
        "receipt.autoReduced",
        DocumentSizeFormatter.format(DocumentSize.ofBytes(largeBytes.toLong())),
        DocumentSizeFormatter.format(DocumentSize.ofBytes(storedBytes)),
    )

    private fun choose(key: String) = _find<Button>().single { it.text == UiTranslations.tr(key) }._click()

    @Test
    fun `tab - setting on reduces a large PDF without asking and reports before and after`() {
        tab().onUploaded("plan.pdf", "application/pdf", large)

        assertTrue(sentUpload().reduce)
        assertTrue(_find<Dialog>().isEmpty())
        assertTrue(notificationTexts().any { it.contains(fromTo()) }, notificationTexts().toString())
    }

    @Test
    fun `tab - a rasterised result carries the text layer hint, an images-only result does not`() {
        val hint = UiTranslations.tr("receipt.reduced.rasterised")
        tab().onUploaded("plan.pdf", "application/pdf", large)
        assertFalse(notificationTexts().any { it.contains(hint) }, notificationTexts().toString())

        whenever(attach.attach(any(), any())).thenReturn(StoredQuoteFile("f-9", StoredQuoteReduction(ReceiptNormalisationStage.RASTERISED, storedBytes)))
        tab().onUploaded("plan.pdf", "application/pdf", large)

        assertTrue(notificationTexts().any { it.contains(fromTo()) && it.contains(hint) }, notificationTexts().toString())
    }

    @Test
    fun `item dialog - a rasterised result carries the text layer hint`() {
        whenever(attach.attach(any(), any())).thenReturn(StoredQuoteFile("f-9", StoredQuoteReduction(ReceiptNormalisationStage.RASTERISED, storedBytes)))

        itemDialog().onUploaded("plan.pdf", "application/pdf", large)

        assertTrue(notificationTexts().any { it.contains(UiTranslations.tr("receipt.reduced.rasterised")) }, notificationTexts().toString())
    }

    @Test
    fun `tab - setting off asks with sizes and preview and sends nothing before the answer`() {
        whenever(autoReduce.isAutoReduceEnabled()).thenReturn(false)

        tab().onUploaded("plan.pdf", "application/pdf", large)

        verify(attach, never()).attach(any(), any())
        assertEquals(UiTranslations.tr("receipt.reduceDialog.title"), _find<Dialog>().single().headerTitle)
        choose("receipt.reduceDialog.reduced")
        assertTrue(sentUpload().reduce)
    }

    @Test
    fun `tab - keeping the original sends the file as it is`() {
        whenever(autoReduce.isAutoReduceEnabled()).thenReturn(false)
        whenever(attach.attach(any(), any())).thenReturn(StoredQuoteFile("f-9"))

        tab().onUploaded("plan.pdf", "application/pdf", large)
        choose("receipt.reduceDialog.original")

        assertFalse(sentUpload().reduce)
        assertTrue(notificationTexts().any { it.contains("plan.pdf") })
    }

    @Test
    fun `tab - a small PDF and a large non-PDF are stored as sent without a question`() {
        val tab = tab()

        tab.onUploaded("klein.pdf", "application/pdf", ByteArray(ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES.toInt()))
        tab.onUploaded("foto.png", "image/png", large)

        val sent = argumentCaptor<QuoteAttachmentUpload>().also { verify(attach, org.mockito.kotlin.times(2)).attach(any(), it.capture()) }.allValues
        assertEquals(listOf(false, false), sent.map { it.reduce })
        assertTrue(_find<Dialog>().isEmpty())
        verify(preview, never()).previewReduction(any())
    }

    @Test
    fun `item dialog - setting on reduces, setting off asks`() {
        itemDialog().onUploaded("plan.pdf", "application/pdf", large)
        assertTrue(sentUpload().reduce)
        assertTrue(notificationTexts().any { it.contains(fromTo()) })
    }

    @Test
    fun `item dialog - setting off asks and the answer travels with the upload`() {
        whenever(autoReduce.isAutoReduceEnabled()).thenReturn(false)

        itemDialog().onUploaded("plan.pdf", "application/pdf", large)
        verify(attach, never()).attach(any(), any())
        choose("receipt.reduceDialog.original")

        assertFalse(sentUpload().reduce)
    }

    @Test
    fun `library dialog - setting on marks the file for reduction`() {
        var saved: AttachmentLibraryFile? = null
        val dialog = QuoteReceiptPoolUploadDialog.forNewVersion(testHost(), readability, libraryEntry("l-1", "AGB", libraryVersion("v-1", 1))) { saved = it }.also { it.open() }

        dialog.onFile("agb.pdf", "application/pdf", large)
        dialog.save()

        assertTrue(saved!!.reduce)
        assertTrue(_find<Dialog>().none { it.headerTitle == UiTranslations.tr("receipt.reduceDialog.title") })
    }

    @Test
    fun `library dialog - setting off asks before saving and keeps the dialog until answered`() {
        whenever(autoReduce.isAutoReduceEnabled()).thenReturn(false)
        var saved: AttachmentLibraryFile? = null
        val dialog = QuoteReceiptPoolUploadDialog.forNewVersion(testHost(), readability, libraryEntry("l-1", "AGB", libraryVersion("v-1", 1))) { saved = it }.also { it.open() }

        dialog.onFile("agb.pdf", "application/pdf", large)
        dialog.save()
        assertEquals(null, saved)
        assertTrue(dialog.isOpened)

        choose("receipt.reduceDialog.original")

        assertFalse(saved!!.reduce)
        assertFalse(dialog.isOpened)
    }

    @Test
    fun `library dialog - a new entry carries the decision with its file`() {
        var created: de.littletrotter.application.quote.port.inbound.NewAttachmentLibraryEntry? = null
        val dialog = QuoteReceiptPoolUploadDialog.forNewEntry(testHost(), readability) { created = it }.also { it.open() }
        dialog.title.value = "AGB"
        dialog.description.value = "Bedingungen"

        dialog.onFile("agb.pdf", "application/pdf", large)
        dialog.save()

        assertTrue(created!!.file.reduce)
        assertEquals("AGB", created!!.title)
    }

    @Test
    fun `readability - a poor page shows the hint first and rescan ends the upload`() {
        whenever(readability.findHardToReadPages(any())).thenReturn(listOf(HardToReadPage(2, 70, byteArrayOf(1))))

        tab().onUploaded("plan.pdf", "application/pdf", large)

        assertEquals(UiTranslations.tr("receipt.readability.title"), _find<Dialog>().single().headerTitle)
        verify(attach, never()).attach(any(), any())
        choose("receipt.readability.rescan")

        verify(attach, never()).attach(any(), any())
        verify(preview, never()).previewReduction(any())
    }

    @Test
    fun `readability - accepting continues to the size decision`() {
        whenever(readability.findHardToReadPages(any())).thenReturn(listOf(HardToReadPage(2, null, byteArrayOf(1))))
        whenever(autoReduce.isAutoReduceEnabled()).thenReturn(false)

        itemDialog().onUploaded("plan.pdf", "application/pdf", large)
        choose("receipt.readability.accept")

        assertEquals(UiTranslations.tr("receipt.reduceDialog.title"), _find<Dialog>().single { it.isOpened && it.headerTitle == UiTranslations.tr("receipt.reduceDialog.title") }.headerTitle)
        choose("receipt.reduceDialog.original")
        assertFalse(sentUpload().reduce)
    }

    @Test
    fun `readability - a small PDF is checked too, a good file shows no hint`() {
        tab().onUploaded("klein.pdf", "application/pdf", ByteArray(1024))

        verify(readability).findHardToReadPages(any())
        assertTrue(_find<Dialog>().isEmpty())
        assertFalse(sentUpload().reduce)
    }

    @Test
    fun `readability - a library file with a poor page asks before saving`() {
        whenever(readability.findHardToReadPages(any())).thenReturn(listOf(HardToReadPage(1, 60, byteArrayOf(1))))
        var saved: AttachmentLibraryFile? = null
        val dialog = QuoteReceiptPoolUploadDialog.forNewVersion(testHost(), readability, libraryEntry("l-1", "AGB", libraryVersion("v-1", 1))) { saved = it }.also { it.open() }

        dialog.onFile("agb.pdf", "application/pdf", large)
        dialog.save()
        assertEquals(null, saved)
        choose("receipt.readability.accept")

        assertTrue(saved!!.reduce)
    }
}
