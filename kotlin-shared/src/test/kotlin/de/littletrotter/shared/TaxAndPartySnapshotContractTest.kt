package de.littletrotter.shared

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** DTO/message-contract tests for the tax and party snapshot fields. */
class TaxAndPartySnapshotContractTest {

    private val mapper = jacksonObjectMapper()

    private fun roundtrip(item: InvoiceItemDto): InvoiceItemDto {
        val json = mapper.writeValueAsString(item)
        return mapper.readValue(json)
    }

    // ── AE/E/O roundtrip losslessly ───────────────────────────────

    @Test
    fun `R6-T01 - AE with VATEX-EU-AE roundtrips losslessly`() {
        val item = InvoiceItemDto(
            taxRuleCode = "EU_B2B_RC_SERVICE", taxCategory = "AE",
            taxRatePercent = BigDecimal.ZERO, exemptionReasonCode = "VATEX-EU-AE",
        )
        val read = roundtrip(item)
        assertEquals("AE", read.taxCategory)
        assertEquals("VATEX-EU-AE", read.exemptionReasonCode)
        assertEquals(0, BigDecimal.ZERO.compareTo(read.taxRatePercent))
    }

    @Test
    fun `R6-T02 - E with a reason text roundtrips losslessly`() {
        val item = InvoiceItemDto(
            taxRuleCode = "DE_EXEMPT_SERVICE", taxCategory = "E",
            taxRatePercent = BigDecimal.ZERO, exemptionReasonText = "steuerbefreit", taxLegalBasis = "§ 4 UStG",
        )
        val read = roundtrip(item)
        assertEquals("E", read.taxCategory)
        assertEquals("steuerbefreit", read.exemptionReasonText)
        assertEquals("§ 4 UStG", read.taxLegalBasis)
    }

    @Test
    fun `R6-T03 - O with a justification roundtrips losslessly`() {
        val item = InvoiceItemDto(
            taxRuleCode = "OUTSIDE_SCOPE_SERVICE", taxCategory = "O",
            taxRatePercent = BigDecimal.ZERO, exemptionReasonText = "nicht steuerbar",
        )
        val read = roundtrip(item)
        assertEquals("O", read.taxCategory)
        assertEquals("nicht steuerbar", read.exemptionReasonText)
    }

    // ── BT-10 and BT-49 stay separate fields ───────────────────────────

    @Test
    fun `R6-T04 - buyerReference (BT-10) and electronicAddress (BT-49) are separate fields after roundtrip`() {
        val invoice = InvoiceDto(
            references = InvoiceReferencesDto(buyerReference = "991-01484-64"),
            recipient = PartyDto(electronicAddress = SchemedIdentifierDto("buyer@example.de", "EM")),
        )
        val json = mapper.writeValueAsString(invoice)
        val read: InvoiceDto = mapper.readValue(json)

        assertEquals("991-01484-64", read.references.buyerReference)
        assertEquals("buyer@example.de", read.recipient?.electronicAddress?.value)
        assertEquals("EM", read.recipient?.electronicAddress?.schemeId)
        // setting one must never have derived/overwritten the other
        assertEquals("991-01484-64", read.references.buyerReference)
    }

    // ── Identifier scheme survives the roundtrip ───────────────────────

    @Test
    fun `R6-T05 - a SchemedIdentifierDto's schemeId survives the roundtrip`() {
        val party = PartyDto(identifier = SchemedIdentifierDto("987654321", "0204"))
        val json = mapper.writeValueAsString(party)
        val read: PartyDto = mapper.readValue(json)

        assertEquals("987654321", read.identifier?.value)
        assertEquals("0204", read.identifier?.schemeId)
    }

    // ── The DE-null country convention is explicit, not implicit ──────

    @Test
    fun `R6-T11 - a DE-null country round-trips as null, a foreign country round-trips unchanged`() {
        val de = PartyDto(country = null)
        val fr = PartyDto(country = "FR")

        assertNull(mapper.readValue<PartyDto>(mapper.writeValueAsString(de)).country)
        assertEquals("FR", mapper.readValue<PartyDto>(mapper.writeValueAsString(fr)).country)
    }
}
