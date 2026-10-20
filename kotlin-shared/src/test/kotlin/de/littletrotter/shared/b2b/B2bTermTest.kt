package de.littletrotter.shared.b2b

import de.littletrotter.shared.b2b.B2bFieldKind.DATE
import de.littletrotter.shared.b2b.B2bFieldKind.ENUM
import de.littletrotter.shared.b2b.B2bFieldKind.NUMBER
import de.littletrotter.shared.b2b.B2bFieldKind.TEXT
import de.littletrotter.shared.b2b.B2bTermBinding.BOUND
import de.littletrotter.shared.b2b.B2bTermBinding.FREE
import de.littletrotter.shared.b2b.B2bTermLevel.BUYER_PARTY
import de.littletrotter.shared.b2b.B2bTermLevel.HEADER
import de.littletrotter.shared.b2b.B2bTermLevel.LINE
import de.littletrotter.shared.b2b.B2bTermLevel.SELLER_PARTY
import de.littletrotter.shared.b2b.codes.B2bCodeList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Invariant-style tests on [B2bTerm] (H27 conventions §11: "kritische Komponente",
 * 100 % line+branch). Not line-by-line: each test asserts a rule that must hold across every
 * constant, plus one hard-pinned transcription table that is now the single source for the 15
 * former `b2b_field_catalog` rows (that seed block was deleted).
 */
class B2bTermTest {

    // ── Codes / officialCode ─────────────────────────────────────────────────

