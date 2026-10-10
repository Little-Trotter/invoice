package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.converters.ContinuityConverter
import de.littletrotter.domain.reference.converters.CounterScopeConverter
import de.littletrotter.domain.reference.converters.DatePartConverter
import de.littletrotter.domain.reference.converters.NumberRangeSubjectConverter
import de.littletrotter.domain.reference.converters.PrefixDataFieldConverter
import de.littletrotter.domain.reference.converters.PrefixSourceConverter
import de.littletrotter.domain.reference.converters.ResetCycleConverter
import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.StampedOnCreation
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.shared.numbering.ResetCycle
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

/**
 * Number allocation subject. Declaration order is persisted; CATALOGUE retains ordinal 2.
 * QUOTE ranges are only configured here — the quote module keeps the counter.
 */
enum class NumberRangeSubject { INVOICE, CUSTOMER, CATALOGUE, SELLER, QUOTE }

/** Where a number's prefix comes from. */
enum class PrefixSource {
    /** No prefix — the number is just the counter. */
    NONE,
    /** Fixed prefix configured in [NumberRange.fixedPrefix]. */
    FIXED,
    /** From [de.littletrotter.domain.masterdata.SenderProfile.invoicePrefix]. */
    SELLER_PROFILE,
    /** Derived from a named field of the record ([NumberRange.prefixDataField]). */
    FROM_DATA,
}

/** Curated prefix source. Declaration order is persisted; CATALOGUE_CATEGORY retains ordinal 2. */
enum class PrefixDataField {
    /** First three ASCII-normalized letters of the name (customer, seller). */
    NAME_INITIALS,
    /** ISO-3166-1 alpha-2 (customer, seller). */
    COUNTRY_CODE,
    /** Short form of the [de.littletrotter.shared.catalogue.InvoiceItemCategory] (catalogue position). */
    CATALOGUE_CATEGORY,
}

/** Scope of the counter. */
enum class CounterScope {
    GLOBAL,
    /** One own sequence per seller (see `scopeKey`). */
    PER_SELLER,
}

/** Does the range allocate strictly gaplessly, or only uniquely (gaps then don't matter)? */
enum class Continuity { GAPLESS, UNIQUE_ONLY }

/**
 * A number range — reference data that invoice, customer, article, and seller draw
 * their numbers from. Its structure is composed from named building blocks rather than a typed
 * format mask (same curation principle as the B2B field catalog).
 *
 * `continuity` is the column that concurrency behavior hangs on, not the format: `GAPLESS`
 * (invoice) allocates in the same transaction as the document; `UNIQUE_ONLY`
 * (customer/article/seller) runs in its own transaction, where gaps don't matter.
 */
@Entity
@Table(name = "number_range")
@EntityListeners(CreationStampListener::class)
class NumberRange : StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** Stable code, e.g. `CUSTOMER_DEFAULT`. */
    @field:Size(max = 50)
    var code: String = ""

    @field:Size(max = 100)
    var displayName: String = ""

    @Convert(converter = NumberRangeSubjectConverter::class)
    @Column(name = "subject")
    var subject: NumberRangeSubject = NumberRangeSubject.CUSTOMER

    @Column(name = "start_value")
    var startValue: Long = 1

    @Convert(converter = PrefixSourceConverter::class)
    @Column(name = "prefix_source")
    var prefixSource: PrefixSource = PrefixSource.NONE

    @field:Size(max = 30)
    @Column(name = "fixed_prefix")
    var fixedPrefix: String? = null

    @Convert(converter = PrefixDataFieldConverter::class)
    @Column(name = "prefix_data_field")
    var prefixDataField: PrefixDataField? = null

    @Convert(converter = DatePartConverter::class)
    @Column(name = "date_part")
    var datePart: DatePart = DatePart.NONE

    @Column(name = "counter_width")
    var counterWidth: Int = 5

    @Convert(converter = ResetCycleConverter::class)
    @Column(name = "reset_cycle")
    var resetCycle: ResetCycle = ResetCycle.NEVER

    @Convert(converter = CounterScopeConverter::class)
    @Column(name = "scope")
    var scope: CounterScope = CounterScope.GLOBAL

    @Convert(converter = ContinuityConverter::class)
    @Column(name = "continuity")
    var continuity: Continuity = Continuity.UNIQUE_ONLY

    @Column(name = "is_active")
    var isActive: Boolean = true

    @Column(name = "is_default")
    var isDefault: Boolean = false

    var createdAt: OffsetDateTime? = null

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == null) createdAt = now
    }

    @Version
    var version: Long? = 0

    /**
     * `YEARLY` without a date part would produce the same number twice across a
     * year boundary — violating the legally required uniqueness. This check sits in the domain,
     * not only in the UI.
     */
    fun validate() {
        if (code.isBlank()) throw InvalidParameterException("Nummernkreis-Code darf nicht leer sein.")
        if (displayName.isBlank()) throw InvalidParameterException("Anzeigename darf nicht leer sein.")
        if (startValue < 0) throw InvalidParameterException("Startwert darf nicht negativ sein.")
        if (counterWidth < 1) throw InvalidParameterException("Stellenzahl muss mindestens 1 sein.")
        validatePrefixFields()
        if (resetCycle == ResetCycle.YEARLY && datePart == DatePart.NONE)
            throw InvalidParameterException("Rücksetzung jährlich verlangt einen Datumsanteil (mindestens Jahr) in der Nummer.")
        if (subject == NumberRangeSubject.QUOTE) validateQuoteRange()
    }

    private fun validatePrefixFields() {
        if (prefixSource == PrefixSource.FIXED && fixedPrefix.isNullOrBlank())
            throw InvalidParameterException("Fester Präfix darf nicht leer sein, wenn prefixSource = FIXED ist.")
        if (prefixSource == PrefixSource.FROM_DATA && prefixDataField == null)
            throw InvalidParameterException("Quellfeld darf nicht fehlen, wenn prefixSource = FROM_DATA ist.")
        if (prefixSource != PrefixSource.FIXED && !fixedPrefix.isNullOrEmpty())
            throw InvalidParameterException("Fester Präfix ist nur bei prefixSource = FIXED erlaubt.")
        if (prefixSource != PrefixSource.FROM_DATA && prefixDataField != null)
            throw InvalidParameterException("Quellfeld ist nur bei prefixSource = FROM_DATA erlaubt.")
    }

    private fun validateQuoteRange() {
        if (prefixSource != PrefixSource.NONE && prefixSource != PrefixSource.FIXED)
            throw InvalidParameterException("Angebotsnummern erlauben nur keinen oder einen festen Präfix.")
        if (scope != CounterScope.GLOBAL)
            throw InvalidParameterException("Angebotsnummern verlangen den Geltungsbereich GLOBAL.")
        if (continuity != Continuity.GAPLESS)
            throw InvalidParameterException("Angebotsnummern verlangen lückenlose Vergabe (GAPLESS).")
    }
}
