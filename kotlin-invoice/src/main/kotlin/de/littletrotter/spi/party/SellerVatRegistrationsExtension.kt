package de.littletrotter.spi.party

/**
 * Extension point for the seller's additional, non-domestic VAT registrations: a
 * variable-length (0..n) list of country+VAT-id rows, docked into the sender master-data form.
 * Unlike [PartyFormExtension] (a *fixed* set of value+scheme pairs, one editor per pair), this
 * extension's shape is a repeatable row list with add/remove — a distinct UI shape that doesn't
 * fit [PartyFormExtension]'s single-pair model, so it gets its own narrow interface instead of
 * forcing the existing one to grow a second responsibility. Scoped to the seller only: the
 * additional-VAT-registration concept doesn't apply to the buyer.
 *
 * As with [PartyFormExtension], the extension owns read/write access to the persisted rows; the
 * view renders the list generically and never references the storage entity directly.
 */
interface SellerVatRegistrationsExtension {

    /** Stable identifier of this module (module-scope unique). */
    val id: String

    /** The curated set of country codes (ISO 3166-1 alpha-2) a row's [SellerVatRegistrationRow
     *  .country] may use. Informational for the view (e.g. to populate a country dropdown); the
     *  extension's [write] still re-validates. */
    val supportedCountries: List<String>

    /** Reads the current rows off the given sender-profile domain object. */
    fun read(senderProfile: Any): List<SellerVatRegistrationRow>

    /** Replaces the full row set on the given sender-profile domain object. The caller always
     *  submits the whole edited list, not incremental add/remove operations. */
    fun write(senderProfile: Any, rows: List<SellerVatRegistrationRow>)
}

/** One row of a [SellerVatRegistrationsExtension]: an EU country (ISO 3166-1 alpha-2) plus the
 *  local VAT id issued there. */
data class SellerVatRegistrationRow(val country: String, val vatId: String)
