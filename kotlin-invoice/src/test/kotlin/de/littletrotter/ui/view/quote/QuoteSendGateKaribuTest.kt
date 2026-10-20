package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.RouteConfiguration
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.quote.QuoteNotSendableException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.AcceptQuoteUseCase
import de.littletrotter.application.quote.port.inbound.AddQuoteNoteUseCase
import de.littletrotter.application.quote.port.inbound.CancelQuoteUseCase
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteByNumberQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDetailQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDispatchHandover
import de.littletrotter.application.quote.port.inbound.QuoteDocument
import de.littletrotter.application.quote.port.inbound.QuoteDocumentFileQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteDocumentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.quote.port.inbound.QuoteLogQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteSendGateQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteSendGateState
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteValidationReportQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteWorkbookExportQueryPort
import de.littletrotter.application.quote.port.inbound.RejectQuoteUseCase
import de.littletrotter.application.quote.port.inbound.RequestQuoteDocumentUseCase
import de.littletrotter.application.quote.port.inbound.RequestQuoteInvoiceUseCase
import de.littletrotter.application.quote.port.inbound.ReturnQuoteToDraftUseCase
import de.littletrotter.application.quote.port.inbound.SendQuoteUseCase
import de.littletrotter.application.quote.port.inbound.SubmitQuoteForReviewUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.ui.view.invoice.DirectPollTicks
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/** The two-step dispatch of the offer detail: the dispatch copy first, then the send behind the gate. */
class QuoteSendGateKaribuTest {

