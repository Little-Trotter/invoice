package de.littletrotter.shared.tax

import de.littletrotter.shared.country.Country
import java.math.BigDecimal
import java.time.LocalDate

/** R5 — the kind of supply this position represents. The resolver only ever proposes a rule
 *  for [GENERAL_SERVICE]; everything else is [UNSUPPORTED] by construction (Umsetzung §6:
 *  "Warenfall ... Fehler UNSUPPORTED_TAX_CASE, niemals stiller Default"). */
enum class ServiceType { GENERAL_SERVICE, UNSUPPORTED }

/** R5, Entscheidungstabelle §5 — an explicit, manually chosen exemption. Requires both a reason
 *  text and a legal basis; the resolver never infers either. */
data class ExplicitExemption(
    val ruleCode: String,
    val category: VatCategory,
    val reasonText: String,
    val legalBasis: String,
)

/**
 * R5 — everything the resolver needs. A plain data holder, not tied to
 * any invoice or quote aggregate; a caller builds it from the party fields it already carries.
 */
data class TaxRuleResolutionRequest(
    val serviceType: ServiceType,
    val serviceDate: LocalDate,
    val sellerCountry: Country,
    val buyerCountry: Country,
    val customerType: CustomerType,
    val sellerVatId: String?,
    val buyerVatId: String?,
    val buyerVatIdVerificationStatus: VatIdVerificationStatus?,
    val explicitSpecialPlaceOfSupply: Boolean = false,
    val explicitExemption: ExplicitExemption? = null,
    /** The invoice's seller VAT registration country at confirmation time, carried here only so
     *  [isStaleFor] can detect a later switch; [TaxRuleResolver.resolve] itself never reads
     *  this field — R5's decision table stays DE-seller-only and unchanged (this is a second,
     *  independent path next to R5, not an extension of it). */
    val sellerVatRegistrationCountry: Country? = null,
)

/** R5 — why the resolver blocked instead of proposing a rule. Never a silent default: no
 *  unsupported case may fall through to a fallback rule unnoticed. */
enum class TaxRuleResolutionError {
    SELLER_COUNTRY_UNSUPPORTED,
    UNSUPPORTED_TAX_CASE,
    EXEMPTION_REASON_MISSING,
    VAT_ID_INVALID,
    BUYER_VAT_ID_NOT_VERIFIED,
}

sealed class TaxRuleResolution {
    data class Proposed(val ruleCode: String, val category: VatCategory, val rate: BigDecimal) : TaxRuleResolution()
    data class Blocked(val error: TaxRuleResolutionError, val detail: String) : TaxRuleResolution()
}

/**
 * R5 — a deterministic tax rule resolver: proposes a rule from country/customer type/VAT-id
 * proof/service type/service date, or blocks with a reason (Ziel: "blockiert, statt still zu
 * raten"). Pure and stateless — every branch of the Entscheidungstabelle is a single `return`.
 */
object TaxRuleResolver {

    // §5 — an explicit exemption short-circuits everything else (except the prerequisite gate),
    // but only when it's actually complete (Grund + Rechtsgrundlage), never as a half-filled
    // guess; §2 — a domestic recipient always gets the plain standard rate. Chained via `?:`
    // instead of a sequence of early returns (ReturnCount) — every branch of the
    // Entscheidungstabelle is still a single expression, just not a single `return` statement.
    fun resolve(request: TaxRuleResolutionRequest, today: LocalDate): TaxRuleResolution =
        validatePrerequisites(request)
            ?: request.explicitExemption?.let(::resolveExplicitExemption)
            ?: domesticResolution(request)
            ?: resolveEuAbroad(request, today)

    private fun resolveExplicitExemption(exemption: ExplicitExemption): TaxRuleResolution =
        if (exemption.reasonText.isBlank() || exemption.legalBasis.isBlank()) {
            TaxRuleResolution.Blocked(
                TaxRuleResolutionError.EXEMPTION_REASON_MISSING,
                "Eine Steuerbefreiung/Nicht-Steuerbarkeit braucht einen Grund und eine Rechtsgrundlage.",
            )
        } else {
            TaxRuleResolution.Proposed(exemption.ruleCode, exemption.category, BigDecimal.ZERO)
        }

