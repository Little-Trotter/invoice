package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentCommand
import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderCommand
import de.littletrotter.application.quote.port.inbound.QuoteArticleOption
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentUpload
import de.littletrotter.application.quote.port.inbound.QuoteCustomerOption
import de.littletrotter.application.quote.port.inbound.QuoteDashboard
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteDispatchHandover
import de.littletrotter.application.quote.port.inbound.QuoteDocument
import de.littletrotter.application.quote.port.inbound.QuoteDocumentContent
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.quote.port.inbound.QuoteHeaderField
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteLogEntry
import de.littletrotter.application.quote.port.inbound.QuotePage
import de.littletrotter.application.quote.port.inbound.QuoteRankingKind
import de.littletrotter.application.quote.port.inbound.QuoteRankingRow
import de.littletrotter.application.quote.port.inbound.QuoteSettings
import de.littletrotter.application.quote.port.inbound.QuoteStatistics
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection
import de.littletrotter.application.quote.port.inbound.QuoteValidationReport
import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat
import de.littletrotter.application.quote.port.inbound.StoredQuoteFile
import de.littletrotter.application.quote.port.inbound.StoredQuoteReduction
import de.littletrotter.application.quote.port.outbound.AcceptQuoteRemote
import de.littletrotter.application.quote.port.outbound.AddQuoteNoteRemote
import de.littletrotter.application.quote.port.outbound.AssignQuoteAttachmentRemote
import de.littletrotter.application.quote.port.outbound.AttachQuoteFileRemote
import de.littletrotter.application.quote.port.outbound.CancelQuoteRemote
import de.littletrotter.application.quote.port.outbound.CreateQuoteRemote
import de.littletrotter.application.quote.port.outbound.EditQuoteHeaderRemote
import de.littletrotter.application.quote.port.outbound.EditQuoteItemsRemote
import de.littletrotter.application.quote.port.outbound.QuoteArticleLookupRemote
import de.littletrotter.application.quote.port.outbound.QuoteAttachmentRemote
import de.littletrotter.application.quote.port.outbound.QuoteByNumberRemote
import de.littletrotter.application.quote.port.outbound.QuoteCustomerLookupRemote
import de.littletrotter.application.quote.port.outbound.QuoteDetailRemote
import de.littletrotter.application.quote.port.outbound.QuoteDocumentFileRemote
import de.littletrotter.application.quote.port.outbound.QuoteDocumentRemote
import de.littletrotter.application.quote.port.outbound.QuoteHeaderFieldRemote
import de.littletrotter.application.quote.port.outbound.QuoteListRemote
import de.littletrotter.application.quote.port.outbound.QuoteLogRemote
import de.littletrotter.application.quote.port.outbound.QuoteRankingRemote
import de.littletrotter.application.quote.port.outbound.QuoteSettingsRemote
import de.littletrotter.application.quote.port.outbound.QuoteStatisticsDashboardRemote
import de.littletrotter.application.quote.port.outbound.QuoteStatisticsRemote
import de.littletrotter.application.quote.port.outbound.QuoteValidationReportRemote
import de.littletrotter.application.quote.port.outbound.RejectQuoteRemote
import de.littletrotter.application.quote.port.outbound.RemoveQuoteAttachmentRemote
import de.littletrotter.application.quote.port.outbound.RequestQuoteDocumentRemote
import de.littletrotter.application.quote.port.outbound.RequestQuoteInvoiceRemote
import de.littletrotter.application.quote.port.outbound.ReturnQuoteToDraftRemote
import de.littletrotter.application.quote.port.outbound.SendQuoteRemote
import de.littletrotter.application.quote.port.outbound.SubmitQuoteForReviewRemote
import de.littletrotter.application.quote.port.outbound.UpdateQuoteSettingsRemote
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

/** Every quote application service forwards its call to the outbound port unchanged. */
class QuoteApplicationServicesTest {

    @Test
    fun `AddQuoteNoteService addNote delegates`() {
        val remote = mock<AddQuoteNoteRemote>()
        AddQuoteNoteService(remote).addNote("x", "x")
        verify(remote).addNote("x", "x")
    }

