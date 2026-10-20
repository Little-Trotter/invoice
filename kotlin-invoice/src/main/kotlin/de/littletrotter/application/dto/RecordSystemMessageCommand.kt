package de.littletrotter.application.dto

import de.littletrotter.application.command.Command
import de.littletrotter.domain.message.SystemMessageSeverity
import de.littletrotter.domain.message.SystemMessageSource

/** A message the UI puts into the message center for everyone, with its text already resolved. */
data class RecordSystemMessageCommand(
    val severity: SystemMessageSeverity,
    val source: SystemMessageSource,
    val messageKey: String,
    val args: List<String> = emptyList(),
    val resolvedText: String,
) : Command
