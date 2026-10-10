package de.littletrotter.domain.reference

import de.littletrotter.shared.tax.TaxRuleCode
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.b2b.codes.B2bCodeList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VatCategoryAndTaxRuleCodeTest {

    @Test
    fun `fromCode maps every known UNTDID 5305 letter code`() {
        assertEquals(VatCategory.STANDARD, VatCategories.parse("S"))
        assertEquals(VatCategory.ZERO_RATED, VatCategories.parse("Z"))
        assertEquals(VatCategory.EXEMPT, VatCategories.parse("E"))
        assertEquals(VatCategory.REVERSE_CHARGE, VatCategories.parse("AE"))
        assertEquals(VatCategory.OUTSIDE_SCOPE, VatCategories.parse("O"))
        assertEquals(VatCategory.FREE_EXPORT_ITEM, VatCategories.parse("G"))
        assertEquals(VatCategory.INTRA_COMMUNITY_SUPPLY, VatCategories.parse("K"))
    }

    @Test
    fun `fromCode rejects an unknown code`() {
        assertThrows(InvalidParameterException::class.java) { VatCategories.parse("X") }
    }

    @Test
    fun `fromCodeOrNull returns null for a null or unknown code`() {
        assertNull(VatCategory.fromCodeOrNull(null))
        assertNull(VatCategory.fromCodeOrNull("X"))
    }

    @Test
    fun `TaxRuleCode rejects a blank value`() {
        assertThrows(InvalidParameterException::class.java) { TaxRuleCodes.parse("") }
        assertThrows(InvalidParameterException::class.java) { TaxRuleCodes.parse("   ") }
    }

    @Test
    fun `TaxRuleCode wraps a non-blank value and round-trips via toString`() {
        val code = TaxRuleCodes.parse("DE_STANDARD_19")
        assertEquals("DE_STANDARD_19", code.value)
        assertEquals("DE_STANDARD_19", code.toString())
    }

    // ─── Pinning against B2bCodeList ──────────────────────────────────────────────────────────

    /**
     * Audit target: "VatCategory vs UNTDID 5305: currently 7 of 9 official codes — assert
     * exactly that subset and document L (Canary Islands IGIC) and M (Ceuta/Melilla IPSI) as
     * deliberately unsupported." UNTDID 5305/EN16931 defines 9 category codes
     * ([B2bCodeList.VAT_CATEGORY_5305]); [VatCategory] models exactly 7 of them today (see its
     * own KDoc: only S/AE/E/O are DB-CHECK-accepted, Z/G/K are modeled ahead of that migration).
     * A codedb update surfaces here instead of drifting silently, per the audit's intent.
     */
    @Test
    fun `VatCategory models exactly 7 of the 9 official UNTDID 5305 codes, L and M deliberately unsupported`() {
        val modeled = VatCategory.entries.map { it.code }.toSet()
        assertEquals(setOf("S", "Z", "E", "AE", "O", "G", "K"), modeled)
        assertEquals(7, modeled.size)

        val official = B2bCodeList.VAT_CATEGORY_5305.values()
        assertEquals(9, official.size, "UNTDID 5305/EN16931 defines 9 category codes — a codedb change moved this")
        assertTrue(modeled.all { it in official }, "every modeled VatCategory code must remain an official one")

        val deliberatelyUnsupported = official - modeled
        assertEquals(
            setOf("L", "M"), deliberatelyUnsupported,
            "L (Canary Islands IGIC) and M (Ceuta/Melilla IPSI) are the two deliberately unsupported codes; " +
                "a different gap here means either VatCategory or the codedb moved",
        )
    }

    /**
     * Audit target: "TaxRuleCode (plain string wrapper): check whether its stored values are
     * VATEX codes — if so, pin against the VATEX list; report the finding either way." Finding:
     * [TaxRuleCode] wraps [TaxRate.code]/`InvoiceItem.taxRuleCode` — a stable, internal rule
     * identifier (e.g. `"DE_STANDARD_19"`, see the previous test), **not** a VATEX code. The
     * actual VATEX-code-bearing field is the separate, untyped
     * [TaxRate.exemptionReasonCode]/`InvoiceItem.exemptionReasonCode` `String?` column (comment:
     * "z. B. VATEX-EU-AE") — this test pins that field's one DB-CHECK-enforced literal
     * (`chk_tax_rate_category_values`-adjacent CHECK: category 'AE' implies exemptionReasonCode
     * 'VATEX-EU-AE', `V1__baseline_schema.sql`) against [B2bCodeList.VATEX_EXEMPTION_REASON]
     * instead — no pinning test against [TaxRuleCode] itself would exercise anything meaningful.
     */
    @Test
    fun `the DB-pinned exemption reason code 'VATEX-EU-AE' is a valid VATEX code`() {
        assertTrue(
            B2bCodeList.VATEX_EXEMPTION_REASON.contains("VATEX-EU-AE"),
            "the DB CHECK-pinned exemption reason 'VATEX-EU-AE' (category AE) must stay a member of " +
                "B2bCodeList.VATEX_EXEMPTION_REASON",
        )
    }
}