    @Test
    fun `AttachQuoteFileService attach delegates`() {
        val remote = mock<AttachQuoteFileRemote>()
        val a1: QuoteAttachmentUpload = mock()
        val stored = StoredQuoteFile("id", StoredQuoteReduction(ReceiptNormalisationStage.RASTERISED, 9))
        whenever(remote.attach("x", a1)).thenReturn(stored)
        assertSame(stored, AttachQuoteFileService(remote).attach("x", a1))
    }

    @Test
    fun `CancelQuoteService cancel delegates`() {
        val remote = mock<CancelQuoteRemote>()
        CancelQuoteService(remote).cancel("x", "x")
        verify(remote).cancel("x", "x")
    }

    @Test
    fun `CreateQuoteService create delegates`() {
        val remote = mock<CreateQuoteRemote>()
        whenever(remote.create("x")).thenReturn("id")
        assertEquals("id", CreateQuoteService(remote).create("x"))
    }

    @Test
    fun `EditQuoteHeaderService editHeader delegates`() {
        val remote = mock<EditQuoteHeaderRemote>()
        val a0: EditQuoteHeaderCommand = mock()
        EditQuoteHeaderService(remote).editHeader(a0)
        verify(remote).editHeader(a0)
    }

    @Test
    fun `EditQuoteItemsService editItems delegates`() {
        val remote = mock<EditQuoteItemsRemote>()
        val a1: List<QuoteItemInput> = mock()
        EditQuoteItemsService(remote).editItems("x", a1)
        verify(remote).editItems("x", a1)
    }

    @Test
    fun `QuoteArticleLookupQueryService articles delegates`() {
        val remote = mock<QuoteArticleLookupRemote>()
        val answer: List<QuoteArticleOption> = emptyList()
        whenever(remote.articles("x", InvoiceItemCategory.PRODUCT, "x", 3)).thenReturn(answer)
        assertSame(answer, QuoteArticleLookupQueryService(remote).articles("x", InvoiceItemCategory.PRODUCT, "x", 3))
    }

    @Test
    fun `QuoteAttachmentQueryService attachments delegates`() {
        val remote = mock<QuoteAttachmentRemote>()
        val answer: List<QuoteAttachment> = emptyList()
        whenever(remote.attachments("x")).thenReturn(answer)
        assertSame(answer, QuoteAttachmentQueryService(remote).attachments("x"))
    }

    @Test
    fun `QuoteAttachmentQueryService content delegates`() {
        val remote = mock<QuoteAttachmentRemote>()
        val answer: QuoteAttachmentContent = mock()
        whenever(remote.content("x", "x")).thenReturn(answer)
        assertSame(answer, QuoteAttachmentQueryService(remote).content("x", "x"))
    }

    @Test
    fun `QuoteCustomerLookupQueryService customers delegates`() {
        val remote = mock<QuoteCustomerLookupRemote>()
        val answer: List<QuoteCustomerOption> = emptyList()
        whenever(remote.customers("x", 3)).thenReturn(answer)
        assertSame(answer, QuoteCustomerLookupQueryService(remote).customers("x", 3))
    }

    @Test
    fun `QuoteDetailQueryService find delegates`() {
        val remote = mock<QuoteDetailRemote>()
        val answer: QuoteDetail = mock()
        whenever(remote.find("x")).thenReturn(answer)
        assertSame(answer, QuoteDetailQueryService(remote).find("x"))
    }

    @Test
    fun `QuoteSendGateQueryService gate delegates`() {
        val remote = mock<de.littletrotter.application.quote.port.outbound.QuoteSendGateRemote>()
        whenever(remote.gate("x")).thenReturn(de.littletrotter.application.quote.port.inbound.QuoteSendGateState.COPY_MISSING)
        assertEquals(de.littletrotter.application.quote.port.inbound.QuoteSendGateState.COPY_MISSING, QuoteSendGateQueryService(remote).gate("x"))
    }

