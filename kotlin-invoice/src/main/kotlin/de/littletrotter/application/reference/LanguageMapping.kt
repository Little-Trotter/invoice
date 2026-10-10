package de.littletrotter.application.reference

import de.littletrotter.application.dto.LanguageDto
import de.littletrotter.domain.reference.Language

/** Row view of a stored, read-only reference language. */
internal fun Language.toDto(): LanguageDto = LanguageDto(
    id = id,
    code = code,
    isFallback = isFallback,
    sortOrder = sortOrder,
)
