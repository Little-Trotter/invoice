package de.littletrotter.domain.value

import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.util.stream.Stream

class ForeignTaxNumberValidatorTest {

    companion object {
        @JvmStatic
        fun valid(): Stream<Pair<Country, String>> = Stream.of(
            Country.AT to "123456789",
            Country.AT to "12-345/6789",
            Country.BE to "85010112387",
            Country.BE to "0123456749",
            Country.BE to "1234567894",
            Country.BG to "7510101236",
            Country.BG to "123456786",
            Country.BG to "1020304061234",
            Country.CY to "12345678A",
            Country.CZ to "12345679",
            Country.CZ to "850101123",
            Country.CZ to "8501011233",
            Country.DK to "0101901234",
            Country.DK to "12345674",
            Country.EE to "38501011239",
            Country.EE to "12345678",
            Country.ES to "12345678Z",
            Country.ES to "X1234567L",
            Country.ES to "Y7654321G",
            Country.ES to "K1234567A",
            Country.ES to "L1234567B",
            Country.ES to "M1234567C",
            Country.ES to "A58585852",
            Country.ES to "Q2828001D",
            Country.ES to "C1111111I",
            Country.FI to "12345671",
            Country.FI to "131052-308T",
            Country.FI to "010100A123D",
            Country.FI to "010190+123M",
            Country.FR to "1234567890123",
            Country.FR to "3010166012345",
            Country.FR to "123456782",
            Country.GR to "123456783",
            Country.HR to "12345678903",
            Country.HU to "8123456786",
            Country.HU to "12345676241",
            Country.IE to "1234567T",
            Country.IE to "6433435NF",
            Country.IE to "1234567FA",
            Country.IT to "12345678903",
            Country.IT to "RSSMRA85T10A562S",
            Country.IT to "BNZVCN80A01H501L",
            Country.IT to "RSSMRA85T10A56LS",
            Country.LT to "38501011239",
            Country.LT to "123456789",
            Country.LU to "1990010112345",
            Country.LU to "19900101123",
            Country.LV to "12345678901",
            Country.LV to "32123456789",
            Country.MT to "1234567M",
            Country.MT to "123G",
            Country.MT to "0123456A",
            Country.MT to "123456789",
            Country.NL to "111222333",
            Country.NL to "123456782",
            Country.PL to "1234563218",
            Country.PL to "44051401359",
            Country.PT to "123456789",
            Country.PT to "501234560",
            Country.RO to "1850101123451",
            Country.RO to "12345674",
            Country.RO to "124",
            Country.SE to "8501011236",
            Country.SE to "198501011236",
            Country.SE to "200101011237",
            Country.SI to "12345679",
            Country.SK to "850101123",
            Country.SK to "8501011233",
        )

        @JvmStatic
        fun structureErrors(): Stream<Pair<Country, String>> = Stream.of(
            Country.AT to "12345678",
            Country.AT to "1234567890",
            Country.AT to "12345678A",
            Country.BE to "01234567",
            Country.BE to "2123456749",
            Country.BG to "12345",
            Country.BG to "12345678AB",
            Country.CY to "1234567A",
            Country.CY to "123456789",
            Country.CZ to "2545678",
            Country.CZ to "12345678901",
            Country.DK to "123456789",
            Country.DK to "1234567",
            Country.EE to "1234567",
            Country.EE to "123456789012",
            Country.ES to "1234567",
            Country.ES to "X123456A",
            Country.ES to "1234567890",
            Country.FI to "ABC",
            Country.FI to "131052308UU",
            Country.FR to "12345678",
            Country.FR to "4234567890123",
            Country.FR to "12345678901234",
            Country.GR to "12345678",
            Country.GR to "1234567890",
            Country.HR to "1234567890",
            Country.HR to "123456789012",
            Country.HU to "123456789",
            Country.HU to "12345676941",
            Country.IE to "12345678",
            Country.IE to "123456AB",
            Country.IT to "1234567890",
            Country.IT to "RSSMRA85T10A562",
            Country.LT to "1234567890",
            Country.LT to "12345678",
            Country.LU to "199001011234",
            Country.LU to "1990010112A45",
            Country.LV to "1234567890",
            Country.LV to "4000301234A",
            Country.MT to "12345678M",
            Country.MT to "M1234567",
            Country.MT to "12M",
            Country.NL to "12345678",
            Country.NL to "12345678A",
            Country.PL to "123456789",
            Country.PL to "123456789012",
            Country.PT to "12345678",
            Country.PT to "1234567890",
            Country.RO to "0123456",
            Country.RO to "1",
            Country.RO to "12345678901",
            Country.SE to "12345",
            Country.SE to "21850101123",
            Country.SI to "1234567",
            Country.SI to "123456789",
            Country.SK to "85010112",
            Country.SK to "12345678901",
        )

        private val RELEASE_DATE: LocalDate = LocalDate.of(2026, 10, 19)
    }

    @ParameterizedTest
    @MethodSource("valid")
    fun `accepts a valid number`(case: Pair<Country, String>) {
        assertTrue(ForeignTaxNumberValidator.isValid(case.first, case.second), "${case.first} ${case.second}")
        assertTrue(ForeignTaxNumberValidator.validate(case.first, case.second).isEmpty)
    }

    @ParameterizedTest
    @MethodSource("structureErrors")
    fun `rejects a wrong structure`(case: Pair<Country, String>) {
        assertEquals(
            ForeignTaxNumberValidator.INVALID_KEY,
            ForeignTaxNumberValidator.validate(case.first, case.second).orElse(""),
            "${case.first} ${case.second}",
        )
    }

