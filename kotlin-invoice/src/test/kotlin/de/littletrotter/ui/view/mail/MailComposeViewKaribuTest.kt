package de.littletrotter.ui.view.mail

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10.clearNotifications
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.Html
import com.vaadin.flow.component.UI
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.RouteConfiguration
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailComposeAttachment
import de.littletrotter.application.mail.port.inbound.MailComposeDraft
import de.littletrotter.application.mail.port.inbound.MailComposeDraftQueryPort
import de.littletrotter.application.mail.port.inbound.MailDraftAttachmentContent
import de.littletrotter.application.mail.port.inbound.MailDraftAttachmentQueryPort
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailPlaceholderCatalogQueryPort
import de.littletrotter.application.mail.port.inbound.MailPlaceholderEntry
import de.littletrotter.application.mail.port.inbound.MailPlaceholderType
import de.littletrotter.application.mail.port.inbound.MailRenderPreview
import de.littletrotter.application.mail.port.inbound.MailRenderPreviewQueryPort
import de.littletrotter.application.mail.port.inbound.MailSendCommand
import de.littletrotter.application.mail.port.inbound.MailSendOutcome
import de.littletrotter.application.mail.port.inbound.MailSendUseCase
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.application.security.AccessDeniedException
import de.littletrotter.application.settings.featureGateWithDisabled
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.InvoiceListView
import de.littletrotter.ui.view.quote.QuoteDetailView
import de.littletrotter.ui.view.quote.QuoteListView
import java.time.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@DisplayName("MailComposeView — review and send a drafted mail")
class MailComposeViewKaribuTest {

    private val draftQuery: MailComposeDraftQueryPort = mock()
    private val placeholderQuery: MailPlaceholderCatalogQueryPort = mock()
    private val previewQuery: MailRenderPreviewQueryPort = mock()
    private val sendUseCase: MailSendUseCase = mock()
    private val attachmentQuery: MailDraftAttachmentQueryPort = mock()

    private val draft = MailComposeDraft(
        draftId = "d-1",
        referenceKey = "RE-2026-0001",
        kind = MailTemplateKind.INVOICE_DISPATCH,
        documentFamily = InvoiceFormat.GERMAN,
        recipientAddress = "kunde@example.com",
        recipientName = "Kunde GmbH",
        placeholderValues = mapOf("invoiceNumber" to "RE-2026-0001", "recipientName" to "Kunde GmbH"),
        attachments = listOf(MailComposeAttachment("RE-2026-0001.pdf", "application/pdf", 250_000, "abc", "mail-drafts/d-1/0-RE.pdf")),
        totalAttachmentBytes = 250_000,
        expiresAt = Instant.parse("2026-10-01T11:00:00Z"),
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(draftQuery.findDraft("d-1")).thenReturn(draft)
        whenever(placeholderQuery.placeholders(MailTemplateKind.INVOICE_DISPATCH)).thenReturn(
            listOf(
                MailPlaceholderEntry("invoiceNumber", MailPlaceholderType.TEXT, "RE-1"),
                MailPlaceholderEntry("dueDate", MailPlaceholderType.DATE, "01.11.2026"),
            ),
        )
        whenever(previewQuery.renderPreview(any())).thenReturn(
            MailRenderPreview("Rechnung {{invoiceNumber}}", "Guten Tag {{recipientName}}", MailTemplateLanguage.DE, 2),
        )
        whenever(sendUseCase.send(any())).thenReturn(MailSendOutcome(5L, MailJournalStatus.QUEUED))
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceDetailView::class.java)
        RouteConfiguration.forSessionScope().setAnnotatedRoute(InvoiceListView::class.java)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(mailEnabled: Boolean = true, vararg disabled: FeatureFlag) =
        MailComposeView(draftQuery, placeholderQuery, previewQuery, sendUseCase, attachmentQuery, MailAvailability(mailEnabled), featureGateWithDisabled(*disabled))

