package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.country.Country

/**
 * Named parameter holding the resolved [Country] values of a free-text filter.
 *
 * A country column stores [Country] as an `Integer` (`CountryAttributeConverter`), so the obvious
 * `lower(country) LIKE :filter` does not merely miss rows — Hibernate rejects `lower()` on an
 * integer argument while parsing the query, so every non-blank search term threw. The text is
 * resolved to enum values by [Country.matching] instead and compared with `IN :$COUNTRY_PARAM`.
 *
 * QOD2/`SqlSourceToSinkFlow` — the country-filtered repositories
 * ([PanacheHotelRateRepository], [PanacheMileageRateRepository], [PanachePerDiemRateRepository])
 * used to build this predicate with a shared `countryPredicate(path, countries)` helper whose
 * `countries` parameter is derived from the free-text search box, and Qodana's taint tracker
 * flagged the helper's return value as attacker-controlled purely because a tainted parameter
 * fed into it — even though the helper could only ever return one of two hardcoded literals
 * (`"1 = 0"` or `"$path IN :$COUNTRY_PARAM"`, `path` always a call-site constant, never the
 * search text itself). The fix was not to weaken the check but to remove the indirection: each
 * repository now selects one of the two literal JPQL fragments itself with a `when` on
 * `countries.isEmpty()` — a boolean, not string data — so no tainted value is ever assigned into
 * the query text. `countries.isEmpty()` still means "the search text matched no [Country] at
 * all", and `IN ()` is not valid JPQL, so the empty branch is `1 = 0` rather than an empty `IN`
 * list; the non-empty branch binds [COUNTRY_PARAM], the empty one must not.
 */
const val COUNTRY_PARAM = "countries"
