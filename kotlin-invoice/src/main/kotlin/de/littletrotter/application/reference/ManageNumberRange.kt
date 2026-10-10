package de.littletrotter.application.reference

import de.littletrotter.application.dto.NumberRangeDto
import de.littletrotter.application.reference.port.inbound.ManageNumberRangeUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixDataField
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.domain.repository.NumberCounterRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [ManageNumberRangeUseCase]; every write needs reference-data edit rights. */
@ApplicationScoped
class ManageNumberRange @Inject constructor(
    private val numberRangeRepository: NumberRangeRepository,
    private val numberCounterRepository: NumberCounterRepository,
) : ManageNumberRangeUseCase {

    private val rows = CatalogueWrites(numberRangeRepository::findById, NumberRange::version) { NumberRange() }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun saveNumberRange(range: NumberRangeDto): NumberRangeDto {
        val target = rows.rowFor(range.id, range.version)
        val persisted = range.id?.let { id -> StoredPrefix(id, target) }
        range.applyTo(target)
        target.validate()
        if (target.subject == NumberRangeSubject.QUOTE) assertQuotePrefixUnique(target)
        if (persisted != null) assertPrefixNotChangedWhileInUse(persisted, target)
        return numberRangeRepository.save(target).toDto()
    }

    /** The stored prefix configuration of a range, captured before the edit is applied. */
    private data class StoredPrefix(
        val id: Long,
        val displayName: String,
        val prefixSource: PrefixSource,
        val fixedPrefix: String?,
        val prefixDataField: PrefixDataField?,
    ) {
        constructor(id: Long, range: NumberRange) : this(id, range.displayName, range.prefixSource, range.fixedPrefix, range.prefixDataField)

        fun differsFrom(range: NumberRange): Boolean =
            prefixSource != range.prefixSource || fixedPrefix != range.fixedPrefix || prefixDataField != range.prefixDataField
    }

    /**
     * A number range's prefix configuration must not change once a number has ever been allocated
     * from it: GAPLESS continuity and the invoice-number audit trail both rely on a stable prefix
     * meaning, and a new range is the sanctioned way to switch
     * ([NumberCounterRepository.hasBeenUsed] is the authoritative "in use" signal — a
     * `number_counter` row only exists after an actual allocation, never after a mere
     * [NumberRangeService.peek]). Compares against the prefix captured from the stored row before
     * the edit was applied onto it.
     */
    private fun assertPrefixNotChangedWhileInUse(persisted: StoredPrefix, range: NumberRange) {
        if (persisted.differsFrom(range) && numberCounterRepository.hasBeenUsed(persisted.id)) {
            throw InvalidParameterException(
                "Das Präfix des Nummernkreises '${persisted.displayName}' kann nicht mehr geändert werden, " +
                    "weil aus ihm bereits mindestens eine Nummer vergeben wurde. Legen Sie stattdessen einen " +
                    "neuen Nummernkreis an.",
            )
        }
    }

    /**
     * The quote module draws quote numbers from the active default QUOTE range and keeps its
     * counter per range code and period, while the quote number itself is unique across all
     * quotes. Two QUOTE ranges that share prefix and date part would each start counting at the
     * same value and the second one would eventually produce a number an earlier quote already
     * holds. Inactive ranges are compared too, because the numbers they issued remain in use. The
     * counter width is deliberately not part of the identity: a narrow counter that grows past its
     * width would still collide with a wider one.
     */
    private fun assertQuotePrefixUnique(range: NumberRange) {
        val conflict = numberRangeRepository.findBySubject(NumberRangeSubject.QUOTE)
            .firstOrNull { other -> other.id != range.id && other.datePart == range.datePart && effectivePrefix(other).equals(effectivePrefix(range), ignoreCase = true) }
        if (conflict != null) {
            throw InvalidParameterException(
                "Präfix und Datumsanteil werden bereits vom Angebots-Nummernkreis '${conflict.displayName}' verwendet. " +
                    "Angebotsnummern müssen eindeutig bleiben; wählen Sie einen anderen Präfix oder Datumsanteil.",
            )
        }
    }

    private fun effectivePrefix(range: NumberRange): String =
        if (range.prefixSource == PrefixSource.FIXED) range.fixedPrefix.orEmpty().trim() else ""

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun deleteNumberRange(id: Long) = numberRangeRepository.delete(id)
}
