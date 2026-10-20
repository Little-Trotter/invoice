package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Nav
import com.vaadin.flow.component.html.OrderedList
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.server.streams.DownloadResponse
import com.vaadin.flow.shared.Registration
import com.vaadin.flow.component.textfield.EmailField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.mail.port.inbound.MailAvailabilityQueryPort
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailComposeDraft
import de.littletrotter.application.mail.port.inbound.MailComposeDraftQueryPort
import de.littletrotter.application.mail.port.inbound.MailDraftAttachmentQueryPort
import de.littletrotter.application.mail.port.inbound.MailPlaceholderCatalogQueryPort
import de.littletrotter.application.mail.port.inbound.MailRenderPreview
import de.littletrotter.application.mail.port.inbound.MailRenderPreviewQueryPort
import de.littletrotter.application.mail.port.inbound.MailRenderPreviewRequest
import de.littletrotter.application.mail.port.inbound.MailSendAttachment
import de.littletrotter.application.mail.port.inbound.MailSendCommand
import de.littletrotter.application.mail.port.inbound.MailSendUseCase
import de.littletrotter.application.security.AccessDeniedException
import de.littletrotter.application.settings.FeatureAvailability
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.application.settings.port.inbound.FeatureSettingsQueryPort
import de.littletrotter.shared.documents.FreeTextMarkup
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import de.littletrotter.ui.view.invoice.InvoiceListView
import de.littletrotter.ui.view.quote.QuoteDetailView
import de.littletrotter.ui.view.quote.QuoteListView
import de.littletrotter.ui.view.quote.QuoteRoutes
import de.littletrotter.ui.components.freetext.ChipCandidate
import de.littletrotter.ui.components.freetext.FreeTextChips
import de.littletrotter.ui.components.freetext.HtmlFreeTextEditor
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.io.ByteArrayInputStream

/**
 * Review and send one mail the invoice detail handed over as a draft.
 *
 * The draft carries recipient, attachments and the values for the template's placeholders; subject
 * and body start as the rendered template and stay editable. The user may type or insert
 * `{{name}}` tokens; they are filled from the draft's values before preview and send, and a token
 * that cannot be filled blocks the send, so a raw token never reaches a customer.
 *
 * A draft the component no longer knows (expired, already sent) shows a short explanation instead
 * of an error page: the user usually returns minutes later and needs to hear "start again from the
 * invoice". The view is only reachable while mail is offered; otherwise it forwards to the list,
 * the same hidden-not-disabled rule as the drawer entry.
 */
