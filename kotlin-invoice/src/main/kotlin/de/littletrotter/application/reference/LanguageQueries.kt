package de.littletrotter.application.reference

import de.littletrotter.application.dto.LanguageDto
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.domain.repository.LanguageRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [LanguageQueryPort] on top of [LanguageRepository]. */
@ApplicationScoped
class LanguageQueries @Inject constructor(
    private val languageRepository: LanguageRepository,
) : LanguageQueryPort {
    override fun findActiveLanguages(): List<LanguageDto> = languageRepository.findActiveOrdered().map { it.toDto() }
}
