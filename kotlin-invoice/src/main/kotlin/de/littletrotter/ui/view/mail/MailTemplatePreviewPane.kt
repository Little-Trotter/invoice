package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.ui.i18n.UiTranslations

/** Subject and body of one template as the preview shows them, before the sample values go in. */
internal data class MailTemplateContent(val subject: String, val body: String)

/**
 * The live preview beside the editor: the mail inside its fixed light frame, with the catalogue's
 * sample values filled in. The HTML/text switch shows exactly what each kind of mail client gets
 * (the text part is the same plain conversion the component sends), and the language switch shows the
 * sister template so the two wordings can be compared while editing.
 *
 * The own language shows the text being edited; the sister language and a history version are
 * fetched through [contentFor], so the pane never decides where text comes from.
 */
internal class MailTemplatePreviewPane(
    private val ownLanguage: MailTemplateLanguage,
    private val samples: () -> Map<String, String>,
    private val contentFor: (MailTemplateLanguage) -> MailTemplateContent?,
) : Div() {

    private val card = MailPreviewCard(UiTranslations.tr("mail.templates.preview.title"))
    internal val modeToggle get() = card.modeToggle
    internal val languageToggle = RadioButtonGroup<MailTemplateLanguage>()
    internal val subjectLine get() = card.subjectLine
    internal val bodyFrame get() = card.bodyFrame

    private var pinned: MailTemplateContent? = null

    init {
        languageToggle.addClassName("lt-segmented")
        languageToggle.setItems(*MailTemplateLanguage.entries.toTypedArray())
        languageToggle.setItemLabelGenerator { it.name }
        languageToggle.value = ownLanguage
        languageToggle.addValueChangeListener { pinned = null; refresh() }
        card.addToolbarItem(languageToggle)
        add(card)
    }

    /** Shows [content] of an older version until the next edit or language switch. */
    fun pin(content: MailTemplateContent) {
        languageToggle.value = ownLanguage
        pinned = content
        refresh()
    }

    /** Drops a pinned version so the pane follows the editor again. */
    fun unpin() {
        pinned = null
    }

    fun refresh() {
        val content = pinned ?: contentFor(languageToggle.value ?: ownLanguage)
        if (content == null) {
            card.showUnavailable(UiTranslations.tr("mail.templates.preview.unavailable"))
            return
        }
        val values = samples()
        card.show(
            MailPreviewHeader(MailTemplateRules.fill(content.subject, values)),
            MailTemplateRules.fill(content.body, values),
        )
    }
}
