package de.littletrotter.domain.compliance

import de.littletrotter.shared.tax.ExplicitExemption
import de.littletrotter.shared.tax.ServiceType
import de.littletrotter.shared.tax.TaxRuleResolutionRequest
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.shared.tax.VatCategory
import java.time.LocalDate

/**
 * G1 — the single source of truth for the "Pflicht-Testmatrix", so the resolver proof
 * ([de.littletrotter.shared.tax.TaxRuleResolver]-level, this module) and the report
 * generator both read the exact same ten rows instead of two hand-kept copies drifting apart.
 * The KoSIT/XSD-level proof for the seven `gültig` rows lives in kotlin-xpdf (module boundary:
 * this module has no dependency on kotlin-xpdf's validators) — see
 * `kotlin-xpdf/.../ComplianceMatrixFixtureTest.kt`, which maps each row's [fixtureId] to an F5
 * fixture and writes its own JSON results the report generator reads back in.
 */
data class ComplianceMatrixRow(
    /** Matches the task doc's "Fall" column, used as the stable row id everywhere (report,
     *  fixture mapping, test names). */
    val id: String,
    val landKunde: String,
    val expectation: Expectation,
    /** Present only for [Expectation.VALID] rows — the doc doesn't name a rule/category/rate
     *  for blocked/unsupported rows ("—"). */
    val expectedRuleCode: String? = null,
    val expectedCategory: VatCategory? = null,
    val expectedRate: String? = null,
    val request: TaxRuleResolutionRequest,
    /** F5 fixture id (without the `positive/`/`negative/` prefix or `.xml` suffix) this row's
     *  document-level (SYNTAX/KOSIT) proof reuses — `null` for the three rows blocked before a
     *  document would ever be built. */
    val fixtureId: String? = null,
) {
    enum class Expectation { VALID, BLOCKED, UNSUPPORTED }
}

object ComplianceMatrix {

    private val serviceDate: LocalDate = LocalDate.of(2026, 6, 1)

    private fun req(
        sellerCountry: Country = Country.DE,
        buyerCountry: Country,
        customerType: CustomerType,
        sellerVatId: String? = "DE123456789",
        buyerVatId: String? = null,
        buyerVatIdVerificationStatus: VatIdVerificationStatus? = null,
        explicitExemption: ExplicitExemption? = null,
    ) = TaxRuleResolutionRequest(
        serviceType = ServiceType.GENERAL_SERVICE,
        serviceDate = serviceDate,
        sellerCountry = sellerCountry,
        buyerCountry = buyerCountry,
        customerType = customerType,
        sellerVatId = sellerVatId,
        buyerVatId = buyerVatId,
        buyerVatIdVerificationStatus = buyerVatIdVerificationStatus,
        explicitExemption = explicitExemption,
    )