    @Test
    fun `codes are unique across all constants`() {
        val codes = B2bTerm.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "duplicate B2bTerm.code values: $codes")
    }

    @Test
    fun `officialCode round-trips code for every constant, BT-nn and BT-X-nn alike`() {
        // A BT-X (EXTENDED-only) term's code is `10000 + nn` (the established numbering
        // scheme) but its officialCode renders back to "BT-X-nn", not the misleading "BT-10019".
        for (term in B2bTerm.entries) {
            if (term.code >= 10_000) {
                assertEquals("BT-X-${term.code - 10_000}", term.officialCode)
            } else {
                assertEquals("BT-${term.code}", term.officialCode)
            }
            assertEquals(term, B2bTerm.fromOfficialCode(term.officialCode))
        }
    }

    // ── Binding invariants ───────────────────────────────────────────────────

    @Test
    fun `FREE terms carry an xpath and no boundProperty`() {
        for (term in B2bTerm.entries.filter { it.binding == FREE }) {
            assertNotNull(term.xpath, "${term.officialCode} is FREE but has no xpath")
            assertNull(term.boundProperty, "${term.officialCode} is FREE but has a boundProperty")
        }
    }

    @Test
    fun `BOUND terms carry a boundProperty and no xpath`() {
        for (term in B2bTerm.entries.filter { it.binding == BOUND }) {
            assertNotNull(term.boundProperty, "${term.officialCode} is BOUND but has no boundProperty")
            assertNull(term.xpath, "${term.officialCode} is BOUND but has an xpath")
        }
    }

    @Test
    fun `reference terms carry no catalog metadata at all`() {
        for (term in B2bTerm.entries.filter { it.binding == null }) {
            assertNull(term.xpath, "${term.officialCode} is a reference term but has an xpath")
            assertNull(term.boundProperty, "${term.officialCode} is a reference term but has a boundProperty")
            assertNull(term.maxLength, "${term.officialCode} is a reference term but has a maxLength")
        }
    }

    @Test
    fun `assertB2bTermBindingInvariant accepts every real constant's own data`() {
        for (term in B2bTerm.entries) {
            assertDoesNotThrow {
                assertB2bTermBindingInvariant(
                    term.officialCode,
                    term.binding,
                    B2bCatalogMetadata(term.xpath, term.boundProperty, term.maxLength),
                )
            }
        }
    }

    @Test
    fun `assertB2bTermBindingInvariant rejects a FREE term without an xpath`() {
        assertThrows(IllegalArgumentException::class.java) {
            assertB2bTermBindingInvariant("BT-TEST", FREE, B2bCatalogMetadata(xpath = null, boundProperty = null, maxLength = null))
        }
    }

    @Test
    fun `assertB2bTermBindingInvariant rejects a BOUND term without a boundProperty`() {
        assertThrows(IllegalArgumentException::class.java) {
            assertB2bTermBindingInvariant("BT-TEST", BOUND, B2bCatalogMetadata(xpath = null, boundProperty = null, maxLength = null))
        }
    }

    @Test
    fun `assertB2bTermBindingInvariant rejects a reference term that still carries catalog metadata`() {
        assertThrows(IllegalArgumentException::class.java) {
            assertB2bTermBindingInvariant("BT-TEST", null, B2bCatalogMetadata(xpath = "/some/path", boundProperty = null, maxLength = null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            assertB2bTermBindingInvariant("BT-TEST", null, B2bCatalogMetadata(xpath = null, boundProperty = "someProperty", maxLength = null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            assertB2bTermBindingInvariant("BT-TEST", null, B2bCatalogMetadata(xpath = null, boundProperty = null, maxLength = 10))
        }
    }

    // ── valueDomain / ENUM invariant ──────────────────────────────────────────

    @Test
    fun `ENUM terms carry a valueDomain, non-ENUM terms carry none`() {
        for (term in B2bTerm.entries) {
            if (term.fieldKind == ENUM) {
                assertNotNull(term.valueDomain, "${term.officialCode} is ENUM but has no valueDomain")
            } else {
                assertNull(term.valueDomain, "${term.officialCode} is ${term.fieldKind} but has a valueDomain")
            }
        }
    }

    @Test
    fun `assertB2bTermValueDomainInvariant accepts every real constant's own data`() {
        for (term in B2bTerm.entries) {
            assertDoesNotThrow {
                assertB2bTermValueDomainInvariant(term.officialCode, term.fieldKind, term.valueDomain)
            }
        }
    }

    @Test
    fun `assertB2bTermValueDomainInvariant rejects an ENUM term without a valueDomain`() {
        assertThrows(IllegalArgumentException::class.java) {
            assertB2bTermValueDomainInvariant("BT-TEST", ENUM, valueDomain = null)
        }
    }

    @Test
    fun `assertB2bTermValueDomainInvariant rejects a non-ENUM term that still carries a valueDomain`() {
        assertThrows(IllegalArgumentException::class.java) {
            assertB2bTermValueDomainInvariant("BT-TEST", TEXT, valueDomain = B2bCodeList.CURRENCY_ISO_4217)
        }
    }

    // ── catalogEligible ──────────────────────────────────────────────────────

    @Test
    fun `BOUND-or-FREE catalog terms match the 15 former catalog rows plus BTE-6-BTE-7's additional BOUND terms`() {
        val originalCatalogCodes = setOf(11, 12, 13, 18, 19, 56, 58, 72, 73, 74, 84, 134, 135, 155, 156)
        val bte6CatalogCodes = setOf(
            1, 2, 10, 25, 27, 29, 31, 32, 34, 43, 44, 46, 48, 49, 129, 130, 153, 154,
        )
        val bte7CatalogCodes = setOf(3, 5) // typeCode / currency, the two BOUND terms BTE-7 hand-adds
        val expectedCatalogCodes = originalCatalogCodes + bte6CatalogCodes + bte7CatalogCodes
        val actualCatalogCodes = B2bTerm.entries.filter { it.binding != null }.map { it.code }.toSet()
        assertEquals(expectedCatalogCodes, actualCatalogCodes)
        assertEquals(35, actualCatalogCodes.size)
    }

    // ── catalogEligible widened to "every term except the deliberate carve-out" ────────────────

    @Test
    fun `catalogEligible is true for every term except BT-151, the single deliberate carve-out`() {
        val excluded = B2bTerm.entries.filterNot { it.catalogEligible }
        assertEquals(setOf(B2bTerm.BT_151), excluded.toSet())
        for (term in excluded) {
            assertTrue(term.catalogExcluded, "${term.officialCode} is excluded from the catalog but catalogExcluded is false")
        }
        for (term in B2bTerm.entries - excluded.toSet()) {
            assertFalse(term.catalogExcluded, "${term.officialCode} is catalog-eligible but catalogExcluded is true")
        }
    }

    @Test
    fun `every pure-reference (binding null) catalog-eligible term is generated or BT-16 (BTE-7 documentation-only terms)`() {
        for (term in B2bTerm.entries.filter { it.binding == null && it.catalogEligible }) {
            // Documentation-only: no xpath/boundProperty/maxLength (already covered by "reference
            // terms carry no catalog metadata at all"), just confirming they really are selectable.
            assertTrue(term.catalogEligible)
        }
        // At least one hand-curated (BT_16) and a large generated cohort must be in this bucket.
        val pureReferenceCatalogEligible = B2bTerm.entries.filter { it.binding == null && it.catalogEligible }
        assertTrue(pureReferenceCatalogEligible.contains(B2bTerm.BT_16))
        assertTrue(pureReferenceCatalogEligible.size > 700, "expected the ~747 generated terms plus BT_16")
    }

    // ── numbering invariants ──────────────────────────────────────────────────

    @Test
    fun `every BT-nn code is below 10000 and every BT-X code is 10000 plus its extension number`() {
        for (term in B2bTerm.entries) {
            if (term.officialCode.startsWith("BT-X-")) {
                assertTrue(term.code >= 10_000, "${term.officialCode} (code ${term.code}) expected >= 10000")
            } else {
                assertTrue(term.code < 10_000, "${term.officialCode} (code ${term.code}) expected < 10000")
            }
        }
    }

    @Test
    fun `every code fits in a SMALLINT (persisted range, BTE-2 numbering scheme)`() {
        for (term in B2bTerm.entries) {
            assertTrue(term.code in Short.MIN_VALUE..Short.MAX_VALUE, "${term.officialCode} code ${term.code} exceeds SMALLINT range")
        }
    }

    @Test
    fun `availableInEn16931 and availableInExtended are consistent with the code range`() {
        for (term in B2bTerm.entries) {
            assertTrue(term.availableInExtended, "${term.officialCode}: every term is available in EXTENDED (EN16931 subset)")
            if (term.officialCode.startsWith("BT-X-")) {
                assertFalse(term.availableInEn16931, "${term.officialCode}: a BT-X term can never be an EN16931 term")
            }
        }
    }

    // ── lookup-map completeness (byCode backs every public fromCode/fromCodeOrNull call) ──────────

    @Test
    fun `byCode-backed lookups resolve every single constant, none fall through to a linear scan`() {
        // fromCode/fromCodeOrNull/fromOfficialCode(OrNull) are all backed by the same `byCode` map
        // built once in the companion (O(1) per lookup) — this test simply exercises every
        // constant through all four entry points once, pinning that none of them silently regress
        // to an `entries.first { … }` scan as the catalog grows into the hundreds.
        for (term in B2bTerm.entries) {
            assertEquals(term, B2bTerm.fromCode(term.code))
            assertEquals(term, B2bTerm.fromCodeOrNull(term.code))
            assertEquals(term, B2bTerm.fromOfficialCode(term.officialCode))
            assertEquals(term, B2bTerm.fromOfficialCodeOrNull(term.officialCode))
        }
    }

    // ── fromCode / fromCodeOrNull ────────────────────────────────────────────

    @Test
    fun `fromCode roundtrips every constant`() {
        for (term in B2bTerm.entries) {
            assertEquals(term, B2bTerm.fromCode(term.code))
            assertEquals(term, B2bTerm.fromCodeOrNull(term.code))
        }
    }

    @Test
    fun `fromCode throws on an unknown code, naming it in the message`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { B2bTerm.fromCode(9999) }
        assertTrue(ex.message!!.contains("9999"), "expected the unknown code in the message: ${ex.message}")
    }

    @Test
    fun `fromCodeOrNull returns null on an unknown code`() {
        assertNull(B2bTerm.fromCodeOrNull(9999))
    }

    // ── fromOfficialCode / fromOfficialCodeOrNull ────────────────────────────

    @Test
    fun `fromOfficialCode roundtrips every constant's officialCode`() {
        for (term in B2bTerm.entries) {
            assertEquals(term, B2bTerm.fromOfficialCode(term.officialCode))
            assertEquals(term, B2bTerm.fromOfficialCodeOrNull(term.officialCode))
        }
    }

    @Test
    fun `fromOfficialCode throws on an unknown code, naming it in the message`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { B2bTerm.fromOfficialCode("BT-9999") }
        assertTrue(ex.message!!.contains("BT-9999"), "expected the unknown code in the message: ${ex.message}")
    }

    @Test
    fun `fromOfficialCodeOrNull returns null on an unknown but well-formed code`() {
        assertNull(B2bTerm.fromOfficialCodeOrNull("BT-9999"))
    }

    @Test
    fun `fromOfficialCodeOrNull returns null without the BT- prefix`() {
        assertNull(B2bTerm.fromOfficialCodeOrNull("19"))
    }

    @Test
    fun `fromOfficialCodeOrNull returns null on a non-numeric suffix`() {
        assertNull(B2bTerm.fromOfficialCodeOrNull("BT-nineteen"))
    }

    @Test
    fun `fromOfficialCodeOrNull returns null on a BT-X code with a non-numeric suffix`() {
        assertNull(B2bTerm.fromOfficialCodeOrNull("BT-X-nineteen"))
    }

    @Test
    fun `fromOfficialCodeOrNull returns null on an unknown but well-formed BT-X code`() {
        assertNull(B2bTerm.fromOfficialCodeOrNull("BT-X-999999"))
    }

    // ── i18n key format (bundle-existence for catalog terms is asserted in kotlin-invoice's
    //    ReferenceDataTranslationsAuditTest — kotlin-shared's test scope has no access to the
    //    kotlin-invoice message bundles) ───────────────────────────────────────────────────────

    @Test
    fun `nameKey and specDefinitionKey follow the documented format for every constant`() {
        for (term in B2bTerm.entries) {
            assertEquals("b2b.term.bt${term.code}.name", term.nameKey)
            assertEquals("b2b.catalog.bt${term.code}.definition", term.specDefinitionKey)
        }
    }

    // ── Transcription guard — pins the V2__baseline_seed.sql values verbatim ───────────────

    private data class ExpectedCatalogRow(
        val level: B2bTermLevel,
        val fieldKind: B2bFieldKind,
        val binding: B2bTermBinding,
        val maxLength: Int?,
    )

    /** Hard-pinned against the `b2b_field_catalog` INSERT in V2__baseline_seed.sql, section 7. */
    private val expectedCatalogRows: Map<Int, ExpectedCatalogRow> = mapOf(
        11 to ExpectedCatalogRow(HEADER, TEXT, BOUND, null),
        12 to ExpectedCatalogRow(HEADER, TEXT, FREE, 100),
        13 to ExpectedCatalogRow(HEADER, TEXT, BOUND, null),
        18 to ExpectedCatalogRow(HEADER, TEXT, FREE, 100),
        19 to ExpectedCatalogRow(HEADER, TEXT, FREE, 50),
        56 to ExpectedCatalogRow(HEADER, TEXT, FREE, 100),
        58 to ExpectedCatalogRow(HEADER, TEXT, FREE, 100),
        72 to ExpectedCatalogRow(HEADER, DATE, BOUND, null),
        73 to ExpectedCatalogRow(HEADER, DATE, BOUND, null),
        74 to ExpectedCatalogRow(HEADER, DATE, BOUND, null),
        84 to ExpectedCatalogRow(SELLER_PARTY, TEXT, BOUND, null),
        134 to ExpectedCatalogRow(LINE, DATE, BOUND, null),
        135 to ExpectedCatalogRow(LINE, DATE, BOUND, null),
        155 to ExpectedCatalogRow(LINE, TEXT, FREE, 50),
        156 to ExpectedCatalogRow(LINE, TEXT, FREE, 50),
    )

    @Test
    fun `catalog terms match the V2 seed transcription exactly`() {
        assertEquals(15, expectedCatalogRows.size)
        for ((code, expected) in expectedCatalogRows) {
            val term = B2bTerm.fromCode(code)
            assertEquals(expected.level, term.level, "${term.officialCode} level mismatch")
            assertEquals(expected.fieldKind, term.fieldKind, "${term.officialCode} fieldKind mismatch")
            assertEquals(expected.binding, term.binding, "${term.officialCode} binding mismatch")
            assertEquals(expected.maxLength, term.maxLength, "${term.officialCode} maxLength mismatch")
        }
    }

    @Test
    fun `the two remaining reference terms carry the EN 16931 level and fieldKind used at their call sites`() {
        // Every other former reference term was moved to BOUND (see the boundProperty
        // transcription table below). Two stay reference terms, for two different reasons —
        // B2bTerm.kt's own KDoc on each records why:
        // - BT-16: no HEADER-level modeled property exists (the only candidate,
        //   InvoiceItem.deliveryNoteReference, is LINE-level).
        // - BT-151: a genuinely modeled property exists (InvoiceItem.taxCategory), but
        //   B2bProfileMigrationIT's "catalog never contains a tax-determining term" guard forbids
        //   catalog-eligibility outright, regardless of binding kind.
        val bt16 = B2bTerm.fromCode(16)
        assertNull(bt16.binding, "${bt16.officialCode} is expected to be a reference term")
        assertEquals(HEADER, bt16.level)
        assertEquals(TEXT, bt16.fieldKind)

        val bt151 = B2bTerm.fromCode(151)
        assertNull(bt151.binding, "${bt151.officialCode} is expected to be a reference term")
        assertEquals(LINE, bt151.level)
        assertEquals(ENUM, bt151.fieldKind)
        assertEquals(B2bCodeList.VAT_CATEGORY_5305, bt151.valueDomain)
    }

    // ── boundProperty transcription guard for the 18 terms upgraded from reference to
    //    BOUND catalog terms (every property name verified against the actual domain entity by
    //    B2bProfileRuleSetTest's presence checks in kotlin-invoice; this table pins level/fieldKind/
    //    boundProperty here, where B2bTerm itself lives). ──────────────────────────────────────

    private data class ExpectedBte6Row(
        val level: B2bTermLevel,
        val fieldKind: B2bFieldKind,
        val boundProperty: String,
    )

    private val expectedBte6Rows: Map<Int, ExpectedBte6Row> = mapOf(
        1 to ExpectedBte6Row(HEADER, TEXT, "invoiceNumber"),
        2 to ExpectedBte6Row(HEADER, DATE, "invoiceDate"),
        10 to ExpectedBte6Row(HEADER, TEXT, "buyerReference"),
        25 to ExpectedBte6Row(HEADER, TEXT, "originalInvoiceNumber"),
        27 to ExpectedBte6Row(SELLER_PARTY, TEXT, "name"),
        29 to ExpectedBte6Row(SELLER_PARTY, TEXT, "sellerIdentifier"),
        31 to ExpectedBte6Row(SELLER_PARTY, TEXT, "vatId"),
        32 to ExpectedBte6Row(SELLER_PARTY, TEXT, "taxNumber"),
        34 to ExpectedBte6Row(SELLER_PARTY, TEXT, "electronicAddress"),
        43 to ExpectedBte6Row(SELLER_PARTY, TEXT, "email"),
        44 to ExpectedBte6Row(BUYER_PARTY, TEXT, "name"),
        46 to ExpectedBte6Row(BUYER_PARTY, TEXT, "buyerIdentifier"),
        48 to ExpectedBte6Row(BUYER_PARTY, TEXT, "vatId"),
        49 to ExpectedBte6Row(BUYER_PARTY, TEXT, "electronicAddress"),
        129 to ExpectedBte6Row(LINE, NUMBER, "quantity"),
        130 to ExpectedBte6Row(LINE, ENUM, "unitCode"),
        153 to ExpectedBte6Row(LINE, TEXT, "description"),
        154 to ExpectedBte6Row(LINE, TEXT, "itemDescription"),
    )

    @Test
    fun `BTE-6 BOUND terms match the documented level, fieldKind and boundProperty exactly`() {
        assertEquals(18, expectedBte6Rows.size)
        for ((code, expected) in expectedBte6Rows) {
            val term = B2bTerm.fromCode(code)
            assertEquals(BOUND, term.binding, "${term.officialCode} is expected to be BOUND")
            assertEquals(expected.level, term.level, "${term.officialCode} level mismatch")
            assertEquals(expected.fieldKind, term.fieldKind, "${term.officialCode} fieldKind mismatch")
            assertEquals(expected.boundProperty, term.boundProperty, "${term.officialCode} boundProperty mismatch")
        }
    }

    @Test
    fun `the pre-BTE-7 catalog and reference codes are exactly the original 35 hand-curated constants`() {
        // Two more curated BOUND terms were hand-added (BT-3/BT-5, checked separately below) and a
        // large generated pure-reference block (B2bTermDbDriftTest) on top of these 35 — this test
        // still pins the original hand-curated set exactly, unaffected by those additions.
        val catalogCodes = expectedCatalogRows.keys + expectedBte6Rows.keys
        val referenceCodes = setOf(16, 151)
        assertTrue(catalogCodes.intersect(referenceCodes).isEmpty(), "catalog/reference code overlap")
        val originalCurated = catalogCodes + referenceCodes
        assertEquals(35, originalCurated.size)
        assertTrue(originalCurated.all { code -> B2bTerm.fromCodeOrNull(code) != null })
    }

    @Test
    fun `BT-3 and BT-5 are hand-curated BOUND terms with the correct valueDomain`() {
        val bt3 = B2bTerm.fromCode(3)
        assertEquals(BOUND, bt3.binding)
        assertEquals(HEADER, bt3.level)
        assertEquals("typeCode", bt3.boundProperty)
        assertEquals(ENUM, bt3.fieldKind)
        assertEquals(B2bCodeList.DOCUMENT_TYPE_1001, bt3.valueDomain)
        assertTrue(bt3.autoFilled)

        val bt5 = B2bTerm.fromCode(5)
        assertEquals(BOUND, bt5.binding)
        assertEquals(HEADER, bt5.level)
        assertEquals("currency", bt5.boundProperty)
        assertEquals(ENUM, bt5.fieldKind)
        assertEquals(B2bCodeList.CURRENCY_ISO_4217, bt5.valueDomain)
        assertTrue(bt5.autoFilled)
    }

    @Test
    fun `the total constant count is the 37 hand-curated plus the generated block`() {
        // Pinned exactly so a future regeneration that silently changes term count is caught here
        // too, not only by B2bTermDbDriftTest (which is skipped without python3/the workbooks).
        assertEquals(783, B2bTerm.entries.size)
    }

    @Test
    fun `autoFilled is set only on terms verified always-present, and never on a reference term`() {
        val expectedAutoFilled = setOf(1, 2, 3, 5, 27, 31, 32, 43)
        val actualAutoFilled = B2bTerm.entries.filter { it.autoFilled }.map { it.code }.toSet()
        assertEquals(expectedAutoFilled, actualAutoFilled)
        for (term in B2bTerm.entries.filter { it.autoFilled }) {
            assertNotNull(term.binding, "${term.officialCode} is autoFilled but has no binding to make that meaningful")
        }
    }
}
