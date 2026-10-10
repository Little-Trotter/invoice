package de.littletrotter.ui.view.masterdata

import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextArea
import de.littletrotter.application.dto.LanguageDto
import de.littletrotter.application.dto.SenderConsumerNoteDto
import de.littletrotter.application.invoice.ConsumerNoteResolver
import de.littletrotter.domain.reference.LocalizedTextPurpose
import de.littletrotter.ui.component.Section

/**
 * The widgets of the sender's private-customer tab: the on/off switch and one text area per
 * document language. An empty or unchanged default text is saved as "no own text", so the
 * default wording keeps following the language file instead of being frozen into the sender.
 */
class SenderConsumerNoteFields(languages: List<LanguageDto>, private val tr: (String) -> String) {

    val enabledField = Checkbox(tr("senderProfile.consumerNote.enabled"))

    val textAreasByLanguage: Map<LanguageDto, TextArea> = languages.associateWith { language ->
        TextArea("${tr("senderProfile.consumerNote.text")} (${language.code.uppercase()})").apply {
            maxLength = LocalizedTextPurpose.SELLER_CONSUMER_NOTE.maxLength
            setWidthFull()
        }
    }

    private var defaultTexts: Map<String, String> = emptyMap()

    /** [note] is `null` for a profile that does not exist yet: switch on, default wording shown. */
    fun populate(note: SenderConsumerNoteDto?) {
        defaultTexts = note?.defaultTexts
            ?: textAreasByLanguage.keys.associate { it.code to ConsumerNoteResolver.defaultText(it.code) }
        enabledField.value = note?.enabled ?: true
        textAreasByLanguage.forEach { (language, area) ->
            area.value = note?.texts?.get(language.code) ?: defaultTexts[language.code].orEmpty()
        }
    }

    /** Per language: the typed text, or `null` when blank or identical to the default wording. */
    fun textsForSave(): Map<String, String?> = textAreasByLanguage.entries.associate { (language, area) ->
        val value = area.value.trim()
        language.code to value.takeIf { it.isNotEmpty() && it != defaultTexts[language.code] }
    }

    fun buildSection(): Section {
        val form = FormLayout(*textAreasByLanguage.values.toTypedArray()).also {
            it.setResponsiveSteps(FormLayout.ResponsiveStep("0", 1))
        }
        val help = Span(tr("senderProfile.consumerNote.help")).apply { addClassNames("fs-08", "text-muted") }
        val content = VerticalLayout(enabledField, form, help).also {
            it.isPadding = false
            it.isSpacing = true
            it.setWidthFull()
        }
        return Section(tr("senderProfile.tab.consumer"), "sp-section-bar--violet", content)
    }
}
