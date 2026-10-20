package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.SellerVatRegistrationDto
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.tax.ServiceType
import de.littletrotter.shared.tax.TaxRuleResolution
import de.littletrotter.shared.tax.TaxRuleResolver
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.value.TaxRatePercent
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TODAY

class TaxRuleUiSupportTest {

    private fun rate(
        country: Country = Country.DE,
        active: Boolean = true,
        selectable: Boolean = true,
        validFrom: LocalDate? = null,
        validTo: LocalDate? = null,
    ) = TaxRateDto(
        name = "Test",
        rate = TaxRatePercent(BigDecimal("19.00")),
        country = country,
        isActive = active,
        isSelectable = selectable,
        validFrom = validFrom,
        validTo = validTo,
        category = "S",
    )

    // ── selectableTaxRates ────────────────────────────────────────────────────

    @Test
    fun `a foreign legacy rate is excluded even when active and selectable`() {
        val fr = rate(country = Country.FR)
        assertTrue(selectableTaxRates(listOf(fr), date = FIXED_TODAY).isEmpty())
    }

    @Test
    fun `an inactive rate is excluded`() {
        val inactive = rate(active = false)
        assertTrue(selectableTaxRates(listOf(inactive), date = FIXED_TODAY).isEmpty())
    }

    @Test
    fun `a non-selectable rate is excluded`() {
        val notSelectable = rate(selectable = false)
        assertTrue(selectableTaxRates(listOf(notSelectable), date = FIXED_TODAY).isEmpty())
    }

    @Test
    fun `a rate expired before the given date is excluded`() {
        val expired = rate(validTo = LocalDate.of(2025, 12, 31))
        assertTrue(selectableTaxRates(listOf(expired), LocalDate.of(2026, 1, 1)).isEmpty())
    }

    @Test
    fun `a rate valid at the given date is included`() {
        val valid = rate(validFrom = LocalDate.of(2026, 1, 1), validTo = LocalDate.of(2026, 12, 31))
        assertEquals(1, selectableTaxRates(listOf(valid), LocalDate.of(2026, 6, 15)).size)
    }

    // ── taxRuleLabel ───────────────────────────────────────────────────────────

    @Test
    fun `the three zero-percent categories AE, E and O are textually distinguishable`() {
        val ae = rate().copy(name = "Reverse Charge", rate = TaxRatePercent(BigDecimal.ZERO), category = "AE", exemptionReasonText = "Steuerschuldnerschaft des Leistungsempfängers")
        val e = rate().copy(name = "Heilbehandlung", rate = TaxRatePercent(BigDecimal.ZERO), category = "E", exemptionReasonText = "Steuerfrei nach § 4 Nr. 14 UStG")
        val o = rate().copy(name = "Drittland", rate = TaxRatePercent(BigDecimal.ZERO), category = "O", exemptionReasonText = "Nicht steuerbar im Inland")
        val labels = setOf(taxRuleLabel(ae), taxRuleLabel(e), taxRuleLabel(o))
        assertEquals(3, labels.size, "expected three distinct labels, got: $labels")
        assertTrue(taxRuleLabel(ae).contains("AE"))
        assertTrue(taxRuleLabel(e).contains("E)") || taxRuleLabel(e).contains(", E"))
        assertTrue(taxRuleLabel(o).contains("O)") || taxRuleLabel(o).contains(", O"))
    }

    @Test
    fun `standard rate label includes name, rate and category`() {
        val standard = rate().copy(name = "Regelsteuersatz", rate = TaxRatePercent(BigDecimal("19.00")), category = "S")
        val label = taxRuleLabel(standard)
        assertTrue(label.contains("Regelsteuersatz"))
        assertTrue(label.contains("19"))
        assertTrue(label.contains("S"))
    }

    @Test
    fun `taxRuleLabel falls back to the category's own short reason when no exemptionReasonText is set`() {
        val ae = rate().copy(name = "RC", rate = TaxRatePercent(BigDecimal.ZERO), category = "AE", exemptionReasonText = null)
        assertFalse(taxRuleLabel(ae).contains("null"))
        assertTrue(taxRuleLabel(ae).contains("Reverse Charge"))
    }