    private fun enter(view: MailComposeView, draftId: String = "d-1", query: String = "invoice=7"): BeforeEnterEvent {
        val event = mock<BeforeEnterEvent>()
        whenever(event.routeParameters).thenReturn(RouteParameters("draftId", draftId))
        whenever(event.location).thenReturn(Location("mail/compose/$draftId" + if (query.isEmpty()) "" else "?$query"))
        UI.getCurrent().add(view)
        view.beforeEnter(event)
        return event
    }

    private fun descendants(root: Component): List<Component> = root.children.toList().flatMap { listOf(it) + descendants(it) }

    private fun chips(view: MailComposeView): List<Button> =
        descendants(view).filterIsInstance<Button>().filter { it.classNames.contains("lt-freetext-chip") }

    private fun commandSent(): MailSendCommand = argumentCaptor<MailSendCommand>().also { verify(sendUseCase).send(it.capture()) }.firstValue

    private fun currentPath() = UI.getCurrent().internals.activeViewLocation.path

    @Test
    fun `the draft fills recipient, subject and body from the rendered template`() {
        val view = newView().also { enter(it) }

        assertEquals("kunde@example.com", view.recipientField.value)
        assertEquals("Rechnung {{invoiceNumber}}", view.subjectField.value)
        assertEquals("Guten Tag {{recipientName}}", view.bodyEditor.value)
        assertTrue(view.previewCard.element.textRecursively.contains("RE-2026-0001.pdf · 245 KB"))
        assertTrue(view.previewCard.element.textRecursively.contains("Gesamt 245 KB"))
    }

    @Test
    fun `each attachment is a link that opens in a new tab`() {
        val view = newView().also { enter(it) }

        val anchors = descendants(view.previewCard).filterIsInstance<Anchor>()

        assertEquals(1, anchors.size)
        assertEquals("_blank", anchors.single().target.orElse(null))
        assertFalse(anchors.single().element.hasAttribute("download"))
        assertTrue(anchors.single().text.contains("RE-2026-0001.pdf · 245 KB"))
        assertEquals("Anhang RE-2026-0001.pdf öffnen", anchors.single().element.getAttribute("title"))
    }

    @Test
    fun `an attachment is served with its stored type and a vanished one becomes a 404`() {
        val view = newView().also { enter(it) }
        whenever(attachmentQuery.findAttachment("d-1", 0)).thenReturn(MailDraftAttachmentContent("a.pdf", "application/pdf", byteArrayOf(1, 2)))
        whenever(attachmentQuery.findAttachment("d-1", 1)).thenThrow(MailDispatchException("down"))

        val hit = view.respondAttachment("d-1", 0)
        assertEquals("a.pdf", hit.fileName)
        assertEquals("application/pdf", hit.contentType)
        assertEquals(2L, hit.contentLength)
        assertFalse(hit.hasError())

        assertEquals(404, view.respondAttachment("d-1", 2).error)
        assertEquals(404, view.respondAttachment("d-1", 1).error)
    }

    @Test
    fun `the preview shows the text with the draft values filled in and follows edits`() {
        val view = newView().also { enter(it) }
        assertTrue(view.previewCard.element.textRecursively.contains("Rechnung RE-2026-0001"))
        assertTrue(htmlBody(view).contains("Guten Tag Kunde GmbH"))

        view.bodyEditor.value = "Neu: {{invoiceNumber}}"
        view.subjectField.value = "Betreff {{invoiceNumber}}"

        assertTrue(htmlBody(view).contains("Neu: RE-2026-0001"))
        assertTrue(view.previewCard.element.textRecursively.contains("Betreff RE-2026-0001"))
    }

    private fun htmlBody(view: MailComposeView): String =
        (view.previewCard.bodyFrame.children.findFirst().get() as Html).innerHtml

    private fun barText(view: MailComposeView): String = view.sendBar.label.text

    @Test
    fun `the envelope shows the recipient and follows it`() {
        val view = newView().also { enter(it) }
        assertTrue(view.previewCard.element.textRecursively.contains("kunde@example.com"))

        view.recipientField.value = "neu@example.com"

        assertTrue(view.previewCard.element.textRecursively.contains("neu@example.com"))
    }

