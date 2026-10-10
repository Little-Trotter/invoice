package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.SenderConsumerNoteDto

/**
 * Input port for maintaining a sender's private-customer notice: the on/off switch and one text
 * per document language. Existing invoices keep their own copy, so a save only affects invoices
 * created or recomputed afterwards.
 */
interface EditSenderConsumerNoteUseCase : CommandEntryPoint {
    /** Current switch and texts of the sender plus the default wording per active language. */
    fun loadConsumerNote(senderProfileId: Long): SenderConsumerNoteDto

    /**
     * [texts] is keyed by language code. A blank value removes that language's own text (the
     * default wording applies again); a code absent from the map is left untouched.
     */
    fun saveConsumerNote(senderProfileId: Long, enabled: Boolean, texts: Map<String, String?>)
}
