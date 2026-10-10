package de.littletrotter.ui.component

import de.littletrotter.shared.country.Country
import java.util.Locale

/**
 * The JVM default locale's own display name for [alpha2] (e.g. "Deutschland" for `"DE"` under a
 * German locale, "Germany" under an English one) — no flag emoji. Shared implementation for the
 * idiom `RecipientView`, `ScopedB2bProfileFieldsSection` and `B2bProfilesTab` each used to carry
 * as their own private copy.
 */
fun countryDisplayName(alpha2: String): String = Locale.forLanguageTag("und-$alpha2").displayCountry

/** [countryDisplayName] for a [Country] value directly. */
fun countryDisplayName(country: Country): String = countryDisplayName(country.alpha2)
