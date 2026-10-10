package de.littletrotter.application.invoice

import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDetailQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocument
import de.littletrotter.application.quote.port.inbound.QuoteDocumentContent
import de.littletrotter.application.quote.port.inbound.QuoteDocumentFileQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteDocumentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.ui.view.quote.quoteDetail
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class QuoteOfferReaderTest {

    private val details: QuoteDetailQueryPort = mock()
    private val documents: QuoteDocumentQueryPort = mock()
    private val documentFiles: QuoteDocumentFileQueryPort = mock()
    private val attachments: QuoteAttachmentQueryPort = mock()
    private val reader = QuoteOfferReader(details, documents, documentFiles, attachments)

    private val quoteId = UUID.randomUUID()
    private val command = CreateInvoiceFromQuoteCommand(quoteId, "AN-1", LocalDate.of(2026, 10, 5), null, "clerk", UUID.randomUUID())

    private fun document(id: String, mark: QuoteDocumentMark, status: QuoteDocumentStatus = QuoteDocumentStatus.COMPLETED, at: String = "2026-10-01T10:00:00Z") =
        QuoteDocument(id, mark, status, requestedAt = Instant.parse(at), completedAt = Instant.parse(at))

    private fun attachment(id: String, kind: QuoteAttachmentKind, mime: String, name: String = "$id.pdf") =
        QuoteAttachment(id, kind, name, mime, 10, "sha", Instant.parse("2026-10-02T23:30:00Z"))

    private fun givenOffer(vararg docs: QuoteDocument) {
        whenever(details.find(quoteId.toString())).thenReturn(quoteDetail(id = quoteId.toString()))
        whenever(documents.documents(quoteId.toString())).thenReturn(docs.toList())
        whenever(documentFiles.pdf(quoteId.toString(), "dispatch-new")).thenReturn(QuoteDocumentContent("angebot-neu.pdf", byteArrayOf(1)))
        whenever(documentFiles.pdf(quoteId.toString(), "review")).thenReturn(QuoteDocumentContent("angebot-review.pdf", byteArrayOf(2)))
        whenever(attachments.attachments(quoteId.toString())).thenReturn(emptyList())
    }

    @Test
    fun `the newest completed dispatch document becomes the first receipt`() {
        givenOffer(
            document("dispatch-old", QuoteDocumentMark.DISPATCH, at = "2026-09-01T10:00:00Z"),
            document("dispatch-new", QuoteDocumentMark.DISPATCH, at = "2026-10-01T10:00:00Z"),
            document("review", QuoteDocumentMark.REVIEW),
        )

        val prepared = reader.read(command)

        val receipt = prepared.receipts.single()
        assertEquals("angebot-neu.pdf", receipt.fileName)
        assertEquals("Angebot AN-1: Angebot angebot-neu.pdf", receipt.description)
        assertEquals(LocalDate.of(2026, 10, 1), receipt.documentDate)
        assertArrayEquals(byteArrayOf(1), receipt.bytes)
    }

    @Test
    fun `without a dispatch document the reviewed one is used and unfinished documents are ignored`() {
        givenOffer(
            document("review", QuoteDocumentMark.REVIEW),
            document("dispatch-new", QuoteDocumentMark.DISPATCH, status = QuoteDocumentStatus.REQUESTED),
        )

        assertEquals("angebot-review.pdf", reader.read(command).receipts.single().fileName)
    }

    @Test
    fun `an offer without a finished document is refused`() {
        givenOffer(document("draft", QuoteDocumentMark.DRAFT), document("dispatch-new", QuoteDocumentMark.DISPATCH, QuoteDocumentStatus.FAILED))

        val refusal = assertThrows(QuoteInvoiceRejectedException::class.java) { reader.read(command) }

        assertEquals("Das Angebotsdokument (PDF) liegt nicht vor.", refusal.reason)
    }

    @Test
    fun `customer pdf attachments become receipts, everything else stays at the offer and is named`() {
        givenOffer(document("dispatch-new", QuoteDocumentMark.DISPATCH))
        whenever(attachments.attachments(quoteId.toString())).thenReturn(
            listOf(
                attachment("a1", QuoteAttachmentKind.CUSTOMER, "application/pdf", "datenblatt.pdf"),
                attachment("a2", QuoteAttachmentKind.CUSTOMER, "Application/PDF; charset=binary", "zeichnung.pdf"),
                attachment("a3", QuoteAttachmentKind.CUSTOMER, "image/png", "foto.png"),
                attachment("a4", QuoteAttachmentKind.INTERNAL, "application/pdf", "kalkulation.pdf"),
            ),
        )
        whenever(attachments.content(quoteId.toString(), "a1")).thenReturn(QuoteAttachmentContent("datenblatt.pdf", "application/pdf", byteArrayOf(7)))
        whenever(attachments.content(quoteId.toString(), "a2")).thenReturn(QuoteAttachmentContent("zeichnung.pdf", "application/pdf", byteArrayOf(8)))

        val prepared = reader.read(command)

        assertEquals(listOf("angebot-neu.pdf", "datenblatt.pdf", "zeichnung.pdf"), prepared.receipts.map { it.fileName })
        assertEquals("Angebot AN-1: Anhang datenblatt.pdf", prepared.receipts[1].description)
        // Uploaded 23:30 UTC, which is already 01:30 of the next day in Germany.
        assertEquals(LocalDate.of(2026, 10, 3), prepared.receipts[1].documentDate)
        assertEquals(listOf("foto.png", "kalkulation.pdf"), prepared.skippedAttachments)
        verify(attachments, never()).content(quoteId.toString(), "a3")
        verify(attachments, never()).content(quoteId.toString(), "a4")
    }

    @Test
    fun `a library attachment is described by title and version, an incomplete reference falls back to the file name`() {
        givenOffer(document("dispatch-new", QuoteDocumentMark.DISPATCH))
        val fromLibrary = attachment("a1", QuoteAttachmentKind.CUSTOMER, "application/pdf", "agb-2.pdf").copy(libraryTitle = "AGB", versionNo = 2)
        val titleOnly = attachment("a2", QuoteAttachmentKind.CUSTOMER, "application/pdf", "x.pdf").copy(libraryTitle = "AGB")
        whenever(attachments.attachments(quoteId.toString())).thenReturn(listOf(fromLibrary, titleOnly))
        whenever(attachments.content(quoteId.toString(), "a1")).thenReturn(QuoteAttachmentContent("agb-2.pdf", "application/pdf", byteArrayOf(7)))
        whenever(attachments.content(quoteId.toString(), "a2")).thenReturn(QuoteAttachmentContent("x.pdf", "application/pdf", byteArrayOf(8)))

        val prepared = reader.read(command)

        assertEquals("Angebot AN-1: AGB (Fassung 2)", prepared.receipts[1].description)
        assertEquals("Angebot AN-1: Anhang x.pdf", prepared.receipts[2].description)
    }
}