@Route(value = "mail/compose/:draftId", layout = MainLayout::class)
@PageTitle("E-Mail senden")
class MailComposeView
@Inject
constructor(
    private val draftQuery: MailComposeDraftQueryPort,
    private val placeholderQuery: MailPlaceholderCatalogQueryPort,
    private val previewQuery: MailRenderPreviewQueryPort,
    private val sendUseCase: MailSendUseCase,
    private val attachmentQuery: MailDraftAttachmentQueryPort,
    private val mailAvailability: MailAvailabilityQueryPort,
    private val featureSettingsGate: FeatureSettingsQueryPort,
) : VerticalLayout(), BeforeEnterObserver {

    private val log = Logger.getLogger(javaClass)

    internal val recipientField = EmailField(tr("mail.compose.recipient"))
    internal val subjectField = TextField(tr("mail.compose.subject"))
    internal val bodyEditor = HtmlFreeTextEditor(tr("mail.compose.body"), FreeTextMarkup.MAX_LENGTH)
    internal val previewCard = MailPreviewCard(tr("mail.compose.preview"))
    internal val sendButton = AppButtons.primary(tr("mail.compose.send")) { send() }
    internal val discardButton = AppButtons.tertiary(tr("mail.compose.discard")) { navigateBack() }
    internal val breadcrumb = Nav().apply {
        addClassName("nav-breadcrumb")
        element.setAttribute("aria-label", tr("nav.breadcrumb.label"))
    }

    private var draft: MailComposeDraft? = null
    private var renderedBody: String = ""
    private var invoiceId: Long? = null
    private var quoteId: String? = null
    private var chips: FreeTextChips? = null
    private var resizeRegistration: Registration? = null

    internal val sendBar = MailComposeSendBar(discardButton, sendButton)

    /** The form column; the same instance sits in the two-column layout and in the edit tab. */
    private val formColumn = VerticalLayout().apply {
        isPadding = false
        isSpacing = true
        addClassName("mail-compose__form")
    }

    /** The mail card column; the same instance sits beside the form or inside the preview tab. */
    private val previewColumn = Div(previewCard).apply { addClassName("mail-compose__preview") }

    private val twoColumns = FormLayout().apply {
        setResponsiveSteps(FormLayout.ResponsiveStep("0", 1), FormLayout.ResponsiveStep(TWO_COLUMN_MIN_WIDTH, 2))
        addClassName("lt-form-column-gap")
    }
    private val editHost = Div()
    private val previewHost = Div()

    /** Dot on the preview tab while a placeholder has no value. */
    internal val previewTabDot = Span().apply {
        addClassName("mail-compose__tab-dot")
        isVisible = false
    }
    internal val tabs = TabSheet().apply {
        add(Tab(tr("mail.compose.tab.edit")), editHost)
        add(Tab(Span(tr("mail.compose.tab.preview")), previewTabDot), previewHost)
    }
    private val content = Div().apply { setWidthFull() }

    /** The layout the view currently shows; switchable without a browser through [applyLayoutMode]. */
    internal var layoutMode: ComposeLayoutMode = ComposeLayoutMode.TWO_COLUMNS
        private set

    init {
        isPadding = true
        isSpacing = true
        applyLayoutMode(ComposeLayoutMode.TWO_COLUMNS)
    }

    override fun onAttach(attachEvent: AttachEvent) {
        val page = attachEvent.ui.page
        resizeRegistration = page.addBrowserWindowResizeListener { applyLayoutMode(modeForWidth(it.width)) }
        // The browser reports its window size while the UI boots; until it arrives the details carry -1,
        // so the width is fetched once instead of sizing the view from the placeholder.
        val details = page.extendedClientDetails
        if (details.windowInnerWidth > 0) applyWindowWidth(details.windowInnerWidth) else details.refresh { applyWindowWidth(it.windowInnerWidth) }
    }

    private fun applyWindowWidth(widthPx: Int) {
        if (widthPx > 0) applyLayoutMode(modeForWidth(widthPx))
    }

    override fun onDetach(detachEvent: DetachEvent) {
        resizeRegistration?.remove()
        resizeRegistration = null
    }

    /**
     * Shows form and mail card side by side or as two tabs. Only re-parents the existing columns, so the
     * editor keeps its content and caret and nothing is rebuilt.
     */
    internal fun applyLayoutMode(mode: ComposeLayoutMode) {
        layoutMode = mode
        content.removeAll()
        if (mode == ComposeLayoutMode.TWO_COLUMNS) {
            twoColumns.removeAll()
            twoColumns.add(formColumn, previewColumn)
            previewColumn.removeClassName(TABBED_CLASS)
            content.add(twoColumns)
        } else {
            editHost.removeAll()
            previewHost.removeAll()
            editHost.add(formColumn)
            previewHost.add(previewColumn)
            previewColumn.addClassName(TABBED_CLASS)
            content.add(tabs)
        }
    }

    /** Two columns from the template editor's two-column breakpoint upwards, tabs below it. */
    internal fun modeForWidth(widthPx: Int): ComposeLayoutMode =
        if (widthPx >= TWO_COLUMN_MIN_WIDTH_PX) ComposeLayoutMode.TWO_COLUMNS else ComposeLayoutMode.TABS

    override fun beforeEnter(event: BeforeEnterEvent) {
        val offered = FeatureAvailability.mailAvailable(mailAvailability.enabled, featureSettingsGate.isEnabled(FeatureFlag.EMAIL_SEND))
        if (!offered) {
            event.forwardTo(InvoiceListView::class.java)
            return
        }
        invoiceId = event.location.queryParameters.parameters[INVOICE_PARAMETER]?.firstOrNull()?.toLongOrNull()
        quoteId = event.location.queryParameters.parameters[QUOTE_PARAMETER]?.firstOrNull()?.takeIf { it.isNotBlank() }
        removeAll()
        val draftId = event.routeParameters.get(DRAFT_ID_PARAMETER).orElse("")
        try {
            val loaded = draftQuery.findDraft(draftId)
            if (loaded == null) {
                showProblem(tr("mail.compose.draftGone"))
            } else {
                val preview = previewQuery.renderPreview(
                    MailRenderPreviewRequest(loaded.kind, loaded.documentFamily, loaded.placeholderValues),
                )
                render(loaded, preview)
            }
        } catch (e: MailDispatchException) {
            log.warn("Mail draft could not be opened: ${e.message}")
            showProblem(messageOf(e, "mail.compose.loadFailed"))
        }
    }

    /**
     * The bytes behind one attachment link. A draft or file that has vanished since the page was
     * rendered becomes a 404 with a readable text rather than a broken tab.
     */
    internal fun respondAttachment(draftId: String, index: Int): DownloadResponse {
        val attachment = try {
            attachmentQuery.findAttachment(draftId, index)
        } catch (e: MailDispatchException) {
            log.warn("Attachment $index of the mail draft could not be fetched: ${e.message}")
            null
        }
        return if (attachment == null) {
            DownloadResponse.error(HTTP_NOT_FOUND, tr("mail.compose.attachment.missing"))
        } else {
            DownloadResponse(ByteArrayInputStream(attachment.bytes), attachment.filename, attachment.contentType, attachment.bytes.size.toLong())
        }
    }

    /** The component's own text is what the user can act on; an empty one falls back to [fallbackKey]. */
    private fun messageOf(e: Exception, fallbackKey: String): String =
        e.message?.takeIf { it.isNotBlank() } ?: tr(fallbackKey)

    private fun label(key: String): Span = Span(tr(key)).apply { addClassName("mail-compose-label") }

    private fun render(loaded: MailComposeDraft, preview: MailRenderPreview) {
        draft = loaded
        renderedBody = preview.body
        recipientField.value = loaded.recipientAddress
        subjectField.value = preview.subject
        bodyEditor.value = preview.body
        recipientField.setWidthFull()
        subjectField.setWidthFull()
        bodyEditor.setWidthFull()
        bodyEditor.addValueChangeListener { refreshPreview() }
        subjectField.valueChangeMode = ValueChangeMode.EAGER
        subjectField.addValueChangeListener { refreshPreview() }
        recipientField.addValueChangeListener { refreshPreview() }
        chips = FreeTextChips({ chipCandidates(loaded) }) { candidate -> bodyEditor.insertAtCursor(candidate.insertText) }
            .also { it.refresh() }
        buildBreadcrumb(loaded)
        formColumn.removeAll()
        formColumn.add(
            recipientField,
            subjectField,
            Div(label("mail.compose.placeholders"), chips).apply { addClassName("mail-compose__chips") },
            bodyEditor,
        )
        add(
            breadcrumb,
            PageShell(tr(if (quoteId != null) "mail.compose.title.quote" else "mail.compose.title")).narrow().content(content),
            sendBar,
        )
        refreshPreview()
    }

    /**
     * One chip per placeholder the component knows **and** the draft has a value for: a token with
     * no value could not be filled and would only block the send.
     */
    private fun chipCandidates(loaded: MailComposeDraft): List<ChipCandidate> =
        try {
            placeholderQuery.placeholders(loaded.kind)
                .filter { it.key in loaded.placeholderValues }
                .map { entry ->
                    ChipCandidate(
                        label = UiTranslations.trOrNull(null, "mail.templates.placeholder.${entry.key}") ?: entry.key,
                        fullName = entry.key,
                        insertText = MailPlaceholderText.token(entry.key),
                        group = entry.type.name,
                    )
                }
        } catch (e: MailDispatchException) {
            // The chips are a convenience: without them the user can still type a token or send as is.
            log.warn("Mail placeholder catalogue unavailable: ${e.message}")
            emptyList()
        }

    /**
     * `Rechnungen › <Nr> › E-Mail`, or `Angebote › <Nr> › E-Mail` for an offer draft; the number links
     * back only when the id of the originating document travelled along.
     */
    private fun buildBreadcrumb(loaded: MailComposeDraft) {
        breadcrumb.removeAll()
        val list = OrderedList().apply { addClassName("breadcrumb-list") }
        val linkedQuote = quoteId
        if (linkedQuote != null) {
            list.add(segment(RouterLink(tr("nav.section.quoting"), QuoteListView::class.java)))
        } else {
            list.add(segment(Anchor("", tr("nav.invoices"))))
        }
        val number = originLink(loaded.referenceKey)
        list.add(segment(number))
        list.add(segment(Span(tr("mail.compose.breadcrumb")).apply { element.setAttribute("aria-current", "page") }))
        breadcrumb.add(list)
    }

    private fun originLink(label: String): Component {
        val linkedQuote = quoteId
        val invoice = invoiceId
        return when {
            linkedQuote != null -> RouterLink(label, QuoteDetailView::class.java, RouteParameters(QuoteRoutes.NUMBER_PARAM, linkedQuote))
            invoice != null -> RouterLink(label, InvoiceDetailView::class.java, RouteParameters("id", invoice.toString()))
            else -> Span(label)
        }
    }

    private fun segment(content: Component): Div = Div(content).apply { addClassName("breadcrumb-segment") }

    private fun unresolvedNames(): List<String> {
        val values = draft?.placeholderValues ?: return emptyList()
        return (MailPlaceholderText.unresolved(subjectField.value.trim(), values) + MailPlaceholderText.unresolved(bodyEditor.value, values)).distinct()
    }

    private fun refreshPreview() {
        val current = draft ?: return
        val values = current.placeholderValues
        val unresolved = unresolvedNames()
        previewCard.show(
            MailPreviewHeader(
                subject = MailPlaceholderText.resolve(subjectField.value, values),
                to = recipientField.value.trim().ifBlank { null },
                attachments = current.attachments.mapIndexed { index, file -> MailPreviewAttachment(file.filename, file.sizeBytes, index) },
                attachmentDownload = { index -> respondAttachment(current.draftId, index) },
            ),
            MailPlaceholderText.resolveBody(bodyEditor.value, values),
        )
        previewTabDot.isVisible = unresolved.isNotEmpty()
        val state = MailComposeState.of(
            MailComposeFields(
                recipientValid = recipientField.value.isNotBlank() && !recipientField.isInvalid,
                subject = subjectField.value,
                body = bodyEditor.value,
                renderedBody = renderedBody,
                unresolved = unresolved,
            ),
        )
        sendBar.show(state, unresolved, current.attachments.size, current.totalAttachmentBytes)
    }

    private fun showProblem(message: String) {
        removeAll()
        add(
            Div(message).apply {
                setId("mail-compose-problem")
                addClassName("mail-compose-problem")
            },
            AppButtons.secondary(tr("mail.compose.backToInvoices")) { UI.getCurrent()?.navigate(InvoiceListView::class.java) },
        )
    }

    /** Flags the first unusable input on its own field; true when the mail must not be sent. */
    private fun rejectInput(recipient: String, subject: String, body: String, unresolved: List<String>): Boolean {
        when {
            recipient.isBlank() || recipientField.isInvalid ->
                UiNotifications.errorOn(recipientField, tr("mail.compose.error.recipient"))
            subject.isBlank() -> UiNotifications.errorOn(subjectField, tr("mail.compose.error.subject"))
            body.isBlank() -> UiNotifications.errorOn(bodyEditor, tr("mail.compose.error.body"))
            unresolved.isNotEmpty() ->
                UiNotifications.errorOn(bodyEditor, tr("mail.compose.error.unresolved", unresolved.distinct().joinToString(", ")))
            else -> return false
        }
        return true
    }

    private fun send() {
        val current = draft ?: return
        val values = current.placeholderValues
        val recipient = recipientField.value.trim()
        val subject = subjectField.value.trim()
        val body = bodyEditor.value
        val unresolved = MailPlaceholderText.unresolved(subject, values) + MailPlaceholderText.unresolved(body, values)
        if (rejectInput(recipient, subject, body, unresolved)) return
        val command = MailSendCommand(
            kind = current.kind,
            documentFamily = current.documentFamily,
            recipientAddress = recipient,
            referenceKey = current.referenceKey,
            subject = MailPlaceholderText.resolve(subject, values),
            body = MailPlaceholderText.resolveBody(body, values),
            placeholderValues = values,
            bodyEditedByUser = body != renderedBody,
            attachments = current.attachments.map { MailSendAttachment(it.s3Key, it.filename, it.contentType, it.sha256) },
            draftId = current.draftId,
        )
        if (handOver(command)) {
            UiNotifications.success(tr("mail.compose.sent", current.recipientAddress))
            navigateBack()
        }
    }

    /**
     * Queues [command]; `false` after showing why it could not be queued. The button is locked
     * while the call runs so a second click cannot send the customer the mail twice, and unlocked
     * again on every failure so the user can retry.
     */
    private fun handOver(command: MailSendCommand): Boolean {
        sendButton.isEnabled = false
        return try {
            sendUseCase.send(command)
            true
        } catch (e: MailDispatchException) {
            sendButton.isEnabled = true
            UiNotifications.error(messageOf(e, "mail.compose.error.send"))
            false
        } catch (e: AccessDeniedException) {
            sendButton.isEnabled = true
            UiNotifications.error(messageOf(e, "mail.compose.error.send"))
            false
        }
    }

    private fun navigateBack() {
        val ui = UI.getCurrent() ?: return
        val linkedQuote = quoteId
        val id = invoiceId
        when {
            linkedQuote != null -> ui.navigate(QuoteDetailView::class.java, RouteParameters(QuoteRoutes.NUMBER_PARAM, linkedQuote))
            id != null -> ui.navigate(InvoiceDetailView::class.java, RouteParameters("id", id.toString()))
            else -> ui.navigate(InvoiceListView::class.java)
        }
    }

    private fun tr(key: String, vararg params: Any): String = UiTranslations.tr(key, *params)

    companion object {
        const val DRAFT_ID_PARAMETER = "draftId"
        const val HTTP_NOT_FOUND = 404

        /** Query parameter naming the invoice the draft came from, so "back" lands on it. */
        const val INVOICE_PARAMETER = "invoice"

        /** Query parameter naming the offer the draft came from, so "back" lands on it. */
        const val QUOTE_PARAMETER = "quote"

        /** Window width from which form and mail card sit side by side (64em at 16px); below it they become tabs. */
        const val TWO_COLUMN_MIN_WIDTH_PX = 1024

        private const val TWO_COLUMN_MIN_WIDTH = "64em"
        private const val TABBED_CLASS = "mail-compose__preview--tabbed"
    }
}

/** How the compose view arranges form and mail card. */
internal enum class ComposeLayoutMode { TWO_COLUMNS, TABS }