    @Test
    fun `the html mode renders lists and paragraphs from the markup`() {
        val view = newView().also { enter(it) }

        view.bodyEditor.value = "Hallo\n\n- eins\n- zwei"

        val html = htmlBody(view)
        assertTrue(html.contains("lt-ft-line"), html)
        assertTrue(html.contains("""<span class="lt-ft-mark">•</span>eins"""), html)
    }

    @Test
    fun `bold text and an indented plain line reach the html preview without literal markers`() {
        val view = newView().also { enter(it) }

        view.bodyEditor.value = "**Hallo** Welt\n    Eingerückt"

        val html = htmlBody(view)
        assertTrue(html.contains("<b>Hallo</b> Welt"), html)
        assertTrue(html.contains("lt-ft-l1"), html)
        assertTrue(html.contains("Eingerückt"), html)
        assertFalse(html.contains("*"), html)
    }

    @Test
    fun `the text mode shows the plain text and the html mode returns`() {
        val view = newView().also { enter(it) }
        view.bodyEditor.value = "Hallo\n\n- eins"

        view.previewCard.modeToggle.value = MailPreviewMode.TEXT
        val text = view.previewCard.bodyFrame.element.textRecursively
        assertTrue(text.contains("Hallo") && text.contains("eins"))
        assertFalse(text.contains("<div"))
        view.previewCard.modeToggle.value = MailPreviewMode.HTML

        assertTrue(htmlBody(view).contains("lt-ft-mark"))
    }

    @Test
    fun `an unfilled placeholder is marked in both modes`() {
        val view = newView().also { enter(it) }
        view.bodyEditor.value = "Fällig am {{dueDate}} danke"

        assertTrue(htmlBody(view).contains("""<mark class="mail-preview-unresolved">{{dueDate}}</mark>"""))
        view.previewCard.modeToggle.value = MailPreviewMode.TEXT
        val marks = view.previewCard.bodyFrame.children.findFirst().get().element.children.toList().filter { !it.isTextNode && it.tag == "mark" }
        assertEquals(listOf("{{dueDate}}"), marks.map { it.text })
        assertTrue(view.previewCard.bodyFrame.element.textRecursively.contains("Fällig am {{dueDate}} danke"))
    }

    @Test
    fun `the subject follows typing eagerly`() {
        val view = newView().also { enter(it) }

        assertEquals(ValueChangeMode.EAGER, view.subjectField.valueChangeMode)
    }

    @Test
    fun `the send bar announces the template text with its attachments`() {
        val view = newView().also { enter(it) }

        assertEquals("Text der Vorlage · 1 Anhang · 245 KB", barText(view))
    }

    @Test
    fun `the send bar shows an edit, then blockers, and the send button stays clickable`() {
        val view = newView().also { enter(it) }

        view.bodyEditor.value = "Anders"
        assertEquals("Text geändert · 1 Anhang · 245 KB", barText(view))

        view.bodyEditor.value = "Fällig {{dueDate}} und {{other}}"
        assertEquals("2 Platzhalter ohne Wert: {{dueDate}}, {{other}}", barText(view))
        assertTrue(view.sendButton.isEnabled)
        assertTrue(view.previewTabDot.isVisible)

        view.bodyEditor.value = ""
        assertEquals("Nachricht fehlt", barText(view))
        view.subjectField.value = ""
        assertEquals("Betreff fehlt", barText(view))
        view.recipientField.value = ""
        assertEquals("Empfänger prüfen", barText(view))
        assertTrue(view.sendButton.isEnabled)
    }

    @Test
    fun `several attachments are counted in the bar`() {
        val second = MailComposeAttachment("Anlage.pdf", "application/pdf", 250_000, "def", "mail-drafts/d-1/1-A.pdf")
        whenever(draftQuery.findDraft("d-1")).thenReturn(draft.copy(attachments = draft.attachments + second, totalAttachmentBytes = 500_000))
        val view = newView().also { enter(it) }

        assertEquals("Text der Vorlage · 2 Anhänge · 489 KB", barText(view))
    }

