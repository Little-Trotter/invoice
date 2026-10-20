package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.SenderConsumerNoteDto
import de.littletrotter.application.invoice.ConsumerNoteDefaults
import de.littletrotter.application.masterdata.port.inbound.EditSenderConsumerNoteUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.domain.exception.SenderProfileNotFoundException
import de.littletrotter.domain.repository.LanguageRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.shared.stampForPersist
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Implements [EditSenderConsumerNoteUseCase]. One short transaction per call: the sender is
 * loaded managed, the text group is changed through [de.littletrotter.domain.masterdata
 * .SenderProfile.changeConsumerNote] (blank removes, last removal drops the group) and validated
 * before it is stored.
 */
@ApplicationScoped
class SenderConsumerNoteService @Inject constructor(
    private val senderProfileRepository: SenderProfileRepository,
    private val languageRepository: LanguageRepository,
    private val auditActorProvider: AuditActorQueryPort,
) : EditSenderConsumerNoteUseCase {

    @Transactional
    override fun loadConsumerNote(senderProfileId: Long): SenderConsumerNoteDto {
        val sender = senderProfileRepository.findById(senderProfileId)
            ?: throw SenderProfileNotFoundException(senderProfileId)
        val codes = languageRepository.findActiveOrdered().map { it.code }
        // Own entries only: consumerNoteFor would fall back to the fallback language, which would
        // show the German text as if it were maintained for English.
        val own = sender.consumerNote?.entries.orEmpty().mapNotNull { e -> e.language?.code?.let { it to e.value } }.toMap()
        return SenderConsumerNoteDto(
            senderProfileId = senderProfileId,
            enabled = sender.consumerNoteEnabled,
            texts = codes.mapNotNull { code -> own[code]?.let { code to it } }.toMap(),
            defaultTexts = codes.associateWith { ConsumerNoteDefaults.defaultText(it) },
        )
    }

    @RequiresPermission(AppPermission.MASTER_DATA_EDIT)
    @Transactional
    override fun saveConsumerNote(senderProfileId: Long, enabled: Boolean, texts: Map<String, String?>) {
        val sender = senderProfileRepository.findById(senderProfileId)
            ?: throw SenderProfileNotFoundException(senderProfileId)
        sender.enableConsumerNote(enabled)
        texts.forEach { (code, value) ->
            languageRepository.findByCode(code)?.let { language -> sender.changeConsumerNote(language, value) }
        }
        sender.consumerNote?.validate()
        sender.stampForPersist(auditActorProvider.currentActor())
        senderProfileRepository.save(sender)
    }
}
