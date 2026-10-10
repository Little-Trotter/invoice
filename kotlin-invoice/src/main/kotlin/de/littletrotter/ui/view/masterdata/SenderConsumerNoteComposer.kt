package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.dto.LanguageDto
import de.littletrotter.application.dto.SenderConsumerNoteDto
import de.littletrotter.application.masterdata.port.inbound.EditSenderConsumerNoteUseCase
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.application.settings.port.inbound.WordingProfileQueryPort
import de.littletrotter.shared.wording.WordingProfile
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * [SenderProfileView]'s access to the document languages and to the private-customer notice,
 * bundled so the view does not take every port itself. The notice tab exists only for the crafts
 * wording profile, independent of any recipient.
 */
@Dependent
class SenderConsumerNoteComposer @Inject constructor(
    private val languageQueries: LanguageQueryPort,
    private val consumerNotes: EditSenderConsumerNoteUseCase,
    private val wordingProfile: WordingProfileQueryPort,
) {
    fun activeLanguages(): List<LanguageDto> = languageQueries.findActiveLanguages()

    /** Whether the private-customer tab is offered at all. */
    fun tabAvailable(): Boolean = wordingProfile.currentProfile() == WordingProfile.HANDWERK

    fun load(senderProfileId: Long): SenderConsumerNoteDto = consumerNotes.loadConsumerNote(senderProfileId)

    fun save(senderProfileId: Long, enabled: Boolean, texts: Map<String, String?>) =
        consumerNotes.saveConsumerNote(senderProfileId, enabled, texts)
}