    @Test
    fun `every supported seller registration country has a rule, examples and a format hint`() {
        val supported = Country.supportedSellerVatRegistrationCountries(RELEASE_DATE)
        assertEquals(26, supported.size)
        supported.forEach { country ->
            assertTrue(ForeignTaxNumberValidator.formatHint(country).isNotBlank(), "hint $country")
            assertTrue(valid().anyMatch { it.first == country }, "valid example $country")
            assertTrue(structureErrors().anyMatch { it.first == country }, "structure error $country")
        }
        assertEquals(supported, ForeignTaxNumberRules.byCountry.keys)
    }

    @Test
    fun `normalisation removes separators and upper-cases`() {
        assertEquals("FR12345678", ForeignTaxNumberValidator.normalize(" fr 12.34-56/78 "))
        assertEquals("010100A123D", ForeignTaxNumberValidator.normalize("010100a123d"))
        assertEquals("131052308T", ForeignTaxNumberValidator.normalize("131052-308T"))
    }

    @Test
    fun `formatted input is validated in normalised form`() {
        assertTrue(ForeignTaxNumberValidator.isValid(Country.AT, "12-345/6789"))
        assertTrue(ForeignTaxNumberValidator.isValid(Country.FI, "131052-308t"))
        assertTrue(ForeignTaxNumberValidator.isValid(Country.PL, "123 456 32 18"))
        assertTrue(ForeignTaxNumberValidator.isValid(Country.ES, "x-1234567-l"))
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = [" ", "  ", "-", " . / "])
    fun `null and blank input is required`(input: String?) {
        assertEquals(
            ForeignTaxNumberValidator.REQUIRED_KEY,
            ForeignTaxNumberValidator.validate(Country.FR, input).orElse(""),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["DE", "CH", "GB", "US"])
    fun `a country without a rule is unsupported`(alpha2: String) {
        val country = Country.valueOf(alpha2)
        assertEquals(
            ForeignTaxNumberValidator.UNSUPPORTED_KEY,
            ForeignTaxNumberValidator.validate(country, "123456789").orElse(""),
        )
        assertFalse(ForeignTaxNumberValidator.isValid(country, "123456789"))
        assertEquals("", ForeignTaxNumberValidator.formatHint(country))
    }

    @Test
    fun `format hint names the expected format`() {
        assertTrue(ForeignTaxNumberValidator.formatHint(Country.FR).contains("SIREN"))
    }

    @Test
    fun `a wrong check digit is not a structure error because no sheet names an algorithm`() {
        assertTrue(ForeignTaxNumberValidator.isValid(Country.NL, "123456789"))
        assertTrue(ForeignTaxNumberValidator.isValid(Country.FR, "123456789"))
    }

    @ParameterizedTest
    @CsvSource(
        "FR, 123456782, foreignTaxNumber.label.siren",
        "FR, 123 456 782, foreignTaxNumber.label.siren",
        "FR, 1234567890123, foreignTaxNumber.label.nFiscal",
        "BE, 0123456749, foreignTaxNumber.label.kbo",
        "BE, 85.01.01-123.87, foreignTaxNumber.label.rrn",
        "NL, 123456782, foreignTaxNumber.label.rsin",
        "LU, 1990010112345, foreignTaxNumber.label.matricule",
        "LU, 19900101123, foreignTaxNumber.label.matricule",
        "DK, 12345674, foreignTaxNumber.label.tin",
        "ES, 12345678Z, foreignTaxNumber.label.tin",
        "FR, 12345, foreignTaxNumber.label.tin",
        "BE, 2123456749, foreignTaxNumber.label.tin",
        "NL, 12345678, foreignTaxNumber.label.tin",
        "LU, 12, foreignTaxNumber.label.tin",
        "CH, 123456789, foreignTaxNumber.label.tin",
    )
    fun `label key follows the recognised format`(alpha2: String, number: String, expected: String) {
        assertEquals(expected, ForeignTaxNumberValidator.labelKey(Country.valueOf(alpha2), number))
    }

    @Test
    fun `label key falls back to the generic key for a country with a term but a mismatching length`() {
        assertEquals(ForeignTaxNumberValidator.LABEL_TIN, ForeignTaxNumberValidator.labelKey(Country.FR, "1234567890"))
        assertEquals(ForeignTaxNumberValidator.LABEL_BSN, "foreignTaxNumber.label.bsn")
    }

    @ParameterizedTest
    @CsvSource(
        "DK, 12345674, DK12345674, true",
        "DK, 12 34 56 74, DK 12345674, true",
        "DK, 12345675, DK12345674, false",
        "NL, 123456782, NL123456782B01, true",
        "NL, 123456782, NL123456782, true",
        "NL, 123456783, NL123456782B01, false",
        "BE, 0123456749, BE0123456749, true",
        "BE, 0123456749, BE123456749, true",
        "BE, 0123456749, BE0123456748, false",
        "GR, 123456783, EL123456783, true",
        "GR, 123456783, GR123456783, false",
        "FR, 123456782, DK123456782, false",
        "FR, 123456782, FR, false",
        "FR, 123456782, 123456782, false",
    )
    fun `duplicates the VAT ID without its prefix`(alpha2: String, number: String, vatId: String, expected: Boolean) {
        assertEquals(expected, ForeignTaxNumberValidator.duplicatesVatId(Country.valueOf(alpha2), number, vatId))
    }

    @Test
    fun `an empty number never duplicates the VAT ID`() {
        assertFalse(ForeignTaxNumberValidator.duplicatesVatId(Country.DK, "", "DK12345674"))
    }
}