    @Test
    fun `a draft without attachments shows no attachment figures`() {
        whenever(draftQuery.findDraft("d-1")).thenReturn(draft.copy(attachments = emptyList(), totalAttachmentBytes = 0))
        val view = newView().also { enter(it) }

        assertEquals("Text der Vorlage", barText(view))
        assertFalse(view.previewCard.element.textRecursively.contains("Gesamt"))
    }

    @Test
    fun `the window width decides the layout at the template editor's breakpoint`() {
        val view = newView()
        assertEquals(ComposeLayoutMode.TABS, view.modeForWidth(MailComposeView.TWO_COLUMN_MIN_WIDTH_PX - 1))
        assertEquals(ComposeLayoutMode.TWO_COLUMNS, view.modeForWidth(MailComposeView.TWO_COLUMN_MIN_WIDTH_PX))
    }

    @Test
    fun `the view is two columns by default and tabs when narrow, keeping the same instances and the editor text`() {
        val view = newView().also { enter(it) }
        assertEquals(ComposeLayoutMode.TWO_COLUMNS, view.layoutMode)
        assertTrue(descendants(view).any { it === view.previewCard })
        assertFalse(descendants(view).any { it === view.tabs })
        view.bodyEditor.value = "Mein Text"

        view.applyLayoutMode(ComposeLayoutMode.TABS)
        assertEquals(ComposeLayoutMode.TABS, view.layoutMode)
        assertTrue(descendants(view).any { it === view.tabs })
        assertTrue(view.bodyEditor.parent.isPresent)
        assertTrue(view.previewCard.parent.get().classNames.contains("mail-compose__preview--tabbed"))
        assertEquals(2, view.tabs.tabCount)

        view.applyLayoutMode(ComposeLayoutMode.TWO_COLUMNS)
        assertFalse(descendants(view).any { it === view.tabs })
        assertTrue(descendants(view).any { it === view.previewCard })
        assertTrue(descendants(view).any { it === view.bodyEditor })
        assertEquals("Mein Text", view.bodyEditor.value)
        assertFalse(view.previewCard.parent.get().classNames.contains("mail-compose__preview--tabbed"))
    }

    @Test
    fun `the preview tab dot appears only while a placeholder has no value`() {
        val view = newView().also { enter(it) }
        assertFalse(view.previewTabDot.isVisible)

        view.bodyEditor.value = "{{dueDate}}"
        assertTrue(view.previewTabDot.isVisible)

        view.bodyEditor.value = "ok"
        assertFalse(view.previewTabDot.isVisible)
    }

    @Test
    fun `attaching the view wires the width switch and detaching releases it`() {
        val view = newView().also { enter(it) }

        UI.getCurrent().internals.stateTree.runExecutionsBeforeClientResponse()
        UI.getCurrent().remove(view)
        UI.getCurrent().add(view)

        assertEquals(ComposeLayoutMode.TWO_COLUMNS, view.layoutMode)
    }

    @Test
    fun `chips exist only for placeholders the draft has a value for and insert their token`() {
        val view = newView().also { enter(it) }

        val chip = chips(view).single()
        assertTrue(chip.element.textRecursively.contains("Rechnungsnummer"))
        UI.getCurrent().internals.stateTree.runExecutionsBeforeClientResponse()
        UI.getCurrent().internals.dumpPendingJavaScriptInvocations()
        chip._click()

        UI.getCurrent().internals.stateTree.runExecutionsBeforeClientResponse()
        val invocations = UI.getCurrent().internals.dumpPendingJavaScriptInvocations()
        assertTrue(
            invocations.any { it.invocation.expression.contains("insertText") && it.invocation.parameters.contains("{{invoiceNumber}}") },
            "the chip must ask the editor to insert the token at the caret",
        )
    }

    @Test
    fun `an unavailable catalogue only removes the chips`() {
        whenever(placeholderQuery.placeholders(any())).thenThrow(MailDispatchException("down"))
        val view = newView().also { enter(it) }

        assertTrue(chips(view).isEmpty())
        assertNotNull(view.bodyEditor)
    }

