package de.littletrotter.xpdf.validation

import de.littletrotter.shared.KositOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [CiiSchematronValidator] against the real embedded Factur-X 1.09.2 Schematron, not
 * a mock of it (no mock of the rule set): every fixture was engineered by
 * actually running the checked-in stylesheets via Saxon against hand-built CII documents until
 * the observed `svrl:failed-assert` output matched what each test below asserts.
 *
 * Fixture provenance (all derived from `golden/community-invoice-cii.xml`, itself an existing
 * Mustang XRechnung-profile export fixture):
 * - `green-en16931-cii.xml` — the guideline URN changed to the bare EN16931 URN
 *   (`urn:cen.eu:en16931:2017`, the only value the EN16931 codedb's own `cl id="1"` permits) so
 *   the document routes to the EN16931 profile and is otherwise clean (zero failed asserts).
 * - `red-missing-seller-name-cii.xml` — the green fixture with `SellerTradeParty/ram:Name`
 *   removed: fires exactly `BR-06` (references `BT-27` in its own text) and the structural
 *   `FX-SCH-A-000409` cardinality check (not a code-list lookup, despite the `FX-SCH-A-*` id).
 * - `red-invalid-typecode-cii.xml` — the green fixture with `ExchangedDocument/ram:TypeCode`
 *   changed to `999`: fires exactly the pure code-list-membership assert `FX-SCH-A-000555`, whose
 *   own `test` XPath contains `document('FACTUR-X_EN16931_codedb.xml')`.
 * - `extended-cii.xml` — the green fixture with the guideline URN changed to one of the EXTENDED
 *   codedb's own permitted values; clean against the EXTENDED stylesheet (zero failed asserts).
 * - `golden/community-invoice-cii.xml` — unchanged, real XRechnung-profile export fixture (kept
 *   exactly as-is, PO 2026-08-29 routing correction) — its own guideline URN is an XRechnung CIUS
 *   URN, so it must pass through this validator untouched rather than being flagged.
 *
 * The XRechnung-CIUS-pass-through test against that last fixture stays in kotlin-xpdf
 * (`de.littletrotter.xpdf.impl.CiiSchematronXRechnungPassThroughTest`) rather than moving here
 * with the rest of this class: everything under `golden/` is a cross-engine fixture tree kept in
 * kotlin-xpdf (read by Mustang-route tests too, not Factur-X-only), so it is only on
 * kotlin-xpdf's own test classpath, not this module's.
 */
class CiiSchematronValidatorTest {

    private val validator = CiiSchematronValidator().apply { init() }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream("facturx/schematron/$name")) {
            "test fixture not found: $name"
        }.use { it.readBytes() }

    @Test
    fun `a clean EN16931 document passes with zero findings`() {
        val stage = validator.validate(fixture("green-en16931-cii.xml"))

        assertEquals(CiiSchematronValidator.STAGE, stage.stage)
        assertEquals(KositOutcome.VALID, stage.outcome)
        assertFalse(stage.skipped)
        assertTrue(stage.findings.isEmpty(), "expected zero findings, got: ${stage.findings}")
    }

    @Test
    fun `a missing mandatory element surfaces the expected assert id and the extracted B2bTerm`() {
        val stage = validator.validate(fixture("red-missing-seller-name-cii.xml"))

        assertEquals(KositOutcome.INVALID, stage.outcome)
        val ruleIds = stage.findings.map { it.ruleId }.toSet()
        assertEquals(setOf("BR-06", "FX-SCH-A-000409"), ruleIds)

        val brFinding = stage.findings.single { it.ruleId == "BR-06" }
        assertEquals("error", brFinding.severity)
        assertEquals(CiiSchematronValidator.STAGE, brFinding.stage)
        assertEquals(CiiSchematronValidator.VALIDATOR_ID, brFinding.validator)
        assertTrue(brFinding.message.contains("BT-27"), "expected the assert's own text to name BT-27")
        assertEquals(listOf("BT-27"), brFinding.businessTerms)
        // Not a code-list lookup — a mandatory-element check, PO 29.08.2026 marker must be false.
        assertFalse(brFinding.codeListViolation)

        // The cardinality follow-on: same id-prefix family as a real code-list check, but its own
        // `test` XPath is `count(ram:Name)=1` — no document() call — so the marker must stay false.
        val cardinalityFinding = stage.findings.single { it.ruleId == "FX-SCH-A-000409" }
        assertFalse(cardinalityFinding.codeListViolation, "a plain cardinality check must not be marked as a code-list violation")
        assertNull(cardinalityFinding.businessTerms)
    }

    @Test
    fun `a pure code-list membership violation is marked distinctly (upstream gate-gap marker)`() {
        val stage = validator.validate(fixture("red-invalid-typecode-cii.xml"))

        assertEquals(KositOutcome.INVALID, stage.outcome)
        val finding = stage.findings.single()
        assertEquals("FX-SCH-A-000555", finding.ruleId)
        assertTrue(finding.codeListViolation, "a codedb.xml membership check must be marked as a code-list violation")
        assertNull(finding.businessTerms, "this assert's own text carries no BT-nn reference")
    }

    @Test
    fun `an EN16931-only run never compiles the EXTENDED stylesheet`() {
        val fresh = CiiSchematronValidator().apply { init() }
        assertFalse(fresh.extendedTemplates.isInitialized())

        fresh.validate(fixture("green-en16931-cii.xml"))
        fresh.validate(fixture("red-missing-seller-name-cii.xml"))

        assertFalse(
            fresh.extendedTemplates.isInitialized(),
            "an EN16931-only validator must never touch the EXTENDED resources",
        )
    }

    @Test
    fun `an EXTENDED-profile document compiles and validates against the EXTENDED stylesheet lazily`() {
        val fresh = CiiSchematronValidator().apply { init() }
        assertFalse(fresh.extendedTemplates.isInitialized())

        val stage = fresh.validate(fixture("extended-cii.xml"))

        assertTrue(fresh.extendedTemplates.isInitialized(), "the first EXTENDED validation must compile the EXTENDED stylesheet")
        assertEquals(KositOutcome.VALID, stage.outcome)
        assertFalse(stage.skipped)
        assertTrue(stage.findings.isEmpty(), "expected zero findings, got: ${stage.findings}")
    }

    // "An XRechnung CIUS document passes through untouched instead of being flagged"
    // lives in kotlin-xpdf's own CiiSchematronXRechnungPassThroughTest (see this class's own
    // KDoc): it needs the cross-engine `golden/community-invoice-cii.xml` fixture, which stays in
    // kotlin-xpdf's test resources, not this module's.

    @Test
    fun `not-well-formed XML that still matches the guideline regex is an engine error, not a crash`() {
        // The guideline-URN regex only inspects a substring, so it still finds the EN16931 URN
        // and routes to runProfile — but the transform itself must then fail cleanly (an
        // unclosed root element makes this not well-formed) instead of propagating an exception.
        val malformed = """<?xml version="1.0" encoding="UTF-8"?>
            <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
                                       xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:cen.eu:en16931:2017</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
        """.trimIndent().toByteArray() // no closing </rsm:CrossIndustryInvoice> — not well-formed

        val stage = validator.validate(malformed)

        assertEquals(KositOutcome.ERROR, stage.outcome)
        assertFalse(stage.skipped)
        val finding = stage.findings.single()
        assertEquals("error", finding.severity)
        assertTrue(finding.message.contains("engine error"))
    }

    @Test
    fun `a document with no guideline URN at all is an error-severity finding, not a silent pass`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100">
              <rsm:ExchangedDocument/>
            </rsm:CrossIndustryInvoice>
        """.trimIndent().toByteArray()

        val stage = validator.validate(xml)

        assertEquals(KositOutcome.INVALID, stage.outcome)
        assertFalse(stage.skipped)
        val finding = stage.findings.single()
        assertEquals("error", finding.severity)
        assertEquals("FACTURX-SCHEMATRON-NO-GUIDELINE", finding.ruleId)
    }

    @Test
    fun `boundary - init() wraps a broken EN16931 stylesheet lookup as IllegalStateException`() {
        // The constructor-injected stylesheetLookup lets this arm be reached directly,
        // without corrupting the real bundled classpath resource every other test still relies on.
        val broken = CiiSchematronValidator(stylesheetLookup = { null })

        val ex = assertThrows(IllegalStateException::class.java) { broken.init() }

        assertTrue(ex.message?.contains("Failed to initialize Factur-X Schematron validator") == true)
        assertTrue(ex.cause is IllegalStateException, "the original requireStylesheet() failure must be chained as the cause")
    }

    @Test
    fun `an EN16931 route on a validator whose init() never ran reports an engine error, not a document verdict`() {
        val neverInitialized = CiiSchematronValidator() // deliberately no .apply { init() }

        val stage = neverInitialized.validate(fixture("green-en16931-cii.xml"))

        assertEquals(KositOutcome.ERROR, stage.outcome)
        val finding = stage.findings.single()
        assertTrue(finding.message.contains("not initialized"))
    }

    @Test
    fun `a document with an unrecognized guideline URN is an error-severity finding`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
                                       xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:example:not-a-real-guideline</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
            </rsm:CrossIndustryInvoice>
        """.trimIndent().toByteArray()

        val stage = validator.validate(xml)

        assertEquals(KositOutcome.INVALID, stage.outcome)
        val finding = stage.findings.single()
        assertEquals("error", finding.severity)
        assertEquals("FACTURX-SCHEMATRON-UNKNOWN-GUIDELINE", finding.ruleId)
        assertTrue(finding.message.contains("urn:example:not-a-real-guideline"))
    }

    @Test
    fun `boundary - an XRechnung CIUS guideline URN passes through untouched instead of being flagged`() {
        // Same routing decision as kotlin-xpdf's CiiSchematronXRechnungPassThroughTest,
        // but exercised directly here with a minimal inline fixture: that other test's golden
        // fixture lives only on kotlin-xpdf's classpath, not this module's own.
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
                                       xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:cen.eu:en16931:2017#conformant#urn:xoev-de:kosit:standard:xrechnung_3.0</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
            </rsm:CrossIndustryInvoice>
        """.trimIndent().toByteArray()

        val stage = validator.validate(xml)

        assertEquals(CiiSchematronValidator.STAGE, stage.stage)
        assertEquals(KositOutcome.VALID, stage.outcome)
        assertTrue(stage.skipped, "an XRechnung CIUS document is not this validator's domain")
        assertEquals(CiiSchematronValidator.SKIP_REASON_XRECHNUNG_CIUS, stage.skipReason)
        assertTrue(stage.findings.isEmpty())
    }
}
