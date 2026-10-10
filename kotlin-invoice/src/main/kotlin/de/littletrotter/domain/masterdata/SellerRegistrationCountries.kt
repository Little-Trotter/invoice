package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country

/**
 * anbieter-einvoice-ergonomie §3 — the countries the seller is VAT-registered in: the issuing
 * country of its own German VAT id ([vatId], parsed via [Country.fromVatIdPrefix]) plus every
 * [additionalRegistrations] country (see
 * [SenderProfile.additionalVatRegistrations]/`SellerVatRegistration.country`).
 *
 * Extracted as a small, pure function — no Vaadin/JPA types — so the country-scoped B2B profile
 * visibility rule (`de.littletrotter.domain.reference.isB2bSellerFieldGroupVisible`) stays
 * unit-testable without a UI or a database. A blank/unparseable [vatId] simply contributes
 * nothing (mirrors [Country.fromVatIdPrefix]'s own "never throws" contract) — a half-filled seller
 * form must remain evaluable.
 */
fun sellerRegistrationCountries(vatId: String?, additionalRegistrations: Set<Country>): Set<Country> =
    setOfNotNull(Country.fromVatIdPrefix(vatId)) + additionalRegistrations