    @Test
    fun `send hands the filled text, the draft and its attachments to the use case and returns to the invoice`() {
        val view = newView().also { enter(it) }
        view.bodyEditor.value = "Hallo {{recipientName}}"

        view.sendButton._click()

        val command = commandSent()
        assertEquals("kunde@example.com", command.recipientAddress)
        assertEquals("Rechnung RE-2026-0001", command.subject)
        assertEquals("Hallo Kunde GmbH", command.body)
        assertEquals("d-1", command.draftId)
        assertEquals("RE-2026-0001", command.referenceKey)
        assertTrue(command.bodyEditedByUser)
        assertEquals("mail-drafts/d-1/0-RE.pdf", command.attachments.single().s3Key)
        assertEquals("invoice/7", currentPath())
        MockVaadin.clientRoundtrip()
        assertFalse(getNotifications().isEmpty(), "the user is told the mail was handed over")
    }

    @Test
    fun `an untouched body is not flagged as edited`() {
        val view = newView().also { enter(it) }

        view.sendButton._click()

        assertFalse(commandSent().bodyEditedByUser)
    }

    @Test
    fun `send without the invoice id falls back to the invoice list`() {
        val view = newView().also { enter(it, query = "") }

        view.sendButton._click()

        assertEquals("", currentPath())
    }

    @Test
    fun `a failed send shows the error, stays on the view and allows a retry`() {
        whenever(sendUseCase.send(any())).thenThrow(MailDispatchException("Component unreachable"))
        val view = newView().also { enter(it) }
        clearNotifications()

        view.sendButton._click()

        MockVaadin.clientRoundtrip()
        assertFalse(getNotifications().isEmpty())
        assertTrue(view.sendButton.isEnabled)
        assertFalse(currentPath().startsWith("invoice"))
    }

    @Test
    fun `a refused permission is reported and also allows a retry`() {
        whenever(sendUseCase.send(any())).thenThrow(AccessDeniedException("Keine Berechtigung"))
        val view = newView().also { enter(it) }
        clearNotifications()

        view.sendButton._click()

        MockVaadin.clientRoundtrip()
        assertFalse(getNotifications().isEmpty())
        assertTrue(view.sendButton.isEnabled)
    }

    @Test
    fun `a missing recipient, subject or body blocks the send`() {
        val view = newView().also { enter(it) }

        view.recipientField.value = ""
        view.sendButton._click()
        view.recipientField.value = "kunde@example.com"
        view.subjectField.value = " "
        view.sendButton._click()
        view.subjectField.value = "Betreff"
        view.bodyEditor.value = ""
        view.sendButton._click()

        verify(sendUseCase, never()).send(any())
    }

    @Test
    fun `a token without a value blocks the send`() {
        val view = newView().also { enter(it) }
        view.bodyEditor.value = "Fällig am {{dueDate}}"

        view.sendButton._click()

        verify(sendUseCase, never()).send(any())
    }

    @Test
    fun `discard returns to the invoice without sending`() {
        val view = newView().also { enter(it) }

        view.discardButton._click()

        verify(sendUseCase, never()).send(any())
        assertEquals("invoice/7", currentPath())
    }

    @Test
    fun `the breadcrumb links the invoice number when the invoice id is known`() {
        val view = newView().also { enter(it) }

        assertTrue(descendants(view.breadcrumb).filterIsInstance<RouterLink>().any { it.text == "RE-2026-0001" })
    }

    @Test
    fun `an offer draft names the offer in the breadcrumb and goes back to it`() {
        RouteConfiguration.forSessionScope().setAnnotatedRoute(QuoteDetailView::class.java)
        RouteConfiguration.forSessionScope().setAnnotatedRoute(QuoteListView::class.java)
        val view = newView().also { enter(it, query = "quote=AN-2026-0009") }

        val links = descendants(view.breadcrumb).filterIsInstance<RouterLink>()
        assertTrue(links.any { it.text == "RE-2026-0001" })
        assertTrue(links.any { it.text == "Angebote" || it.text == "Quotes" })
        view.discardButton._click()

        assertEquals("quotes/AN-2026-0009", currentPath())
    }