    @Test
    fun `QuoteByNumberQueryService find delegates`() {
        val remote = mock<QuoteByNumberRemote>()
        val answer: QuoteDetail = mock()
        whenever(remote.find("AN-2026-0001")).thenReturn(answer)
        assertSame(answer, QuoteByNumberQueryService(remote).find("AN-2026-0001"))
    }

    @Test
    fun `QuoteHeaderFieldQueryService headerFields delegates`() {
        val remote = mock<QuoteHeaderFieldRemote>()
        val answer: List<QuoteHeaderField> = emptyList()
        whenever(remote.headerFields("x")).thenReturn(answer)
        assertSame(answer, QuoteHeaderFieldQueryService(remote).headerFields("x"))
    }

    @Test
    fun `QuoteListQueryService findPage delegates`() {
        val remote = mock<QuoteListRemote>()
        val a0: QuoteListCriteria = mock()
        val answer: QuotePage = mock()
        whenever(remote.findPage(a0)).thenReturn(answer)
        assertSame(answer, QuoteListQueryService(remote).findPage(a0))
    }

    @Test
    fun `QuoteLogQueryService log delegates`() {
        val remote = mock<QuoteLogRemote>()
        val answer: List<QuoteLogEntry> = emptyList()
        whenever(remote.log("x", emptySet())).thenReturn(answer)
        assertSame(answer, QuoteLogQueryService(remote).log("x", emptySet()))
    }

    @Test
    fun `QuoteSettingsQueryService settings delegates`() {
        val remote = mock<QuoteSettingsRemote>()
        val answer: QuoteSettings = mock()
        whenever(remote.settings()).thenReturn(answer)
        assertSame(answer, QuoteSettingsQueryService(remote).settings())
    }

    @Test
    fun `QuoteStatisticsQueryService statistics delegates`() {
        val remote = mock<QuoteStatisticsRemote>()
        val answer: QuoteStatistics = mock()
        whenever(remote.statistics()).thenReturn(answer)
        assertSame(answer, QuoteStatisticsQueryService(remote).statistics())
    }

    @Test
    fun `QuoteStatisticsDashboardQueryService dashboard delegates with the selection`() {
        val remote = mock<QuoteStatisticsDashboardRemote>()
        val answer: QuoteDashboard = mock()
        val selection = QuoteStatisticsSelection(2025, quarter = 2)
        whenever(remote.dashboard(selection)).thenReturn(answer)
        assertSame(answer, QuoteStatisticsDashboardQueryService(remote).dashboard(selection))
    }

    @Test
    fun `QuoteRankingQueryService ranking delegates with kind, selection and limit`() {
        val remote = mock<QuoteRankingRemote>()
        val rows = listOf(QuoteRankingRow("k", "Acme", 2, Money.of("10.00")))
        val selection = QuoteStatisticsSelection(2025)
        whenever(remote.ranking(QuoteRankingKind.ARTICLE, selection, 5)).thenReturn(rows)
        assertSame(rows, QuoteRankingQueryService(remote).ranking(QuoteRankingKind.ARTICLE, selection, 5))
    }

    @Test
    fun `AcceptQuoteService accept delegates`() {
        val remote = mock<AcceptQuoteRemote>()
        AcceptQuoteService(remote).accept("x", LocalDate.of(2026, 1, 2), "x")
        verify(remote).accept("x", LocalDate.of(2026, 1, 2), "x")
    }

    @Test
    fun `RejectQuoteService reject delegates`() {
        val remote = mock<RejectQuoteRemote>()
        RejectQuoteService(remote).reject("x", "x")
        verify(remote).reject("x", "x")
    }

    @Test
    fun `AssignQuoteAttachmentService assign delegates`() {
        val remote = mock<AssignQuoteAttachmentRemote>()
        val command = AssignQuoteAttachmentCommand("q", "a", "i")
        AssignQuoteAttachmentService(remote).assign(command)
        verify(remote).assign(command)
    }

    @Test
    fun `RemoveQuoteAttachmentService remove delegates`() {
        val remote = mock<RemoveQuoteAttachmentRemote>()
        RemoveQuoteAttachmentService(remote).remove("x", "x")
        verify(remote).remove("x", "x")
    }