    // ── Wiring TaxRuleResolver into the UI ────────────────────────────────────
    // G1UiApiBlockingTest documented this as a real,
    // unproven gap: selectableTaxRates() never consulted buyer country/customer type/VAT-id, so
    // the resolver had no call site anywhere in kotlin-invoice. These tests exercise the glue
    // that closes it (buildTaxRuleResolutionRequest + TaxRuleResolver.resolve together), the
    // same path InvoiceDetailViewItemDialogsGoodsService.kt/InvoiceCockpitHero now use.

    private fun invoiceWithoutRecipient(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun invoiceWithRecipient(recipientId: Long = 1L, sellerVatRegistrationCountry: String? = null): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = LocalDate.of(2026, 6, 1),
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 9L, name = "Sender", vatId = "DE111111111"),
        recipient = PartyDto(id = recipientId, name = "Recipient", vatId = "FR22222222222"),
        items = emptyList(), latestGenerationJob = null,
        sellerVatRegistrationCountry = sellerVatRegistrationCountry,
    )

    /** Builds an [ItemDto] carrying only the frozen VAT category [category] — the pure field
     *  [isProposalStale] reads, without needing `applyTaxRule` on a domain entity to snapshot it. */
    private fun itemWithTaxCategory(category: String?): ItemDto = ItemDto(
        id = 1L, positionId = "1", description = "Test", quantity = BigDecimal.ONE,
        unit = null, unitPriceNet = null, taxRate = null, taxRateRefId = null,
        netAmount = null, vatAmount = null, grossAmount = null, taxCategory = category,
    )

    private fun stubRecipient(
        recipientQueryPort: RecipientQueryPort,
        recipientId: Long,
        customerType: CustomerType?,
        verificationStatus: VatIdVerificationStatus? = null,
        vatId: String? = "FR22222222222",
    ) {
        val recipient = RecipientSummaryDto(
            id = recipientId, name = "Recipient", customerNumber = null, shortHash = null, contactPerson = null,
            routingId = null, vatId = vatId, customerType = customerType, vatIdVerificationStatus = verificationStatus,
            reverseChargePreference = ReverseChargePreference.ASK, billingCity = null, billingCountryAlpha2 = null,
            deliveryCountryAlpha2 = null,
        )
        whenever(recipientQueryPort.findRecipientById(recipientId)).thenReturn(recipient)
    }

    @Test
    fun `buildTaxRuleResolutionRequest is null when the invoice has no recipient`() {
        assertNull(buildTaxRuleResolutionRequest(mock<RecipientQueryPort>(), invoiceWithoutRecipient(), today = FIXED_TODAY))
    }

    @Test
    fun `buildTaxRuleResolutionRequest is null when the recipient has no customerType yet`() {
        val recipientQueryPort = mock<RecipientQueryPort>()
        stubRecipient(recipientQueryPort, 1L, customerType = null)
        assertNull(buildTaxRuleResolutionRequest(recipientQueryPort, invoiceWithRecipient(), today = FIXED_TODAY))
    }

    @Test
    fun `buildTaxRuleResolutionRequest is null while a foreign seller VAT registration is active`() {
        // On this path the tax rule resolver must make no claim at all
        // (items pull tax from article country variants); mirrors the domain twin
        // Invoice.taxRuleResolutionRequestOrNull. The recipient here is fully resolvable — only
        // the active registration suppresses the request.
        val recipientQueryPort = mock<RecipientQueryPort>()
        stubRecipient(
            recipientQueryPort, 1L, customerType = CustomerType.BUSINESS,
            verificationStatus = VatIdVerificationStatus.VERIFIED,
        )

        assertNull(buildTaxRuleResolutionRequest(recipientQueryPort, invoiceWithRecipient(sellerVatRegistrationCountry = "LU"), today = FIXED_TODAY))
    }

    @Test
    fun `buildTaxRuleResolutionRequest maps seller, recipient and service type correctly`() {
        val recipientQueryPort = mock<RecipientQueryPort>()
        stubRecipient(
            recipientQueryPort, 1L, customerType = CustomerType.BUSINESS,
            verificationStatus = VatIdVerificationStatus.VERIFIED,
        )

        val request = buildTaxRuleResolutionRequest(recipientQueryPort, invoiceWithRecipient(), FIXED_TODAY, InvoiceItemCategory.SERVICE.toServiceType())

        assertNotNull(request)
        assertEquals(ServiceType.GENERAL_SERVICE, request!!.serviceType)
        assertEquals(Country.DE, request.sellerCountry)
        assertEquals(Country.FR, request.buyerCountry)
        assertEquals(CustomerType.BUSINESS, request.customerType)
        assertEquals("DE111111111", request.sellerVatId)
        assertEquals("FR22222222222", request.buyerVatId)
        assertEquals(VatIdVerificationStatus.VERIFIED, request.buyerVatIdVerificationStatus)
    }

    @Test
    fun `VAT ID prefix controls tax country while billing address stays unchanged`() {
        val recipientQueryPort = mock<RecipientQueryPort>()
        stubRecipient(
            recipientQueryPort, 1L, customerType = CustomerType.BUSINESS,
            verificationStatus = VatIdVerificationStatus.VERIFIED,
            vatId = "LU12345678",
        )
        val request = buildTaxRuleResolutionRequest(recipientQueryPort, invoiceWithRecipient(), today = FIXED_TODAY)

        assertEquals(Country.LU, request?.buyerCountry)
    }

    @Test
    fun `a PRODUCT item is always resolved as UNSUPPORTED, never proposed a rate`() {
        assertEquals(ServiceType.UNSUPPORTED, InvoiceItemCategory.PRODUCT.toServiceType())
        assertEquals(ServiceType.GENERAL_SERVICE, InvoiceItemCategory.SERVICE.toServiceType())
        assertEquals(ServiceType.GENERAL_SERVICE, InvoiceItemCategory.TRIP.toServiceType())
    }

    @Test
    fun `matchingRate finds the proposed rule by code and is null for a blocked resolution`() {
        val de19 = rate().copy(code = "DE_STANDARD_19", category = "S")
        val proposed = TaxRuleResolution.Proposed("DE_STANDARD_19", de.littletrotter.shared.tax.VatCategory.STANDARD, BigDecimal("19.00"))
        assertEquals(de19, proposed.matchingRate(listOf(de19)))

        val blocked = TaxRuleResolution.Blocked(de.littletrotter.shared.tax.TaxRuleResolutionError.UNSUPPORTED_TAX_CASE, "n/a")
        assertNull(blocked.matchingRate(listOf(de19)))
    }

    @Test
    fun `proposal label contains only ISO country selected rate and spacing`() {
        val lu17 = rate().copy(country = Country.LU, rate = TaxRatePercent(BigDecimal("17.00")), category = "S")
        assertEquals("LU 17 %", selectedTaxRateLabel(lu17))
    }

    @Test
    fun `reverse charge label does not pretend to be a positive country rate`() {
        val reverseCharge = rate().copy(country = Country.DE, rate = TaxRatePercent(BigDecimal.ZERO), category = "AE")
        assertEquals("Reverse Charge", selectedTaxRateLabel(reverseCharge))
    }

    @Test
    fun `a proposed rule without a matching reference row renders no misleading rate`() {
        val proposal = TaxRuleResolution.Proposed("MISSING", VatCategory.STANDARD, BigDecimal("19.00"))

        assertEquals("", taxRuleHintText(null, proposal, null))
        assertEquals("", taxRuleHintText(null, null, null))
    }

    @Test
    fun `trip preview prefers its stored reverse charge row`() {
        val storedRc = rate(active = false).copy(id = 11L, category = "AE")
        val storedStandard = rate().copy(id = 12L, category = "S")
        val otherRc = rate().copy(id = 13L, category = "AE")
        val taxRateQueries = mock<TaxRateQueryPort>()
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(storedStandard, storedRc, otherRc))
        val items = listOf(
            itemWithTaxCategory("S").copy(taxRateRefId = storedStandard.id),
            itemWithTaxCategory("AE").copy(id = 2L, taxRateRefId = storedRc.id),
        )

        assertEquals(storedRc, reverseChargeTaxRateFor(items, taxRateQueries.findAllTaxRates()))
    }

    @Test
    fun `trip preview uses the unique active reverse charge row without a stored one`() {
        val inactiveRc = rate(active = false).copy(id = 21L, category = "AE")
        val activeRc = rate().copy(id = 22L, category = "AE")
        val taxRateQueries = mock<TaxRateQueryPort>()
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(inactiveRc, activeRc))

        assertEquals(activeRc, reverseChargeTaxRateFor(emptyList(), taxRateQueries.findAllTaxRates()))
    }

    @Test
    fun `trip preview rejects ambiguous active reverse charge rows`() {
        val first = rate().copy(id = 31L, category = "AE")
        val second = rate().copy(id = 32L, category = "AE")
        val standard = rate().copy(id = 33L, category = "S")
        val taxRateQueries = mock<TaxRateQueryPort>()
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf(standard, first, second))

        assertNull(reverseChargeTaxRateFor(emptyList(), taxRateQueries.findAllTaxRates()))
    }

    @Test
    fun `and T08 - exemptionDetailsIncomplete blocks a blank reason or legal basis`() {
        assertTrue(exemptionDetailsIncomplete("", "§ 4 UStG"), "blank reason (R7-T07) must block")
        assertTrue(exemptionDetailsIncomplete("Heilbehandlung", ""), "blank legal basis (R7-T08) must block")
        assertFalse(exemptionDetailsIncomplete("Heilbehandlung", "§ 4 UStG"))
    }

    @Test
    fun `requiresExplicitExemptionDetails is true only for E and O categories`() {
        assertTrue(requiresExplicitExemptionDetails(rate().copy(category = "E")))
        assertTrue(requiresExplicitExemptionDetails(rate().copy(category = "O")))
        assertFalse(requiresExplicitExemptionDetails(rate().copy(category = "S")))
        assertFalse(requiresExplicitExemptionDetails(rate().copy(category = "AE")))
        assertFalse(requiresExplicitExemptionDetails(null))
    }

    @Test
    fun `a third-country buyer resolves to Blocked with a non-blank hint (closes G1UiApiBlockingTest's documented gap)`() {
        val recipientQueryPort = mock<RecipientQueryPort>()
        stubRecipient(
            recipientQueryPort, 1L, customerType = CustomerType.BUSINESS, vatId = "CH123456789",
        )

        val request = buildTaxRuleResolutionRequest(recipientQueryPort, invoiceWithRecipient(), today = FIXED_TODAY)
        assertNotNull(request)
        val resolution = TaxRuleResolver.resolve(request!!, today = FIXED_TODAY)

        assertTrue(resolution is TaxRuleResolution.Blocked)
        assertTrue((resolution as TaxRuleResolution.Blocked).detail.isNotBlank())
        assertTrue(taxRuleHintText(null, resolution, null).isNotBlank(), "UI now has real hint text to show for this case")
    }

    @Test
    fun `a Reverse Charge resolution surfaces both VAT ids and a verification status`() {
        val recipientQueryPort = mock<RecipientQueryPort>()
        stubRecipient(
            recipientQueryPort, 1L, customerType = CustomerType.BUSINESS,
            verificationStatus = VatIdVerificationStatus.VERIFIED,
        )

        val request = buildTaxRuleResolutionRequest(recipientQueryPort, invoiceWithRecipient(), today = FIXED_TODAY)
        assertNotNull(request)
        val resolution = TaxRuleResolver.resolve(request!!, today = FIXED_TODAY)

        assertTrue(resolution is TaxRuleResolution.Proposed)
        assertEquals(de.littletrotter.shared.tax.VatCategory.REVERSE_CHARGE, (resolution as TaxRuleResolution.Proposed).category)
        assertEquals("DE111111111", request!!.sellerVatId)
        assertEquals("FR22222222222", request.buyerVatId)
        assertEquals(VatIdVerificationStatus.VERIFIED, request.buyerVatIdVerificationStatus)
    }

    @Test
    fun `isProposalStale is false right after applying a rule, true once the recipient's country changes`() {
        val de19 = rate().copy(code = "DE_STANDARD_19", category = "S")
        val item = itemWithTaxCategory(de19.category)

        val recipientQueryPort = mock<RecipientQueryPort>()
        stubRecipient(
            recipientQueryPort, 1L, customerType = CustomerType.BUSINESS, verificationStatus = VatIdVerificationStatus.VERIFIED, vatId = "DE222222222",
        )
        val requestBefore = buildTaxRuleResolutionRequest(recipientQueryPort, invoiceWithRecipient(), today = FIXED_TODAY)
        val resolutionBefore = TaxRuleResolver.resolve(requestBefore!!, today = FIXED_TODAY)
        assertFalse(isProposalStale(item, resolutionBefore), "same recipient context — the just-applied rule must not read as stale")

        // Recipient moves to France — re-resolving now proposes Reverse Charge instead of DE_STANDARD_19.
        val recipientQueryPortAfter = mock<RecipientQueryPort>()
        stubRecipient(recipientQueryPortAfter, 1L, customerType = CustomerType.BUSINESS, verificationStatus = VatIdVerificationStatus.VERIFIED)
        val requestAfter = buildTaxRuleResolutionRequest(recipientQueryPortAfter, invoiceWithRecipient(), today = FIXED_TODAY)
        val resolutionAfter = TaxRuleResolver.resolve(requestAfter!!, today = FIXED_TODAY)
        assertTrue(isProposalStale(item, resolutionAfter), "recipient country changed — the previously applied rule must now read as stale")
    }

    // Article vs. recipient context: orthogonal dimensions. Tax category (S/AE/E/O) follows
    // recipient; rate within S (19%/7%) follows invoice item. Article selection must not override
    // resolver suggestion unconditionally.

    private val de19 = rate().copy(code = "DE_STANDARD_19", category = "S", rate = TaxRatePercent(BigDecimal("19.00")))
    private val de7 = rate().copy(code = "DE_REDUCED_7", category = "S", rate = TaxRatePercent(BigDecimal("7.00")))
    private val rcAe = rate().copy(code = "EU_B2B_RC_SERVICE", category = "AE", rate = TaxRatePercent(BigDecimal.ZERO), exemptionReasonCode = "VATEX-EU-AE", exemptionReasonText = "Reverse Charge")
    private val exemptE = rate().copy(code = "DE_EXEMPT_SERVICE", category = "E", rate = TaxRatePercent(BigDecimal.ZERO), exemptionReasonText = "Steuerfrei")

    private fun proposed(rule: String, category: VatCategory, rate: String) =
        TaxRuleResolution.Proposed(rule, category, BigDecimal(rate))

    @Test
    fun `a 19 percent article can no longer cancel a Reverse Charge proposal`() {
        val effective = effectiveTaxRuleFor(
            InvoiceItemCategory.SERVICE,
            proposed("EU_B2B_RC_SERVICE", VatCategory.REVERSE_CHARGE, "0.00"),
            TaxRateCandidates(proposed = rcAe, articleDefault = de19, fallback = de19),
        )
        assertEquals(rcAe, effective, "recipient context (AE) must win over the article's 19 % default")
    }

    @Test
    fun `a reduced-rate article survives a domestic standard-rate proposal`() {
        // Resolver proposes 19% for domestic; if it won unconditionally, 7% would be unreachable.
        val effective = effectiveTaxRuleFor(
            InvoiceItemCategory.SERVICE,
            proposed("DE_STANDARD_19", VatCategory.STANDARD, "19.00"),
            TaxRateCandidates(proposed = de19, articleDefault = de7, fallback = de19),
        )
        assertEquals(de7, effective, "within category S the article decides 19 % vs. 7 %")
    }

    @Test
    fun `goods keep the article default because the resolver never resolves a Warenfall`() {
        // For goods, the resolver always blocks (UNSUPPORTED); field must not be cleared.
        val blocked = TaxRuleResolution.Blocked(
            de.littletrotter.shared.tax.TaxRuleResolutionError.UNSUPPORTED_TAX_CASE, "Warenfall",
        )
        val effective = effectiveTaxRuleFor(
            InvoiceItemCategory.PRODUCT, blocked, TaxRateCandidates(proposed = null, articleDefault = de7, fallback = de19),
        )
        assertEquals(de7, effective)
    }

    @Test
    fun `a genuinely blocked service case yields no preselection at all`() {
        val blocked = TaxRuleResolution.Blocked(
            de.littletrotter.shared.tax.TaxRuleResolutionError.BUYER_VAT_ID_NOT_VERIFIED, "nicht bestätigt",
        )
        assertNull(
            effectiveTaxRuleFor(InvoiceItemCategory.SERVICE, blocked, TaxRateCandidates(proposed = null, articleDefault = de19, fallback = de19)),
            "no silent default when R5 blocks a case it should have resolved",
        )
    }

    @Test
    fun `without a proposal the article default still applies`() {
        val effective = effectiveTaxRuleFor(
            InvoiceItemCategory.SERVICE, resolution = null, TaxRateCandidates(proposed = null, articleDefault = de7, fallback = de19),
        )
        assertEquals(de7, effective)
    }

    // ── Auswahlfilter je Positionsart ─────────────────────────────────────────

    @Test
    fun `goods are not offered the service-only EU and exemption rules`() {
        val offered = selectableTaxRatesFor(InvoiceItemCategory.PRODUCT, listOf(de19, de7, rcAe, exemptE), date = FIXED_TODAY)
        assertEquals(listOf(de19, de7), offered, "a product must not be offered Reverse Charge or a service exemption")
    }

    @Test
    fun `a service is offered the full rule set`() {
        val offered = selectableTaxRatesFor(InvoiceItemCategory.SERVICE, listOf(de19, de7, rcAe, exemptE), date = FIXED_TODAY)
        assertEquals(4, offered.size)
    }

    @Test
    fun `a legitimate reduced-rate position is not flagged stale on a domestic invoice`() {
        // Regressionsschutz: isProposalStale verglich Regel-Codes, wodurch jede korrekte
        // 7-%-Position gegen den DE_STANDARD_19-Vorschlag als "veraltet" galt.
        val item = itemWithTaxCategory(de7.category)
        val resolution = proposed("DE_STANDARD_19", VatCategory.STANDARD, "19.00")
        assertFalse(isProposalStale(item, resolution), "7 % is category S, same as the proposal — not stale")
    }

    @Test
    fun `a standard-rate position is flagged stale when the context calls for Reverse Charge`() {
        val item = itemWithTaxCategory(de19.category)
        val resolution = proposed("EU_B2B_RC_SERVICE", VatCategory.REVERSE_CHARGE, "0.00")
        assertTrue(isProposalStale(item, resolution))
    }

    // ── VAT: resolveSellerVatRegistrationCountryFromDto — its full acceptance table, plus the
    // defensive cases called out alongside it. Full branch coverage:
    // the function has exactly two decision points (recipientCountry == null, then whether any
    // registration matches it) and every test below exercises one distinct combination.

    private fun senderVatRegistration(country: Country, vatId: String): SellerVatRegistrationDto =
        SellerVatRegistrationDto(countryAlpha2 = country.alpha2, vatId = vatId)

    @Test
    fun `VAT table row - a home DE recipient resolves to empty regardless of the seller's registrations`() {
        val registrations = listOf(senderVatRegistration(Country.LU, "LU12345678"))
        assertNull(resolveSellerVatRegistrationCountryFromDto(registrations, Country.DE))
    }

    @Test
    fun `VAT table row - a LU recipient with a matching LU registration resolves to LU`() {
        val lu = senderVatRegistration(Country.LU, "LU12345678")
        val registrations = listOf(lu)
        assertEquals(Country.LU, resolveSellerVatRegistrationCountryFromDto(registrations, Country.LU))
    }

    @Test
    fun `VAT table row - a LU recipient without a matching LU registration resolves to empty`() {
        val registrations = listOf(senderVatRegistration(Country.FR, "FR12345678901"))
        assertNull(resolveSellerVatRegistrationCountryFromDto(registrations, Country.LU))
    }

    @Test
    fun `VAT table row - a FR recipient with a matching FR registration resolves to FR`() {
        val fr = senderVatRegistration(Country.FR, "FR12345678901")
        val registrations = listOf(fr)
        assertEquals(Country.FR, resolveSellerVatRegistrationCountryFromDto(registrations, Country.FR))
    }

    @Test
    fun `VAT - a seller with no registrations at all resolves to empty for any recipient country`() {
        assertNull(resolveSellerVatRegistrationCountryFromDto(emptyList(), Country.LU))
    }

    @Test
    fun `VAT - a recipient without a billing address (null country) resolves to empty even with a matching registration`() {
        val registrations = listOf(senderVatRegistration(Country.LU, "LU12345678"))
        assertNull(resolveSellerVatRegistrationCountryFromDto(registrations, null))
    }

    @Test
    fun `VAT - two registrations sharing a country do not crash and yield the first match`() {
        // Must not exist in real data (SellerVatRegistration.assertUniqueCountries, DB unique
        // index) — this is a defensive test of resolveSellerVatRegistrationCountry itself, not a
        // claim that the domain ever produces such a list.
        val first = senderVatRegistration(Country.LU, "LU12345678")
        val second = senderVatRegistration(Country.LU, "LU87654321")
        assertEquals(Country.LU, resolveSellerVatRegistrationCountryFromDto(listOf(first, second), Country.LU))
    }
}
