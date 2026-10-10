package de.littletrotter.application.export

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InvoicePartyRowMapperTest {

    private fun blankRow(): Array<Any?> = arrayOfNulls(75)

    private val seed = InvoiceHeaderExportRow(invoiceId = 1L, invoiceDate = LocalDate.now())

    @Test
    fun `falls back to master-data seller and buyer fields when no snapshot id is present`() {
        val row = blankRow()
        row[18] = "Live Seller GmbH"
        row[19] = "DE111111111"
        row[38] = "Live Buyer AG"

        val result = InvoicePartyRowMapper.apply(seed, row)

        assertThat(result.partySource).isEqualTo(PartySource.MASTER_DATA)
        assertThat(result.sellerName).isEqualTo("Live Seller GmbH")
        assertThat(result.sellerVatId).isEqualTo("DE111111111")
        assertThat(result.buyerName).isEqualTo("Live Buyer AG")
    }

    @Test
    fun `prefers the frozen snapshot over master data when a snapshot id is present`() {
        val row = blankRow()
        row[18] = "Live Seller GmbH"
        row[28] = 900L // sellerSnapshot.id present
        row[29] = "Frozen Seller GmbH"
        row[44] = 901L // buyerSnapshot.id present
        row[45] = "Frozen Buyer AG"

        val result = InvoicePartyRowMapper.apply(seed, row)

        assertThat(result.partySource).isEqualTo(PartySource.SNAPSHOT)
        assertThat(result.sellerName).isEqualTo("Frozen Seller GmbH")
        assertThat(result.buyerName).isEqualTo("Frozen Buyer AG")
    }

    @Test
    fun `partySource follows only the buyer snapshot presence flag, independent of the seller`() {
        val row = blankRow()
        row[28] = 900L // seller snapshot present, buyer snapshot absent

        val result = InvoicePartyRowMapper.apply(seed, row)

        assertThat(result.partySource).isEqualTo(PartySource.MASTER_DATA)
        assertThat(result.sellerIdentifierValue).isNull()
    }

    @Test
    fun `every party field defaults to null on a blank row`() {
        val result = InvoicePartyRowMapper.apply(seed, blankRow())

        assertThat(result.sellerName).isNull()
        assertThat(result.buyerName).isNull()
        assertThat(result.sellerElectronicAddressSchemeId).isNull()
        assertThat(result.buyerElectronicAddressSchemeId).isNull()
    }

    @Test
    fun `does not disturb fields outside the parties group`() {
        val seeded = seed.copy(invoiceNumber = "IP-1")

        val result = InvoicePartyRowMapper.apply(seeded, blankRow())

        assertThat(result.invoiceNumber).isEqualTo("IP-1")
    }
}
