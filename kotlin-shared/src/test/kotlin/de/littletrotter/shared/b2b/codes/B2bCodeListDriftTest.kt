package de.littletrotter.shared.b2b.codes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One semantically-identified Factur-X code list's `cl` id per profile. Kept in lockstep
 * with `scripts/codegen/facturx_codedb_gen.py`'s `SEMANTIC_LISTS` table by construction (both are derived
 * from the same Schematron-context analysis) — the point of this test is not
 * to re-derive that mapping, it is to catch the generator having been run against a stale codedb
 * copy, or the copy in `src/test/resources/facturx-codedb` having drifted from the generated
 * constants without the generator being re-run.
 */
private data class ClMapping(val list: B2bCodeList, val en16931ClId: String?, val extendedClId: String)

private val CL_MAPPING = listOf(
    ClMapping(B2bCodeList.SPECIFICATION_IDENTIFIER, "1", "1"),
    ClMapping(B2bCodeList.DOCUMENT_TYPE_1001, "2", "2"),
    ClMapping(B2bCodeList.DATE_FORMAT_QUALIFIER, "3", "3"),
    ClMapping(B2bCodeList.NOTE_SUBJECT_CODE_4451, "4", "5"),
    ClMapping(B2bCodeList.ITEM_STANDARD_ID_SCHEME_ICD, "5", "8"),
    ClMapping(B2bCodeList.ITEM_CLASSIFICATION_SCHEME_7143, "6", "9"),
    ClMapping(B2bCodeList.COUNTRY_ISO_3166, "7", "10"),
    ClMapping(B2bCodeList.UNIT_UNECE_REC20, "8", "15"),
    ClMapping(B2bCodeList.TAX_TYPE_CODE_5153, "9", "26"),
    ClMapping(B2bCodeList.VAT_CATEGORY_5305, "10", "24"),
    ClMapping(B2bCodeList.ALLOWANCE_REASON_5189_LINE, "11", "28"),
    ClMapping(B2bCodeList.CHARGE_REASON_7161_LINE, "12", "29"),
    ClMapping(B2bCodeList.REFERENCED_DOC_TYPE_LINE, "13", "18"),
    ClMapping(B2bCodeList.REFERENCE_QUALIFIER_1153, "14", "20"),
    ClMapping(B2bCodeList.PARTY_ID_SCHEME_ICD, "15", "11"),
    ClMapping(B2bCodeList.LEGAL_REG_ID_SCHEME_ICD, "16", "12"),
    ClMapping(B2bCodeList.ELECTRONIC_ADDRESS_SCHEME_EAS, "17", "13"),
    ClMapping(B2bCodeList.TAX_REGISTRATION_SCHEME_BUYER, "18", "34"),
    ClMapping(B2bCodeList.TAX_REGISTRATION_SCHEME_SELLER_TAX_REP, "19", "35"),
    ClMapping(B2bCodeList.SUPPORTING_DOC_TYPE, "20", "38"),
    ClMapping(B2bCodeList.MIME_TYPE, "21", "19"),
    ClMapping(B2bCodeList.TENDER_LOT_DOC_TYPE, "22", "39"),
    ClMapping(B2bCodeList.INVOICE_DATA_SHEET_DOC_TYPE, "23", "40"),
    ClMapping(B2bCodeList.CURRENCY_ISO_4217, "24", "42"),
    ClMapping(B2bCodeList.PAYMENT_MEANS_4461, "25", "43"),
    ClMapping(B2bCodeList.VATEX_EXEMPTION_REASON, "26", "25"),
    ClMapping(B2bCodeList.TAX_POINT_DATE_FORMAT, "27", "44"),
    ClMapping(B2bCodeList.PAYMENT_TERMS_DUE_DATE_TYPE, "28", "27"),
    ClMapping(B2bCodeList.ALLOWANCE_REASON_5189_HEADER, "29", "45"),
    ClMapping(B2bCodeList.CHARGE_REASON_7161_HEADER, "30", "46"),
    ClMapping(B2bCodeList.VAT_TOTAL_CURRENCY_INVOICE, "31", "49"),
    ClMapping(B2bCodeList.VAT_TOTAL_CURRENCY_TAX, "32", "50"),
    ClMapping(B2bCodeList.PRECEDING_INVOICE_DOC_DATE_FORMAT, "33", "17"),
    ClMapping(B2bCodeList.LANGUAGE_ISO_639, null, "4"),
    ClMapping(B2bCodeList.LINE_STATUS_CODE, null, "6"),
    ClMapping(B2bCodeList.LINE_STATUS_REASON_CODE, null, "7"),
    ClMapping(B2bCodeList.ITEM_COMPONENT_ID_SCHEME_ICD, null, "14"),
    ClMapping(B2bCodeList.DELIVERY_TERMS_CODE_LINE, null, "16"),
    ClMapping(B2bCodeList.PRICE_ALLOWANCE_REASON_5189, null, "21"),
    ClMapping(B2bCodeList.PRICE_CHARGE_REASON_ALT_5153, null, "22"),
    ClMapping(B2bCodeList.PRICE_TAX_TYPE_CODE, null, "23"),
    ClMapping(B2bCodeList.LINE_CHARGE_REASON_ALT_5153, null, "30"),
    ClMapping(B2bCodeList.LINE_TAX_TOTAL_CURRENCY_INVOICE, null, "31"),
    ClMapping(B2bCodeList.LINE_TAX_TOTAL_CURRENCY_TAX, null, "32"),
    ClMapping(B2bCodeList.PRECEDING_INVOICE_LINE_DOC_TYPE, null, "33"),
    ClMapping(B2bCodeList.DELIVERY_TERMS_CODE_HEADER, null, "36"),
    ClMapping(B2bCodeList.CONTRACT_REFERENCE_QUALIFIER_1153, null, "37"),
    ClMapping(B2bCodeList.TRANSPORT_MODE_CODE, null, "41"),
    ClMapping(B2bCodeList.HEADER_CHARGE_REASON_ALT_5153, null, "47"),
    ClMapping(B2bCodeList.LOGISTICS_CHARGE_VAT_EXEMPTION_REASON, null, "48"),
    ClMapping(B2bCodeList.PRECEDING_INVOICE_HEADER_DOC_TYPE, null, "51"),
    ClMapping(B2bCodeList.ACCOUNTING_ACCOUNT_TYPE_CODE, null, "52"),
    ClMapping(B2bCodeList.ADVANCE_PAYMENT_DOC_TYPE, null, "53"),
)