    @Test
    fun `ReturnQuoteToDraftService returnToDraft delegates`() {
        val remote = mock<ReturnQuoteToDraftRemote>()
        ReturnQuoteToDraftService(remote).returnToDraft("x", "x")
        verify(remote).returnToDraft("x", "x")
    }

    @Test
    fun `SubmitQuoteForReviewService submit delegates`() {
        val remote = mock<SubmitQuoteForReviewRemote>()
        whenever(remote.submit("x")).thenReturn("id")
        assertEquals("id", SubmitQuoteForReviewService(remote).submit("x"))
    }

    @Test
    fun `SendQuoteService send delegates`() {
        val remote = mock<SendQuoteRemote>()
        val handover = QuoteDispatchHandover("d-1", "mail/compose/d-1")
        whenever(remote.send("x")).thenReturn(handover)
        assertSame(handover, SendQuoteService(remote).send("x"))
    }

    @Test
    fun `UpdateQuoteSettingsService update requires the settings permission`() {
        val method = UpdateQuoteSettingsService::class.java.getMethod("update", Long::class.javaPrimitiveType, QuoteXmlFormat::class.java)

        assertEquals(
            listOf(de.littletrotter.application.security.AppPermission.QUOTE_SETTINGS_EDIT),
            method.getAnnotation(de.littletrotter.application.security.RequiresPermission::class.java).value.toList(),
        )
    }

    @Test
    fun `UpdateQuoteSettingsService update delegates`() {
        val remote = mock<UpdateQuoteSettingsRemote>()
        UpdateQuoteSettingsService(remote).update(3L, QuoteXmlFormat.NONE)
        verify(remote).update(3L, QuoteXmlFormat.NONE)
    }

    @Test
    fun `RequestQuoteDocumentService request delegates`() {
        val remote = mock<RequestQuoteDocumentRemote>()
        whenever(remote.request("q-1", QuoteDocumentMark.REVIEW)).thenReturn("d-1")
        assertEquals("d-1", RequestQuoteDocumentService(remote).request("q-1", QuoteDocumentMark.REVIEW))
    }

    @Test
    fun `QuoteDocumentQueryService documents delegates`() {
        val remote = mock<QuoteDocumentRemote>()
        val answer = listOf(QuoteDocument("d-1", QuoteDocumentMark.DRAFT, QuoteDocumentStatus.REQUESTED, Instant.EPOCH))
        whenever(remote.documents("q-1")).thenReturn(answer)
        assertSame(answer, QuoteDocumentQueryService(remote).documents("q-1"))
    }

    @Test
    fun `QuoteDocumentFileQueryService pdf and xml delegate`() {
        val remote = mock<QuoteDocumentFileRemote>()
        val pdf = QuoteDocumentContent("a.pdf", byteArrayOf(1))
        whenever(remote.pdf("q-1", "d-1")).thenReturn(pdf)
        whenever(remote.xml("q-1", "d-1")).thenReturn(null)
        val service = QuoteDocumentFileQueryService(remote)
        assertSame(pdf, service.pdf("q-1", "d-1"))
        assertNull(service.xml("q-1", "d-1"))
    }

    @Test
    fun `QuoteValidationReportQueryService report delegates`() {
        val remote = mock<QuoteValidationReportRemote>()
        val answer = QuoteValidationReport(false, listOf("BR-Q-01: Fehler"))
        whenever(remote.report("q-1", "d-1")).thenReturn(answer)
        assertSame(answer, QuoteValidationReportQueryService(remote).report("q-1", "d-1"))
    }

    @Test
    fun `RequestQuoteInvoiceService request delegates and returns the order id`() {
        val remote = mock<RequestQuoteInvoiceRemote>()
        val order = QuoteInvoiceOrderRequest(LocalDate.of(2026, 1, 2), "PO", null, null, null)
        whenever(remote.request("x", order)).thenReturn("o-1")
        assertEquals("o-1", RequestQuoteInvoiceService(remote).request("x", order))
    }
}
