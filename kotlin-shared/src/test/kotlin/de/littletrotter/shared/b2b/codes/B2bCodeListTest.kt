package de.littletrotter.shared.b2b.codes

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * API-level behaviour of [B2bCodeList]: default profile, [B2bCodeList.contains],
 * EXTENDED-only lists never falling back to EN16931. [B2bCodeListDriftTest] already exercises
 * [B2bCodeList.values] for every constant under both profiles (that test's real job is drift
 * detection, not API coverage) — this class fills in [B2bCodeList.contains] and the default-
 * parameter path so the `check-b2b-codelist-core` jacoco gate reaches 100 % without calibration.
 */
class B2bCodeListTest {

    @Test
    fun `values defaults to EN16931`() {
        assertTrue(B2bCodeList.DOCUMENT_TYPE_1001.values() == B2bCodeList.DOCUMENT_TYPE_1001.values(FacturXProfile.EN16931))
    }

    @Test
    fun `contains defaults to EN16931 and is true for a known EN16931 value`() {
        assertTrue(B2bCodeList.DOCUMENT_TYPE_1001.contains("380"))
        assertTrue(B2bCodeList.DOCUMENT_TYPE_1001.contains("380", FacturXProfile.EN16931))
    }

    @Test
    fun `contains is false for a value absent from the list`() {
        assertFalse(B2bCodeList.DOCUMENT_TYPE_1001.contains("not-a-real-code"))
    }

    @Test
    fun `an EXTENDED-only list is empty and non-containing under EN16931 — never a fallback`() {
        assertTrue(B2bCodeList.LANGUAGE_ISO_639.values(FacturXProfile.EN16931).isEmpty())
        assertFalse(B2bCodeList.LANGUAGE_ISO_639.contains("deu", FacturXProfile.EN16931))
        assertTrue(B2bCodeList.LANGUAGE_ISO_639.contains("deu", FacturXProfile.EXTENDED))
    }

    @Test
    fun `contains under EXTENDED sees the EXTENDED superset for a shared list`() {
        // 'B' (Split payment) is EXTENDED-only per the CL_MAPPING superset exception test.
        assertFalse(B2bCodeList.VAT_CATEGORY_5305.contains("B", FacturXProfile.EN16931))
        assertTrue(B2bCodeList.VAT_CATEGORY_5305.contains("B", FacturXProfile.EXTENDED))
    }
}
