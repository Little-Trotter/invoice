package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.Html
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.dom.Element
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.shared.documents.FreeTextMarkup
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.ui.format.DocumentSizeFormatter
import de.littletrotter.ui.i18n.UiTranslations

/** The two renderings of the mail body: what an HTML client shows and what a text-only client sees. */
internal enum class MailPreviewMode { HTML, TEXT }

/** One attachment line of the envelope; [index] is its position in the draft, which is how the bytes are addressed. */
internal data class MailPreviewAttachment(val filename: String, val sizeBytes: Long, val index: Int)

/** The envelope lines above the body; recipient and attachments are optional because a template has neither. */
internal data class MailPreviewHeader(
    val subject: String,
    val to: String? = null,
    val attachments: List<MailPreviewAttachment> = emptyList(),
    /** Serves the bytes of the attachment at the given index; without it the lines stay plain text. */
    val attachmentDownload: ((Int) -> DownloadResponse)? = null,
)

/**
 * The mail as the customer receives it: a light card with the envelope lines (recipient, subject,
 * attachments) above a hairline and the body beneath. The HTML/text switch shows exactly the two
 * parts the mail component sends, so the preview never shows more than the customer gets.
 *
 * Placeholders that no value filled are marked in both renderings so a blocker is visible inside the
 * mail itself; the marking exists only in the preview and never reaches the sent text.
 */
internal class MailPreviewCard(title: String) : Div() {

    internal val modeToggle = RadioButtonGroup<MailPreviewMode>()
    internal val subjectLine = Span().apply { addClassName("mail-preview-subject") }
    internal val bodyFrame = Div().apply { addClassName("mail-preview-frame") }

    private val header = Div().apply { addClassName("mail-preview-header") }
    private val toolbar = Div().apply { addClassName("mail-preview__toolbar") }
    private val toolbarEnd = Div().apply { addClassName("lt-toolbar-end") }

    private var lastBody: String = ""

    init {
        addClassName("mail-preview")
        modeToggle.addClassName("lt-segmented")
        modeToggle.setItems(*MailPreviewMode.entries.toTypedArray())
        modeToggle.setItemLabelGenerator { UiTranslations.tr("mail.templates.preview.mode.${it.name}") }
        modeToggle.value = MailPreviewMode.HTML
        modeToggle.addValueChangeListener { renderBody() }
        toolbarEnd.add(modeToggle)
        toolbar.add(Span(title).apply { addClassName("mail-template-section-label") }, toolbarEnd)
        add(toolbar, Div(header, bodyFrame).apply { addClassName("mail-preview-card") })
    }

    /** Puts [component] next to the HTML/text switch, for switches only one host needs (such as a language toggle). */
    fun addToolbarItem(component: Component) {
        toolbarEnd.addComponentAsFirst(component)
    }

    /** Shows the mail: [header] above the hairline, [body] (markup with the values already filled in) below. */
    fun show(header: MailPreviewHeader, body: String) {
        lastBody = body
        subjectLine.text = header.subject
        this.header.removeAll()
        header.to?.let { this.header.add(row(UiTranslations.tr("mail.compose.preview.to"), Span(it))) }
        this.header.add(row(UiTranslations.tr("mail.templates.editor.subject"), subjectLine))
        if (header.attachments.isNotEmpty()) {
            val lines = header.attachments.map { attachmentLine(it, header.attachmentDownload) }
            val total = Div(UiTranslations.tr("mail.compose.preview.attachments.total", sizeOf(header.attachments.sumOf { it.sizeBytes })))
                .apply { addClassName("mail-preview-total") }
            this.header.add(row(UiTranslations.tr("mail.compose.preview.attachments"), *(lines + total).toTypedArray()))
        }
        renderBody()
    }

    /**
     * With a download source the line is a link that opens the staged bytes in a new tab, inline, so a
     * PDF shows in the browser's own viewer; without one (a template has no attachments) it is text.
     */
    private fun attachmentLine(attachment: MailPreviewAttachment, download: ((Int) -> DownloadResponse)?): Div {
        val text = "${attachment.filename} · ${sizeOf(attachment.sizeBytes)}"
        if (download == null) return Div(text)
        val label = UiTranslations.tr("mail.compose.attachment.open", attachment.filename)
        val handler = DownloadHandler.fromInputStream { download(attachment.index) }.inline()
        return Div(
            Anchor(handler, text).apply {
                setTarget("_blank")
                element.setAttribute("aria-label", label)
                element.setAttribute("title", label)
            },
        )
    }

    /** Replaces the whole card content with [message] when there is no mail to show. */
    fun showUnavailable(message: String) {
        lastBody = ""
        subjectLine.text = ""
        header.removeAll()
        bodyFrame.removeAll()
        bodyFrame.add(Span(message))
    }

    private fun row(label: String, vararg values: Component): Div =
        Div(
            Span(label).apply { addClassName("mail-preview-subject-label") },
            Div(*values).apply { addClassName("mail-preview-row__value") },
        ).apply { addClassName("mail-preview-row") }

    private fun sizeOf(bytes: Long): String = DocumentSizeFormatter.formatCompact(DocumentSize(bytes))

    private fun renderBody() {
        if (header.element.childCount == 0) return
        bodyFrame.removeAll()
        if (modeToggle.value == MailPreviewMode.TEXT) {
            bodyFrame.add(textBody(FreeTextMarkup.toPlain(lastBody)))
        } else {
            bodyFrame.add(Html(FreeTextMarkup.toHtml(lastBody).let(::markUnresolvedInHtml)))
        }
    }

    private fun textBody(plain: String): Div {
        val div = Div().apply { addClassName("lt-pre-wrap") }
        var cursor = 0
        TOKEN.findAll(plain).forEach { match ->
            div.element.appendChild(Element.createText(plain.substring(cursor, match.range.first)))
            div.element.appendChild(Element("mark").setAttribute("class", UNRESOLVED_CLASS).setText(match.value))
            cursor = match.range.last + 1
        }
        div.element.appendChild(Element.createText(plain.substring(cursor)))
        return div
    }

    private fun markUnresolvedInHtml(html: String): String =
        TOKEN.replace(html) { """<mark class="$UNRESOLVED_CLASS">${it.value}</mark>""" }
}

/** A `{{name}}` token the draft could not fill; marked in the preview so the blocker is visible in the mail itself. */
private val TOKEN = Regex("\\{\\{\\w+}}")
private const val UNRESOLVED_CLASS = "mail-preview-unresolved"
