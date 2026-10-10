package de.littletrotter.application.dto

/**
 * The private-customer notice of one sender as the sender form's tab shows it.
 *
 * [texts] holds only the languages the sender has maintained; [defaultTexts] holds the language
 * file default for every active language so the tab can prefill an empty field.
 */
data class SenderConsumerNoteDto(
    val senderProfileId: Long,
    val enabled: Boolean,
    val texts: Map<String, String>,
    val defaultTexts: Map<String, String>,
)
