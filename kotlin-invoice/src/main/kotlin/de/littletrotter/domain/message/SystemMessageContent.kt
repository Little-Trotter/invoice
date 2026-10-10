package de.littletrotter.domain.message

/**
 * What a producer says in a [SystemMessage] (S107): classification, i18n key and positional
 * args, the pre-resolved text and the optional reference to the entity it is about. Everything the
 * system stamps itself (actor, occurrence time) and the delivery scope (recipient) stay outside,
 * on [SystemMessage.of] and [de.littletrotter.application.message.MessageCenterService.record].
 *
 * [refType] is an open string (e.g. `"INVOICE"`), [refId] the referenced entity's id as text —
 * both nullable, neither validated here: [SystemMessage.validate] owns the rules on the entity.
 */
data class SystemMessageContent(
    val severity: SystemMessageSeverity,
    val source: SystemMessageSource,
    val messageKey: String,
    val args: List<String> = emptyList(),
    val resolvedText: String,
    val refType: String? = null,
    val refId: String? = null,
)
