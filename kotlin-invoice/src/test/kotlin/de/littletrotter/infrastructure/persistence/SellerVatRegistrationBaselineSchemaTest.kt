package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TODAY

class SellerVatRegistrationBaselineSchemaTest {

    @Test
    fun `seller VAT country checks cover every domain-supported country`() {
        val ddl = object {}.javaClass.getResource(
            "/db/migration/ddl/V1__baseline_schema.sql",
        )!!.readText()
        val expectedCountries = Country.supportedSellerVatRegistrationCountries(today = FIXED_TODAY)
            .map { it.numeric }
            .sorted()
            .joinToString(", ")

        assertTrue(
            ddl.contains("CONSTRAINT chk_seller_vat_registration_country CHECK (country IN ($expectedCountries))"),
            "the seller VAT DDL check must match the domain-supported country set",
        )
        assertTrue(
            ddl.contains("CHECK (seller_vat_registration_country IS NULL OR seller_vat_registration_country IN ($expectedCountries))"),
            "the invoice VAT DDL check must match the domain-supported country set",
        )
    }
}
