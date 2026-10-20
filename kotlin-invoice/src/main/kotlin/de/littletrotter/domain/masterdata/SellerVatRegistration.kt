package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.value.ForeignTaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.domain.value.VatIdValidator
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDate

/**
 * An additional, non-domestic VAT registration of a [SenderProfile]: the seller is
 * also locally registered for VAT in [country] (curated set,
 * [Country.supportedSellerVatRegistrationCountries]), beyond the mandatory German
 * [SenderProfile.vatId]. At most one registration per country per profile (DB unique index
 * `uq_seller_vat_registration_profile_country`, checked again here via [assertUniqueCountries]).
 *
 * This table and entity exist regardless of whether any registrations were ever added, and stay
 * empty until a seller actually configures one.
 */
@Entity
@Table(name = "seller_vat_registration")
class SellerVatRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    protected set

    // LAZY: the owning profile is already in hand when its registrations are read.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_profile_id", nullable = false)
    var senderProfile: SenderProfile? = null
    protected set

    @Convert(converter = CountryAttributeConverter::class)
    @Column(name = "country", nullable = false)
    var country: Country = Country.DE
    protected set

    @Column(name = "vat_id", nullable = false, length = 64)
    var vatId: VatId? = null
    protected set

    /** The seller's national tax number in [country]; optional, and only meaningful next to the
     *  registration's [vatId] (the two are captured and printed as a pair). */
    @Column(name = "tax_number", length = ForeignTaxNumber.MAX_LENGTH)
    var taxNumber: ForeignTaxNumber? = null
    protected set

    @Version
    var version: Long? = 0
    protected set

    /** Replaces the registration's VAT id; [validate] checks the prefix against [country]. */
    fun changeVatId(vatId: VatId?) {
        this.vatId = vatId
    }

    /** Sets or clears ([taxNumber] `null`) the country's tax number. */
    fun changeTaxNumber(taxNumber: ForeignTaxNumber?) {
        this.taxNumber = taxNumber
    }

    fun validate(today: LocalDate) {
        if (country !in Country.supportedSellerVatRegistrationCountries(today)) {
            throw InvalidParameterException(
                "Land ${country.alpha2} ist für zusätzliche Verkäufer-USt-IdNrn nicht freigegeben " +
                    "(erlaubt: ${Country.supportedSellerVatRegistrationCountries(today).joinToString { it.alpha2 }})."
            )
        }
        // VatId's own init already rejects blank/format-invalid input eagerly at
        // construction; only the cross-field rule (does the number's prefix match [country]?)
        // stays deferred here, it needs both fields at once.
        val current = vatId ?: throw InvalidParameterException(
            if (taxNumber != null) {
                "Steuernummer ist nur zusammen mit der USt-IdNr. desselben Landes erfassbar."
            } else {
                "USt-IdNr. darf nicht leer sein."
            }
        )
        val expectedPrefix = VatIdValidator.vatPrefixFor(country)
        if (expectedPrefix == null || !current.value.startsWith(expectedPrefix)) {
            throw InvalidParameterException(
                "USt-IdNr. '${current.value}' passt nicht zum Land ${country.alpha2} (Präfix $expectedPrefix erwartet)."
            )
        }
    }

    companion object {
        /** Validates before constructing — never a half-built, invalid row. [vatId] is already
         *  the value type: a caller building this from raw user input converts via [VatId.of]/
         *  [SellerVatRegistrationCommand] first. */
        fun of(
            senderProfile: SenderProfile,
            country: Country,
            vatId: VatId?,
            today: LocalDate,
            taxNumber: ForeignTaxNumber? = null,
        ): SellerVatRegistration =
            SellerVatRegistration().apply {
                this.senderProfile = senderProfile
                this.country = country
                this.vatId = vatId
                this.taxNumber = taxNumber
                validate(today)
            }

        /** Rejects a candidate list carrying the same [SellerVatRegistration.country] twice. The
         *  DB unique index enforces this too, but failing here first gives the maintenance UI a
         *  clear domain message instead of a generic constraint violation. */
        fun assertUniqueCountries(registrations: List<SellerVatRegistration>) {
            val duplicated = registrations.groupBy { it.country }.filterValues { it.size > 1 }.keys
            if (duplicated.isNotEmpty()) {
                throw InvalidParameterException(
                    "Land ${duplicated.first().alpha2} ist mehrfach angegeben — je Land ist höchstens " +
                        "eine zusätzliche USt-IdNr. zulässig."
                )
            }
        }
    }
}