    private val detailQuery: QuoteDetailQueryPort = mock()
    private val byNumberQuery: QuoteByNumberQueryPort = byNumberAnsweringLike(detailQuery)
    private val send: SendQuoteUseCase = mock()
    private val requestDocument: RequestQuoteDocumentUseCase = mock()
    private val documentQuery: QuoteDocumentQueryPort = mock()
    private val gateQuery: QuoteSendGateQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        registerQuoteRoutes()
        RouteConfiguration.forSessionScope().setRoute("mail/compose/:draftId", ComposeStub::class.java)
        whenever(documentQuery.documents(any())).thenReturn(emptyList())
        DirectPollTicks.install()
    }

    @AfterEach
    fun teardown() {
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun showing(gate: QuoteSendGateState?, status: QuoteStatus = QuoteStatus.REVIEW, vararg granted: AppPermission = ALL_QUOTE_PERMISSIONS): QuoteDetailView =
        showing(gate, true, status, *granted)

    private fun showing(gate: QuoteSendGateState?, stubGate: Boolean, status: QuoteStatus, vararg granted: AppPermission): QuoteDetailView {
        whenever(detailQuery.find("q-1")).thenReturn(quoteDetail(status))
        if (gate != null && stubGate) whenever(gateQuery.gate("q-1")).thenReturn(gate)
        val view = QuoteDetailView(
            QuoteEntry(detailQuery, byNumberQuery),
            QuoteLogPorts(mock<QuoteLogQueryPort> { on { log(any(), any()) } doReturn emptyList() }, mock<AddQuoteNoteUseCase>()),
            QuoteLifecyclePorts(
                mock<SubmitQuoteForReviewUseCase>(), mock<ReturnQuoteToDraftUseCase>(), mock<CancelQuoteUseCase>(),
                mock<AcceptQuoteUseCase>(), mock<RejectQuoteUseCase>(), send, mock<RequestQuoteInvoiceUseCase>(),
                gateQuery.takeIf { gate != null },
            ),
            QuoteDocumentPorts(
                requestDocument, documentQuery, mock<QuoteDocumentFileQueryPort>(), mock<QuoteValidationReportQueryPort>(),
                mock<QuoteAttachmentQueryPort>(), mock<QuoteWorkbookExportQueryPort>(),
            ),
            QuoteAvailability(true),
            rolesWith(*granted),
            ClockTimeSource.fixedAt(NOW),
        )
        UI.getCurrent().add(view)
        val event: BeforeEnterEvent = quoteEvent("AN-2026-0001", "quotes/AN-2026-0001")
        view.beforeEnter(event)
        return view
    }

    private fun button(view: QuoteDetailView, action: QuoteAction): Button =
        view.hero.actions!!._get<Button> { id = "quote-action-${action.name}" }

    @Test
    fun `a blocker the user must fix disables the send with its text`() {
        val expected = mapOf(
            QuoteSendGateState.NOT_IN_REVIEW to "Nur ein Angebot in Prüfung kann versendet werden.",
            QuoteSendGateState.COPY_IN_FLIGHT to "Das Versandexemplar wird noch erstellt.",
            QuoteSendGateState.NO_RECIPIENT_EMAIL to "Der Empfänger hat keine E-Mail-Adresse. Bitte in den Stammdaten ergänzen.",
        )
        assertEquals(
            QuoteSendGateState.entries.toSet() - QuoteSendGateState.READY - QuoteSendGateState.COPY_STALE - QuoteSendGateState.COPY_MISSING,
            expected.keys,
        )
        expected.forEach { (gate, text) ->
            val send = button(showing(gate), QuoteAction.SEND)
            assertFalse(send.isEnabled, gate.name)
            assertEquals(text, send.element.getAttribute("title"), gate.name)
        }
    }

    @Test
    fun `an open gate enables the send, which then leads and navigates to the compose page`() {
        whenever(send.send("q-1")).thenReturn(QuoteDispatchHandover("d-1", "mail/compose/d-1"))
        val view = showing(QuoteSendGateState.READY)

        val sendButton = button(view, QuoteAction.SEND)
        assertTrue(sendButton.isEnabled)
        assertFalse(sendButton.element.hasAttribute("title"))
        assertEquals("quote-action-SEND", view.hero.actions!!._find<Button>().last().id.get())
        sendButton._click()
        assertFalse(sendButton.isEnabled)
        assertEquals("Entwurf wird erstellt …", sendButton.text)
        assertTrue(sendButton.isDisableOnClick)
        assertNotEquals("mail/compose/d-1", currentPath())
        DirectPollTicks.awaitTicks()

        verify(send).send("q-1")
        assertEquals("mail/compose/d-1", currentPath())
    }

    @Test
    fun `a failed handover restores the send button and shows the reason`() {
        whenever(send.send("q-1")).thenThrow(QuoteServiceException("Keine Kunden-E-Mail"))
        val view = showing(QuoteSendGateState.READY)
        val sendButton = button(view, QuoteAction.SEND)
        val label = sendButton.text

        sendButton._click()
        DirectPollTicks.awaitTicks()

        assertEquals(label, sendButton.text)
        assertTrue(sendButton.isEnabled)
        assertTrue(notificationTexts().any { it.contains("Keine Kunden-E-Mail") })
    }

    @Test
    fun `a handover result arriving after the view was detached is dropped`() {
        whenever(send.send("q-1")).thenReturn(QuoteDispatchHandover("d-1", "mail/compose/d-1"))
        val view = showing(QuoteSendGateState.READY)
        val sendButton = button(view, QuoteAction.SEND)

        sendButton._click()
        view.removeFromParent()
        DirectPollTicks.awaitTicks()

        assertNotEquals("mail/compose/d-1", currentPath())
        assertFalse(sendButton.isEnabled)
    }

    @Test
    fun `a missing or outdated copy keeps the send enabled and leading without a tooltip`() {
        listOf(QuoteSendGateState.COPY_MISSING, QuoteSendGateState.COPY_STALE).forEach { gate ->
            val view = showing(gate)

            assertEquals(
                listOf("quote-action-EDIT", "quote-action-RETURN_TO_DRAFT", "quote-action-SEND"),
                view.hero.actions!!._find<Button>().mapNotNull { it.id.orElse(null) }.filterNot { it == "quote-export-xlsx" },
                gate.name,
            )
            val sendButton = button(view, QuoteAction.SEND)
            assertTrue(sendButton.isEnabled, gate.name)
            assertFalse(sendButton.element.hasAttribute("title"), gate.name)
        }
    }

    @Test
    fun `clicking the send without a ready copy requests the dispatch copy and does not send yet`() {
        whenever(requestDocument.request("q-1", QuoteDocumentMark.DISPATCH)).thenReturn("d-9")
        val view = showing(QuoteSendGateState.COPY_MISSING)

        button(view, QuoteAction.SEND)._click()
        DirectPollTicks.awaitTicks()

        verify(requestDocument).request("q-1", QuoteDocumentMark.DISPATCH)
        verify(detailQuery, org.mockito.kotlin.times(2)).find("q-1")
        verify(send, never()).send(any())
    }

    @Test
    fun `a failed copy request re-enables the send`() {
        whenever(requestDocument.request("q-1", QuoteDocumentMark.DISPATCH)).thenThrow(QuoteServiceException("Dienst nicht erreichbar"))
        val view = showing(QuoteSendGateState.COPY_STALE)
        val sendButton = button(view, QuoteAction.SEND)

        sendButton._click()

        assertTrue(sendButton.isEnabled)
        assertTrue(notificationTexts().any { it.contains("Dienst nicht erreichbar") })
        verify(send, never()).send(any())
    }

    @Test
    fun `once the requested copy is ready the next poll tick opens the send flow once`() {
        whenever(requestDocument.request("q-1", QuoteDocumentMark.DISPATCH)).thenReturn("d-9")
        whenever(send.send("q-1")).thenReturn(QuoteDispatchHandover("d-1", "mail/compose/d-1"))
        val view = showing(QuoteSendGateState.COPY_MISSING)
        whenever(gateQuery.gate("q-1")).thenReturn(QuoteSendGateState.COPY_IN_FLIGHT, QuoteSendGateState.READY)

        button(view, QuoteAction.SEND)._click()
        assertFalse(button(view, QuoteAction.SEND).isEnabled)
        verify(requestDocument).request("q-1", QuoteDocumentMark.DISPATCH)
        // The documents tab is not on screen, still the bar polls because the send waits for its copy.
        assertEquals(de.littletrotter.ui.view.invoice.DETAIL_POLL_INTERVAL_MS, UI.getCurrent().pollInterval)
        (view.hero.actions as QuoteDetailActions).applyPolledGate(QuoteSendGateState.READY)
        DirectPollTicks.awaitTicks()

        verify(send, org.mockito.kotlin.times(1)).send("q-1")
        assertEquals("mail/compose/d-1", currentPath())
    }

    @Test
    fun `the send leads as a primary button and keeps the mail styling`() {
        val sendButton = button(showing(QuoteSendGateState.READY), QuoteAction.SEND)

        assertTrue(sendButton.element.getAttribute("theme").orEmpty().contains("primary"))
        assertTrue(sendButton.hasClassName("ch-btn--mail"))
    }

    @Test
    fun `a copy that fails after being made is reported and the send is enabled again`() {
        whenever(requestDocument.request("q-1", QuoteDocumentMark.DISPATCH)).thenReturn("d-9")
        val view = showing(QuoteSendGateState.COPY_MISSING)
        whenever(gateQuery.gate("q-1")).thenReturn(QuoteSendGateState.COPY_IN_FLIGHT, QuoteSendGateState.COPY_STALE)
        val actions = view.hero.actions as QuoteDetailActions

        button(view, QuoteAction.SEND)._click()
        actions.render(quoteDetail(QuoteStatus.REVIEW), true, true, false)

        assertTrue(notificationTexts().any { it.contains("Das Versandexemplar ist veraltet.") })
        assertTrue(button(view, QuoteAction.SEND).isEnabled)
        verify(send, never()).send(any())
    }

    @Test
    fun `a copy in flight shows the seconds since its request on the disabled send`() {
        whenever(documentQuery.documents("q-1")).thenReturn(
            listOf(
                QuoteDocument("d-old", QuoteDocumentMark.DISPATCH, QuoteDocumentStatus.REQUESTED, NOW.minusSeconds(900)),
                QuoteDocument("d-1", QuoteDocumentMark.DISPATCH, QuoteDocumentStatus.REQUESTED, NOW.minusSeconds(12)),
                QuoteDocument("d-2", QuoteDocumentMark.REVIEW, QuoteDocumentStatus.REQUESTED, NOW.minusSeconds(500)),
            ),
        )
        val view = showing(QuoteSendGateState.COPY_IN_FLIGHT)

        val status = view.hero._get<Span> { id = "quote-dispatch-copy-status" }
        assertEquals("Versandexemplar wird erstellt … (seit 12 s)", visibleText(status).trim())
        assertEquals("status", status.element.getAttribute("role"))
        assertTrue(status.hasClassName("ch-fact--progress"))
        val sendButton = button(view, QuoteAction.SEND)
        assertFalse(sendButton.isEnabled)
        assertEquals("Versand wird vorbereitet … (12 s)", sendButton.text)
        assertTrue(view.hero.actions!!.children.toList().contains(sendButton))
    }

    @Test
    fun `an unreadable document list still shows the status line`() {
        whenever(documentQuery.documents("q-1")).thenThrow(QuoteServiceException("offline"))
        val view = showing(QuoteSendGateState.COPY_IN_FLIGHT)

        assertEquals("Versandexemplar wird erstellt … (seit 0 s)", visibleText(view.hero._get<Span> { id = "quote-dispatch-copy-status" }).trim())
    }

    @Test
    fun `no status line without a copy in flight`() {
        assertTrue(showing(QuoteSendGateState.COPY_STALE).hero.actions!!._find<Span> { id = "quote-dispatch-copy-status" }.isEmpty())
    }

    @Test
    fun `a gate that closed meanwhile is shown as its text and the page is redrawn`() {
        whenever(send.send("q-1")).thenThrow(QuoteNotSendableException(QuoteSendGateState.COPY_STALE))
        val view = showing(QuoteSendGateState.READY)

        button(view, QuoteAction.SEND)._click()
        DirectPollTicks.awaitTicks()

        assertTrue(notificationTexts().any { it.contains("Das Versandexemplar ist veraltet.") })
        verify(detailQuery, org.mockito.kotlin.times(2)).find("q-1")
    }

    @Test
    fun `another refusal of the send only shows its reason`() {
        whenever(send.send("q-1")).thenThrow(QuoteServiceException("Keine Kunden-E-Mail"))
        val view = showing(QuoteSendGateState.READY)

        button(view, QuoteAction.SEND)._click()
        DirectPollTicks.awaitTicks()

        assertTrue(notificationTexts().any { it.contains("Keine Kunden-E-Mail") })
        verify(detailQuery, org.mockito.kotlin.times(1)).find("q-1")
    }

    @Test
    fun `an unreadable gate is reported and keeps the send closed`() {
        whenever(gateQuery.gate("q-1")).thenThrow(QuoteServiceException("Angebotsdienst nicht erreichbar"))
        val view = showing(QuoteSendGateState.READY, stubGate = false, QuoteStatus.REVIEW, *ALL_QUOTE_PERMISSIONS)

        assertFalse(button(view, QuoteAction.SEND).isEnabled)
        assertTrue(notificationTexts().any { it.contains("Angebotsdienst nicht erreichbar") })
    }

    @Test
    fun `without a mail component there are no send actions`() {
        val ids = showing(null).hero.actions!!._find<Button>().mapNotNull { it.id.orElse(null) }

        assertTrue(ids.none { it == "quote-action-SEND" }, ids.toString())
    }

    @Test
    fun `without the send permission or outside review there are no send actions`() {
        val noRight = showing(QuoteSendGateState.READY, QuoteStatus.REVIEW, AppPermission.QUOTE_VIEW, AppPermission.QUOTE_EDIT)
        assertTrue(noRight.hero.actions!!._find<Button> { id = "quote-action-SEND" }.isEmpty())
        verify(gateQuery, never()).gate(any())

        val sent = showing(QuoteSendGateState.READY, QuoteStatus.SENT)
        assertTrue(sent.hero.actions!!._find<Button> { id = "quote-action-SEND" }.isEmpty())
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-02T08:00:00Z")
    }
}
