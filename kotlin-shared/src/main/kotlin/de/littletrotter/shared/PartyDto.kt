package de.littletrotter.shared

/** A value+schemeId pair; value never travels without its scheme. */
data class SchemedIdentifierDto(val value: String, val schemeId: String)

/**
 * Non-domestic VAT registration: country code is distinct from [SchemedIdentifierDto]'s scheme type.
 *
 * [taxNumber] is the seller's national tax number in that country, already left out (`null`) when it
 * adds nothing to [vatId]; [taxNumberLabelKey] names the printed label (i18n key, `{0}` = [country]).
 * Both are decided by the producer so the renderer needs no country rules.
 */
data class VatRegistrationDto(
  val country: String,
  val vatId: String,
  val taxNumber: String? = null,
  val taxNumberLabelKey: String? = null,
)

data class PartyDto(
  val name: String? = null,
  val street: String? = null,
  val postalCode: String? = null,
  val city: String? = null,
  /** Deliberately `null` for Germany to omit country line in PDF output (DIN 5008). */
  val country: String? = null,
  val email: String? = null,
  val phone: String? = null,
  val vatId: String? = null,
  val iban: String? = null,
  val bic: String? = null,
  val taxNumber: String? = null,
  val globalId: String? = null,
  val customerNumber: String? = null,
  val bankName: String? = null,
  val managingDirector: String? = null,
  val courtOfJurisdiction: String? = null,
  val registerCourt: String? = null,
  val commercialRegisterNumber: String? = null,
  val footerNote: String? = null,
  /** Stored as entered; the renderer normalises for display (strips the `http(s)://` scheme
   *  and a trailing slash). Seller-only. */
  val website: String? = null,
  /** Sitz der Gesellschaft. `null`/blank = the renderer falls back to [city]. Seller-only. */
  val registeredSeat: String? = null,
  // ── Structured identifiers, each a value+schemeId pair ─────────────
  /** BT-29 (seller) / BT-46 (buyer). */
  val identifier: SchemedIdentifierDto? = null,
  /** BT-30 (seller) / BT-47 (buyer). */
  val legalRegistration: SchemedIdentifierDto? = null,
  /** BT-34 (seller) / BT-49 (buyer) — never derived from [email]; a bare email has no EAS
   *  scheme and is not the same Business Term. */
  val electronicAddress: SchemedIdentifierDto? = null,
  /** `BUSINESS` | `CONSUMER` — only meaningful on the buyer side. */
  val customerType: String? = null,
  val vatIdVerificationStatus: String? = null,
  /** Additional non-domestic VAT registrations, seller-only. */
  val additionalVatRegistrations: List<VatRegistrationDto> = emptyList(),
)

/** BG-13 DELIVER TO — distinct from billing address (BG-8); `null` means no separate ship-to. */
data class ShipToDto(
  val name: String? = null,
  val street: String? = null,
  val postalCode: String? = null,
  val city: String? = null,
  /** `null` = Germany (don't print), else ISO 3166-1 alpha-2. */
  val country: String? = null,
)