private const val EN16931_RESOURCE = "/facturx-codedb/FACTUR-X_EN16931_codedb.xml"
private const val EXTENDED_RESOURCE = "/facturx-codedb/FACTUR-X_EXTENDED_codedb.xml"

/** Semantic lists documented as a deliberate exception to the EN16931 ⊆ EXTENDED superset rule. */
private val SUPERSET_EXCEPTIONS = setOf(B2bCodeList.SPECIFICATION_IDENTIFIER)

class B2bCodeListDriftTest {

    private fun parseCodedb(resourcePath: String): Map<String, Set<String>> {
        val stream = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Test resource not found: $resourcePath"
        }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val clNodes = document.getElementsByTagName("cl")
        val result = mutableMapOf<String, Set<String>>()
        for (i in 0 until clNodes.length) {
            val clElement = clNodes.item(i) as Element
            val clId = clElement.getAttribute("id")
            val enumNodes = clElement.getElementsByTagName("enumeration")
            val values = (0 until enumNodes.length)
                .map { (enumNodes.item(it) as Element).getAttribute("value") }
                .toSet()
            result[clId] = values
        }
        return result
    }

    private fun rawText(resourcePath: String): String =
        requireNotNull(javaClass.getResourceAsStream(resourcePath)) { "Test resource not found: $resourcePath" }
            .bufferedReader()
            .use { it.readText() }

    private val en16931Codedb = parseCodedb(EN16931_RESOURCE)
    private val extendedCodedb = parseCodedb(EXTENDED_RESOURCE)

    @Test
    fun `CL_MAPPING covers every B2bCodeList constant exactly once`() {
        assertEquals(B2bCodeList.entries.size, CL_MAPPING.size)
        assertEquals(B2bCodeList.entries.toSet(), CL_MAPPING.map { it.list }.toSet())
    }

    @Test
    fun `generated EN16931 constants match a fresh parse of the codedb XML`() {
        for (mapping in CL_MAPPING) {
            val generated = mapping.list.values(FacturXProfile.EN16931)
            if (mapping.en16931ClId == null) {
                assertTrue(generated.isEmpty()) {
                    "${mapping.list} has no EN16931 cl id and must return an empty set for EN16931"
                }
                continue
            }
            val parsed = en16931Codedb.getValue(mapping.en16931ClId)
            assertEquals(parsed, generated) {
                "${mapping.list} EN16931 (cl id ${mapping.en16931ClId}) drifted from codedb.xml — regenerate " +
                    "via scripts/codegen/facturx_codedb_gen.py"
            }
        }
    }

    @Test
    fun `generated EXTENDED constants match a fresh parse of the codedb XML`() {
        for (mapping in CL_MAPPING) {
            val parsed = extendedCodedb.getValue(mapping.extendedClId)
            val generated = mapping.list.values(FacturXProfile.EXTENDED)
            assertEquals(parsed, generated) {
                "${mapping.list} EXTENDED (cl id ${mapping.extendedClId}) drifted from codedb.xml — regenerate " +
                    "via scripts/codegen/facturx_codedb_gen.py"
            }
        }
    }

    @Test
    fun `release metadata is pinned to version 1_09_2, effective 2026-09-01`() {
        for (resourcePath in listOf(EN16931_RESOURCE, EXTENDED_RESOURCE)) {
            val text = rawText(resourcePath)
            assertTrue(text.contains("Version: 1.09.2")) {
                "$resourcePath: release version drifted from the pinned 1.09.2 — a ZUGFeRD update must be a " +
                    "conscious act, bump this test alongside the regeneration"
            }
            assertTrue(text.contains("Effective date: 2026-09-01")) {
                "$resourcePath: effective date drifted from the pinned 2026-09-01"
            }
        }
    }

    @Test
    fun `EN16931 values are a subset of EXTENDED values, except the documented exceptions`() {
        for (mapping in CL_MAPPING) {
            if (mapping.en16931ClId == null || mapping.list in SUPERSET_EXCEPTIONS) continue
            val enValues = mapping.list.values(FacturXProfile.EN16931)
            val extValues = mapping.list.values(FacturXProfile.EXTENDED)
            val missing = enValues - extValues
            assertTrue(missing.isEmpty()) {
                "${mapping.list}: EN16931 ⊆ EXTENDED does not hold, missing from EXTENDED: $missing — either " +
                    "the superset relation broke on this ZUGFeRD release, or this list needs adding to " +
                    "SUPERSET_EXCEPTIONS as a newly documented exception"
            }
        }
    }

    @Test
    fun `the SPECIFICATION_IDENTIFIER exception still genuinely violates the superset relation`() {
        val enValues = B2bCodeList.SPECIFICATION_IDENTIFIER.values(FacturXProfile.EN16931)
        val extValues = B2bCodeList.SPECIFICATION_IDENTIFIER.values(FacturXProfile.EXTENDED)
        assertTrue(enValues.none { it in extValues }) {
            "SPECIFICATION_IDENTIFIER's documented cross-profile exception no longer holds — EN16931's URN is " +
                "now also present in EXTENDED; move it out of SUPERSET_EXCEPTIONS, the general rule applies again"
        }
    }
}
