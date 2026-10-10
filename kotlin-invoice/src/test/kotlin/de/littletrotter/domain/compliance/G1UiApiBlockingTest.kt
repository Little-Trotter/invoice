package de.littletrotter.domain.compliance

import de.littletrotter.shared.country.Country
import de.littletrotter.ui.view.invoice.selectableTaxRates
import de.littletrotter.application.dto.TaxRateDto
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TODAY

/**
 * "UI and API for a blocked case -> both block".
 *
 * API side: covered by [de.littletrotter.shared.tax.TaxRuleResolver], which blocks all
 * three documented cases with an explanatory
 * [de.littletrotter.shared.tax.TaxRuleResolutionError] (see
 * [ComplianceMatrixResolverTest]).
 *
 * UI side: `TaxRuleResolver` is called from `kotlin-invoice` -- the goods/services dialogs and
 * the cockpit hero call it via `buildTaxRuleResolutionRequest` (reads customer type/country/
 * VAT-id via [de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort], since the UI DTO
 * [de.littletrotter.application.dto.InvoiceDetailDto] does not project those fields) and
 * block/propose based on the result. The proof for that lives in `TaxRuleUiSupportTest`, not
 * here. This test stays because [selectableTaxRates] remains a
 * plain country/active/selectable filter (see the class comment there): the proposed/blocked
 * decision lives in [TaxRuleProposalPanel] upstream of it, not in the ComboBox filter function
 * itself.
 */
class G1UiApiBlockingTest {

    @Test
    fun `API layer already blocks all three documented cases (cross-reference, no new proof needed)`() {
        val blockedRows = ComplianceMatrix.rows.filter {
            it.expectation == ComplianceMatrixRow.Expectation.BLOCKED || it.expectation == ComplianceMatrixRow.Expectation.UNSUPPORTED
        }
        assertTrue(blockedRows.size == 3, "expected exactly the 3 documented blocked/unsupported rows")
        // The actual assertions live in ComplianceMatrixResolverTest — this just documents the
        // cross-reference so a reader of this file finds the real proof.
    }

    @Test
    fun `selectableTaxRates stays a plain filter - the resolve-and-block step now lives in TaxRuleProposalPanel instead`() {
        // selectableTaxRates() still takes no buyer-country/customer-type/vat-id input — by
        // design: it filters *which rows a ComboBox may ever show*
        // (country/active/selectable), while TaxRuleUiSupport.buildTaxRuleResolutionRequest +
        // TaxRuleProposalPanel decide *which one to propose or
        // block* from that same list. See TaxRuleUiSupportTest for the proof.
        val everyGermanActiveRate = listOf(
            TaxRateDto(country = Country.DE, isActive = true, isSelectable = true, code = "T"),
        )
        val selectable = selectableTaxRates(everyGermanActiveRate, date = FIXED_TODAY)
        assertFalse(
            selectable.isEmpty(),
            "sanity: selectableTaxRates still returns rows by country/active/selectable alone — " +
                "confirms it has no buyer-country-based blocking logic to exercise here",
        )
    }
}