    val rows: List<ComplianceMatrixRow> = listOf(
        ComplianceMatrixRow(
            id = "DE-B2B",
            landKunde = "DE/BUSINESS",
            expectation = ComplianceMatrixRow.Expectation.VALID,
            expectedRuleCode = "DE_STANDARD_19",
            expectedCategory = VatCategory.STANDARD,
            expectedRate = "19.00",
            request = req(buyerCountry = Country.DE, customerType = CustomerType.BUSINESS),
            // At the CII/KoSIT level, a domestic standard-rate B2B and B2C invoice are
            // structurally identical — the distinction is TaxRuleResolver's customerType input,
            // not something the schema/schematron enforces differently. DE-B2B and DE-B2C
            // deliberately share one fixture rather than fabricating an XML-level difference
            // that doesn't exist.
            fixtureId = "inland-b2b-19-s",
        ),
        ComplianceMatrixRow(
            id = "DE-B2C",
            landKunde = "DE/CONSUMER",
            expectation = ComplianceMatrixRow.Expectation.VALID,
            expectedRuleCode = "DE_STANDARD_19",
            expectedCategory = VatCategory.STANDARD,
            expectedRate = "19.00",
            request = req(buyerCountry = Country.DE, customerType = CustomerType.CONSUMER),
            fixtureId = "inland-b2b-19-s",
        ),
        ComplianceMatrixRow(
            id = "EU-B2B",
            landKunde = "FR/BUSINESS + gültige FR-Id",
            expectation = ComplianceMatrixRow.Expectation.VALID,
            expectedRuleCode = "EU_B2B_RC_SERVICE",
            expectedCategory = VatCategory.REVERSE_CHARGE,
            expectedRate = "0",
            request = req(
                buyerCountry = Country.FR, customerType = CustomerType.BUSINESS,
                buyerVatId = "FR12345678901", buyerVatIdVerificationStatus = VatIdVerificationStatus.VERIFIED,
            ),
            fixtureId = "eu-b2b-reverse-charge-ae",
        ),
        ComplianceMatrixRow(
            id = "EU-B2C",
            landKunde = "FR/CONSUMER",
            expectation = ComplianceMatrixRow.Expectation.VALID,
            expectedRuleCode = "EU_B2C_DE_STANDARD_19",
            expectedCategory = VatCategory.STANDARD,
            expectedRate = "19.00",
            request = req(buyerCountry = Country.FR, customerType = CustomerType.CONSUMER),
            fixtureId = "eu-b2c-19-s",
        ),
        ComplianceMatrixRow(
            id = "Befreit",
            landKunde = "DE + Grund",
            expectation = ComplianceMatrixRow.Expectation.VALID,
            expectedRuleCode = "DE_EXEMPT_SERVICE",
            expectedCategory = VatCategory.EXEMPT,
            expectedRate = "0",
            request = req(
                buyerCountry = Country.DE, customerType = CustomerType.BUSINESS,
                explicitExemption = ExplicitExemption(
                    ruleCode = "DE_EXEMPT_SERVICE",
                    category = VatCategory.EXEMPT,
                    reasonText = "Heilbehandlung im Bereich der Humanmedizin",
                    legalBasis = "§ 4 Nr. 14 UStG",
                ),
            ),
            fixtureId = "steuerbefreit-e-mit-pflichtgrund",
        ),
        ComplianceMatrixRow(
            id = "Nicht steuerbar",
            landKunde = "explizit + Grund",
            expectation = ComplianceMatrixRow.Expectation.VALID,
            expectedRuleCode = "OUTSIDE_SCOPE_SERVICE",
            expectedCategory = VatCategory.OUTSIDE_SCOPE,
            expectedRate = "0",
            request = req(
                buyerCountry = Country.DE, customerType = CustomerType.BUSINESS,
                explicitExemption = ExplicitExemption(
                    ruleCode = "OUTSIDE_SCOPE_SERVICE",
                    category = VatCategory.OUTSIDE_SCOPE,
                    reasonText = "Echter Schadensersatz, kein Leistungsaustausch",
                    legalBasis = "Nicht steuerbarer Vorgang mangels Leistungsaustausch (§ 1 Abs. 1 UStG e contrario)",
                ),
            ),
            fixtureId = "nicht-steuerbar-o",
        ),
        ComplianceMatrixRow(
            id = "Behörde",
            landKunde = "DE + Leitweg-ID",
            expectation = ComplianceMatrixRow.Expectation.VALID,
            expectedRuleCode = "DE_STANDARD_19",
            expectedCategory = VatCategory.STANDARD,
            expectedRate = "19.00",
            request = req(buyerCountry = Country.DE, customerType = CustomerType.BUSINESS),
            fixtureId = "oeffentlicher-auftraggeber-leitweg-id",
        ),
        ComplianceMatrixRow(
            id = "RC ohne Käufer-Id",
            landKunde = "FR/BUSINESS",
            expectation = ComplianceMatrixRow.Expectation.BLOCKED,
            // Already proven at both levels: this resolver's own tax-rule resolution and the
            // ae-ohne-kaeufer-ustid.xml fixture (BR-AE-02 at the KOSIT stage) — this row cites
            // both rather than re-deriving the same proof a third time.
            request = req(buyerCountry = Country.FR, customerType = CustomerType.BUSINESS, buyerVatId = null),
        ),
        ComplianceMatrixRow(
            id = "Falsches Id-Präfix",
            landKunde = "FR + BE-Id",
            expectation = ComplianceMatrixRow.Expectation.BLOCKED,
            // Already proven by a structural cross-check (buyer VAT-id prefix vs.
            // buyer country), not something KoSIT's schema/schematron checks at all.
            request = req(
                buyerCountry = Country.FR, customerType = CustomerType.BUSINESS,
                buyerVatId = "BE0123456789", buyerVatIdVerificationStatus = VatIdVerificationStatus.VERIFIED,
            ),
        ),
        ComplianceMatrixRow(
            id = "Drittland",
            landKunde = "CH",
            expectation = ComplianceMatrixRow.Expectation.UNSUPPORTED,
            // Already proven by the third-country case in TaxRuleResolverTest.
            request = req(buyerCountry = Country.CH, customerType = CustomerType.BUSINESS),
        ),
    )
}