    @Test
    fun `the breadcrumb shows the number as plain text without an invoice id`() {
        val view = newView().also { enter(it, query = "") }

        assertTrue(descendants(view.breadcrumb).filterIsInstance<RouterLink>().isEmpty())
        assertTrue(view.breadcrumb.element.textRecursively.contains("RE-2026-0001"))
    }

    @Test
    fun `an unknown or expired draft explains itself and offers the way back`() {
        whenever(draftQuery.findDraft("gone")).thenReturn(null)
        val view = newView()
        enter(view, draftId = "gone")

        val problem = descendants(view).filterIsInstance<Div>().single { it.id.orElse("") == "mail-compose-problem" }
        assertTrue(problem.element.textRecursively.isNotBlank())
        descendants(view).filterIsInstance<Button>().single()._click()
        assertEquals("", currentPath())
    }

    @Test
    fun `a component failure while loading the draft is shown, not thrown`() {
        whenever(draftQuery.findDraft("d-1")).thenThrow(MailDispatchException("Component unreachable"))
        val view = newView().also { enter(it) }

        assertEquals("Component unreachable", descendants(view).filterIsInstance<Div>().single { it.id.orElse("") == "mail-compose-problem" }.element.textRecursively)
    }

    @Test
    fun `a component failure while rendering the preview is shown, not thrown`() {
        whenever(previewQuery.renderPreview(any())).thenThrow(MailDispatchException("Preview unavailable"))
        val view = newView().also { enter(it) }

        assertEquals("Preview unavailable", descendants(view).filterIsInstance<Div>().single { it.id.orElse("") == "mail-compose-problem" }.element.textRecursively)
    }

    @Test
    fun `a failure without a message falls back to the generic load text`() {
        whenever(draftQuery.findDraft("d-1")).thenThrow(MailDispatchException(""))
        val view = newView().also { enter(it) }

        assertTrue(descendants(view).filterIsInstance<Div>().single { it.id.orElse("") == "mail-compose-problem" }.element.textRecursively.isNotBlank())
    }

    @Test
    fun `without the mail component the view forwards to the invoice list`() {
        val view = newView(mailEnabled = false)

        val event = enter(view)

        verify(event).forwardTo(InvoiceListView::class.java)
        verify(draftQuery, never()).findDraft(any())
    }

    @Test
    fun `an admin who switched the mail offer off also gets forwarded`() {
        val view = newView(true, FeatureFlag.EMAIL_SEND)

        val event = enter(view)

        verify(event).forwardTo(InvoiceListView::class.java)
    }

    @Test
    fun `an invoice draft keeps the invoice heading`() {
        val view = newView().also { enter(it) }

        assertEquals("Rechnung per E-Mail senden", descendants(view).filterIsInstance<H2>().single().text)
    }

    @Test
    fun `an offer draft is headed as an offer`() {
        RouteConfiguration.forSessionScope().setAnnotatedRoute(QuoteDetailView::class.java)
        RouteConfiguration.forSessionScope().setAnnotatedRoute(QuoteListView::class.java)
        val view = newView().also { enter(it, query = "quote=AN-2026-0009") }

        assertEquals("Angebot per E-Mail senden", descendants(view).filterIsInstance<H2>().single().text)
    }

    @Test
    fun `a placeholder without a label keeps its raw name on the chip`() {
        whenever(draftQuery.findDraft("d-1")).thenReturn(draft.copy(placeholderValues = mapOf("unlabelled" to "y")))
        whenever(placeholderQuery.placeholders(MailTemplateKind.INVOICE_DISPATCH))
            .thenReturn(listOf(MailPlaceholderEntry("unlabelled", MailPlaceholderType.TEXT, "y")))
        val view = newView().also { enter(it) }

        assertTrue(chips(view).single().element.textRecursively.contains("unlabelled"))
    }

    @Test
    fun `the breadcrumb has a segment per level so the separator style applies`() {
        val view = newView().also { enter(it) }

        assertEquals(3, descendants(view.breadcrumb).count { it.classNames.contains("breadcrumb-segment") })
    }
}
