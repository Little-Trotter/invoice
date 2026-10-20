package de.littletrotter.application.invoice

import de.littletrotter.shared.documents.DocumentTexts

/** The wording a sender's consumer notice falls back to when the sender maintains no text of their own. */
object ConsumerNoteDefaults {
    private const val DEFAULT_KEY = "consumerNote.default"

    /** The language-file default wording in [language]. */
    fun defaultText(language: String): String = DocumentTexts.text(DEFAULT_KEY, language)
}
