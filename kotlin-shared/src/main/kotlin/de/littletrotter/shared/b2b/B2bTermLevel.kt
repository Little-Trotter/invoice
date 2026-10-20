package de.littletrotter.shared.b2b

/**
 * Where in the invoice a [B2bTerm]'s business term lives. This is a
 * property of the term itself, not a choice the anwender makes when building a `B2bProfile`
 * (kotlin-invoice): a position-level term (`LINE`) can never be rendered on the seller party, no
 * matter what `B2bPlacement` a profile field requests.
 */
enum class B2bTermLevel {
    /** Invoice header (e.g. buyer reference, project terms). */
    HEADER,

    /** Invoice line item / position. */
    LINE,

    /** Seller party (Anbieter). */
    SELLER_PARTY,

    /** Buyer party (Empfänger). */
    BUYER_PARTY,
}
