package de.littletrotter.application.export

import de.littletrotter.shared.country.Country
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InvoiceTaxRowMapperTest {

    private fun blankRow(): Array<Any?> = arrayOfNulls(75)
    private val seed = InvoiceHeaderExportRow(invoiceId = 1L, invoiceDate = LocalDate.now())

    @Test
    fun `maps the seller VAT registration country and the reverse-charge flag`() {
        val row = blankRow()
        row[27] = Country.FR
        row[62] = true

        val result = InvoiceTaxRowMapper.apply(seed, row)

        assertThat(result.sellerVatRegistrationCountry).isEqualTo("FR")
        assertThat(result.reverseCharge).isTrue()
    }

    @Test
    fun `defaults to null country and reverseCharge false on a blank row`() {
        val result = InvoiceTaxRowMapper.apply(seed, blankRow())

        assertThat(result.sellerVatRegistrationCountry).isNull()
        assertThat(result.reverseCharge).isFalse()
    }

    @Test
    fun `a non-Country column decodes the seller VAT registration country to null`() {
        val row = blankRow()
        row[27] = "not-a-country"

        val result = InvoiceTaxRowMapper.apply(seed, row)

        assertThat(result.sellerVatRegistrationCountry).isNull()
    }

    @Test
    fun `does not disturb fields outside the tax group`() {
        val seeded = seed.copy(invoiceNumber = "IP-1")

        val result = InvoiceTaxRowMapper.apply(seeded, blankRow())

        assertThat(result.invoiceNumber).isEqualTo("IP-1")
    }
}