    private fun domesticResolution(request: TaxRuleResolutionRequest): TaxRuleResolution.Proposed? =
        if (request.buyerCountry == Country.DE) {
            TaxRuleResolution.Proposed("DE_STANDARD_19", VatCategory.STANDARD, BigDecimal("19.00"))
        } else {
            null
        }

    private fun validatePrerequisites(request: TaxRuleResolutionRequest): TaxRuleResolution.Blocked? {
        // §1 — only a German seller is supported at all.
        if (request.sellerCountry != Country.DE) {
            return TaxRuleResolution.Blocked(
                TaxRuleResolutionError.SELLER_COUNTRY_UNSUPPORTED,
                "Nur Verkäufer mit Sitz in Deutschland werden unterstützt (Verkäuferland: ${request.sellerCountry}).",
            )
        }
        if (request.serviceType != ServiceType.GENERAL_SERVICE) {
            return TaxRuleResolution.Blocked(
                TaxRuleResolutionError.UNSUPPORTED_TAX_CASE,
                "Nur allgemeine Dienstleistungen werden automatisch aufgelöst (z.B. keine Warenlieferung).",
            )
        }
        if (request.explicitSpecialPlaceOfSupply) {
            return TaxRuleResolution.Blocked(
                TaxRuleResolutionError.UNSUPPORTED_TAX_CASE,
                "Eine markierte Sonderortsregel wird nicht automatisch aufgelöst.",
            )
        }
        return null
    }

    private fun resolveEuAbroad(request: TaxRuleResolutionRequest, today: LocalDate): TaxRuleResolution {
        // §3/§4/§6 — EU abroad, gated by R1's time-relative EU membership and the approved
        // target-country allowlist (Country.supportedEuB2bTargets) — GB is deliberately absent
        // from that list regardless of date, but the *reason* given differs by date.
        // supportedEuB2bTargets is deliberately called with [today] (the real calendar date the
        // caller's TimeSource reports), not request.serviceDate: GB was still a genuine EU member
        // on an old, pre-Brexit service date, so a date-relative allowlist would wrongly re-admit
        // it for historical invoices — the allowlist is a standing release decision (R1), not
        // itself a membership check; only isEuMemberOn below is meant to move with the invoice's
        // own date.
        val isEuMemberNow = request.buyerCountry.isEuMemberOn(request.serviceDate)
        val isApprovedTarget = request.buyerCountry in Country.supportedEuB2bTargets(today)
        if (!isApprovedTarget || !isEuMemberNow) {
            val detail = if (isEuMemberNow)
                "${request.buyerCountry} ist zum Leistungsdatum EU-Mitglied, steht aber nicht auf der freigegebenen Zielländerliste."
            else
                "${request.buyerCountry} ist zum Leistungsdatum ${request.serviceDate} kein EU-Mitglied (Drittland)."
            return TaxRuleResolution.Blocked(TaxRuleResolutionError.UNSUPPORTED_TAX_CASE, detail)
        }

        return when (request.customerType) {
            CustomerType.BUSINESS -> {
                val crossCheck = VatIdCrossCheck.validate(request.sellerVatId, request.buyerVatId, request.buyerCountry)
                if (crossCheck is VatIdCrossCheckResult.Invalid) {
                    TaxRuleResolution.Blocked(TaxRuleResolutionError.VAT_ID_INVALID, crossCheck.reason)
                } else if (request.buyerVatIdVerificationStatus != VatIdVerificationStatus.VERIFIED) {
                    // §3 — "belastbarer USt-Id-Nachweis" means VERIFIED, not merely present
                    // (an unverified id must never silently become Reverse Charge).
                    TaxRuleResolution.Blocked(
                        TaxRuleResolutionError.BUYER_VAT_ID_NOT_VERIFIED,
                        "Käufer-USt-IdNr. ist nicht als gültig bestätigt (Status: ${request.buyerVatIdVerificationStatus ?: "unbekannt"}).",
                    )
                } else {
                    TaxRuleResolution.Proposed("EU_B2B_RC_SERVICE", VatCategory.REVERSE_CHARGE, BigDecimal.ZERO)
                }
            }
            CustomerType.CONSUMER ->
                // §4 — always the German standard rate, never the buyer country's local rate.
                TaxRuleResolution.Proposed("EU_B2C_DE_STANDARD_19", VatCategory.STANDARD, BigDecimal("19.00"))
        }
    }
}
