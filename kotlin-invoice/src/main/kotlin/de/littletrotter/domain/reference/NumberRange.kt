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
import de.littletrotter.shared.numbering.NumberFormatter
import de.littletrotter.shared.numbering.NumberPattern
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

/** The configurable part of a [NumberRange]; identity, revision and creation stamp stay the entity's. */
data class NumberRangeDefinition(
    val code: String,
    val displayName: String,
    val subject: NumberRangeSubject,
    val startValue: Long,
    val prefixSource: PrefixSource,
    val fixedPrefix: String?,
    val prefixDataField: PrefixDataField?,
    val datePart: DatePart,
    val counterWidth: Int,
    val resetCycle: ResetCycle,
    val scope: CounterScope,
    val continuity: Continuity,
    val isActive: Boolean,
    val isDefault: Boolean,
)

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
        protected set

    /** Stable code, e.g. `CUSTOMER_DEFAULT`. */
    @field:Size(max = 50)
    var code: String = ""
        protected set

    @field:Size(max = 100)
    var displayName: String = ""
        protected set

    @Convert(converter = NumberRangeSubjectConverter::class)
    @Column(name = "subject")
    var subject: NumberRangeSubject = NumberRangeSubject.CUSTOMER
        protected set

    @Column(name = "start_value")
    var startValue: Long = 1
        protected set

    @Convert(converter = PrefixSourceConverter::class)
    @Column(name = "prefix_source")
    var prefixSource: PrefixSource = PrefixSource.NONE
        protected set

    @field:Size(max = 30)
    @Column(name = "fixed_prefix")
    var fixedPrefix: String? = null
        protected set

    @Convert(converter = PrefixDataFieldConverter::class)
    @Column(name = "prefix_data_field")
    var prefixDataField: PrefixDataField? = null
        protected set

    @Convert(converter = DatePartConverter::class)
    @Column(name = "date_part")
    var datePart: DatePart = DatePart.NONE
        protected set

    @Column(name = "counter_width")
    var counterWidth: Int = 5
        protected set

    @Convert(converter = ResetCycleConverter::class)
    @Column(name = "reset_cycle")
    var resetCycle: ResetCycle = ResetCycle.NEVER
        protected set

    @Convert(converter = CounterScopeConverter::class)
    @Column(name = "scope")
    var scope: CounterScope = CounterScope.GLOBAL
        protected set

    @Convert(converter = ContinuityConverter::class)
    @Column(name = "continuity")
    var continuity: Continuity = Continuity.UNIQUE_ONLY
        protected set

    @Column(name = "is_active")
    var isActive: Boolean = true
        protected set

    @Column(name = "is_default")
    var isDefault: Boolean = false
        protected set

    var createdAt: OffsetDateTime? = null
        protected set

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == null) createdAt = now
    }

    @Version
    var version: Long? = 0
        protected set

    /**
     * Replaces the whole configuration in one step, so a range is never half-reconfigured.
     * Validation stays a separate call ([validate]) because the catalogue write path runs it
     * after every assignment.
     */
    fun redefine(definition: NumberRangeDefinition) {
        code = definition.code
        displayName = definition.displayName
        subject = definition.subject
        startValue = definition.startValue
        prefixSource = definition.prefixSource
        fixedPrefix = definition.fixedPrefix
        prefixDataField = definition.prefixDataField
        datePart = definition.datePart
        counterWidth = definition.counterWidth
        resetCycle = definition.resetCycle
        scope = definition.scope
        continuity = definition.continuity
        isActive = definition.isActive
        isDefault = definition.isDefault
    }

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

    /** The number's shape: prefix resolved from [context], date part and counter width from this range. */
    fun pattern(context: NumberContext): NumberPattern =
        NumberPattern(resolvePrefix(context), datePart, counterWidth)

    fun resolvePrefix(context: NumberContext): String? = when (prefixSource) {
        PrefixSource.NONE -> null
        PrefixSource.FIXED -> fixedPrefix
        PrefixSource.SELLER_PROFILE -> context.sellerPrefix
        PrefixSource.FROM_DATA -> context.dataFieldValue
    }

    /** `'*'` for [CounterScope.GLOBAL] — a single, shared key for all sellers, deliberately not
     *  `null`: `null` in a composite counter key needs special SQL comparison (`IS NULL` instead
     *  of `=`) and is an unnecessary source of bugs. */
    fun scopeKey(context: NumberContext): String = when (scope) {
        CounterScope.GLOBAL -> "*"
        CounterScope.PER_SELLER -> context.sellerId?.toString()
            ?: throw IllegalArgumentException("Nummernkreis '$code' ist PER_SELLER, aber der Kontext trägt keine sellerId.")
    }

    /** `''` for [ResetCycle.NEVER], otherwise the year — the reset counts per year regardless of
     *  how fine-grained the *number's* own date part is resolved. */
    fun periodKey(context: NumberContext): String = NumberFormatter.periodKey(resetCycle, context.date)
}
