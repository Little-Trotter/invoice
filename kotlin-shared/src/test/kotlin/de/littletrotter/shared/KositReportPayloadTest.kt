package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KositReportPayloadTest {

    @Test
    fun `encode produces parseable JSON with status and details`() {
        val result = KositValidationResult(
            status = KositOutcome.INVALID,
            summary = "3 validation error(s)",
            details = listOf(
                ValidationMessage("ERROR", "BR-01 missing", "/Invoice[1]"),
                ValidationMessage("WARNING", "recommended", null),
            ),
        )
        val json = KositReportPayload.encode(result)

        val decoded = KositReportPayload.decode(json)
        assertNotNull(decoded)
        assertEquals("INVALID", decoded!!.status)
        assertEquals("3 validation error(s)", decoded.summary)
        assertEquals(2, decoded.details.size)
        assertEquals("ERROR", decoded.details[0].severity)
        assertEquals("BR-01 missing", decoded.details[0].message)
        assertEquals("/Invoice[1]", decoded.details[0].location)
    }

    @Test
    fun `encode of VALID without summary still yields a non-blank JSON payload`() {
        val json = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
        assertNotNull(json)
        assertEquals("VALID", KositReportPayload.decode(json)?.status)
    }

    @Test
    fun `decode returns null for blank input`() {
        assertNull(KositReportPayload.decode(null))
        assertNull(KositReportPayload.decode(""))
        assertNull(KositReportPayload.decode("   "))
    }

    @Test
    fun `decode returns null for non-JSON plain text`() {
        assertNull(KositReportPayload.decode("3 validation error(s)"))
        assertNull(KositReportPayload.decode("<report><result>VALID</result></report>"))
    }

    @Test
    fun `decode returns null for malformed JSON`() {
        assertNull(KositReportPayload.decode("{ not valid json"))
    }

    @Test
    fun `round-trips validatorVersion`() {
        val json = KositReportPayload.encode(
            KositValidationResult(status = KositOutcome.VALID, validatorVersion = "1.4.1")
        )
        assertEquals("1.4.1", KositReportPayload.decode(json)?.validatorVersion)
    }

    // An old JSON document (written before ruleId/stage/validator existed) decodes
    // with those fields null, no throw.
    @Test
    fun `decodes a document written before ruleId, stage and validator existed`() {
        val legacyJson = """
            {"status":"INVALID","summary":"1 validation error(s)",
             "details":[{"severity":"ERROR","message":"BR-01 missing","location":"/Invoice[1]"}]}
        """.trimIndent()
        val decoded = KositReportPayload.decode(legacyJson)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.details.size)
        val detail = decoded.details[0]
        assertEquals("ERROR", detail.severity)
        assertNull(detail.ruleId)
        assertNull(detail.stage)
        assertNull(detail.validator)
    }

    // A document with a field this reader doesn't know about is still read (the
    // unknown field is ignored, not treated as a parse failure).
    @Test
    fun `decodes a document with an unknown extra field instead of failing`() {
        val futureJson = """
            {"status":"VALID","details":[],"somethingFromALaterTask":"whatever"}
        """.trimIndent()
        val decoded = KositReportPayload.decode(futureJson)
        assertNotNull(decoded)
        assertEquals("VALID", decoded!!.status)
    }

    // Version block round-trips; an old payload (from before the version block existed) decodes with all three
    // fields null instead of throwing.
    @Test
    fun `round-trips generatorVersion, profile and checkedAt`() {
        val result = KositValidationResult(
            status = KositOutcome.VALID,
            validatorVersion = "1.6.2 / 2026-01-31",
            generatorVersion = "2.25.0",
            profile = "XRechnung",
            checkedAt = "2026-08-16T10:15:30Z",
        )
        val decoded = KositReportPayload.decode(KositReportPayload.encode(result))
        assertNotNull(decoded)
        assertEquals("2.25.0", decoded!!.generatorVersion)
        assertEquals("XRechnung", decoded.profile)
        assertEquals("2026-08-16T10:15:30Z", decoded.checkedAt)
    }

    @Test
    fun `decodes a pre-F2 document with the version block fields null`() {
        val legacyJson = """{"status":"VALID","details":[]}"""
        val decoded = KositReportPayload.decode(legacyJson)
        assertNotNull(decoded)
        assertNull(decoded!!.generatorVersion)
        assertNull(decoded.profile)
        assertNull(decoded.checkedAt)
    }

    // A report frozen from before stage-level skip reasons landed: the worker (kotlin-xpdf) always shipped the
    // BUSINESS stage `skipped` (a placeholder, see ValidationRun's KDoc). Such a document
    // must stay decodable exactly as it was found — nothing here ever rewrites an already
    // persisted blob, so old rows keep reading "übersprungen" forever, not retroactively gain
    // findings they never had.
    @Test
    fun `decodes a frozen pre-ARC-6 report with the BUSINESS stage still skipped`() {
        val frozenJson = """
            {"status":"VALID","summary":null,"details":[],"validatorVersion":"embedded",
             "checkedAt":"2026-08-01T10:00:00Z",
             "validationRun":{
               "stages":[
                 {"stage":"SYNTAX","outcome":"VALID","findings":[],"skipped":false,"skipReason":null},
                 {"stage":"KOSIT","outcome":"VALID","findings":[],"skipped":false,"skipReason":null},
                 {"stage":"PDFA","outcome":"VALID","findings":[],"skipped":false,"skipReason":null},
                 {"stage":"BUSINESS","outcome":"VALID","findings":[],"skipped":true,
                  "skipReason":"TAX-03/PARTY-01 noch nicht integriert"}
               ],
               "overall":"VALID",
               "validatorVersions":{},
               "startedAt":"2026-08-01T10:00:00Z"
             }}
        """.trimIndent()

        val decoded = KositReportPayload.decode(frozenJson)

        assertNotNull(decoded)
        assertEquals("VALID", decoded!!.status)
        val businessStage = decoded.validationRun?.stages?.find { it.stage == "BUSINESS" }
        assertNotNull(businessStage)
        assertEquals(true, businessStage!!.skipped)
        assertEquals("TAX-03/PARTY-01 noch nicht integriert", businessStage.skipReason)
    }

    // Encode/decode round-trips ruleId/stage/validator without loss.
    @Test
    fun `round-trips ruleId, stage and validator`() {
        val result = KositValidationResult(
            status = KositOutcome.INVALID,
            details = listOf(
                ValidationMessage(
                    severity = "ERROR",
                    message = "Payment terms missing",
                    location = "/Invoice[1]/PaymentTerms",
                    ruleId = "BR-CO-25",
                    stage = "KOSIT",
                    validator = "kosit",
                ),
            ),
        )
        val decoded = KositReportPayload.decode(KositReportPayload.encode(result))
        assertNotNull(decoded)
        val detail = decoded!!.details[0]
        assertEquals("BR-CO-25", detail.ruleId)
        assertEquals("KOSIT", detail.stage)
        assertEquals("kosit", detail.validator)
    }

    // veraPDF engine-error visibility — a stage's engineError round-trips intact.
    @Test
    fun `round-trips a stage engineError`() {
        val stages = listOf(
            ValidationStageResult(
                "VERAPDF", KositOutcome.ERROR,
                engineError = "veraPDF-CLI nicht ausführbar (/opt/verapdf/verapdf)",
            ),
        )
        val result = KositValidationResult(
            status = KositOutcome.ERROR,
            validationRun = ValidationRun(stages, ValidationRun.overallOutcomeOf(stages), startedAt = "2026-08-24T10:00:00Z"),
        )
        val decoded = KositReportPayload.decode(KositReportPayload.encode(result))
        assertNotNull(decoded)
        val verapdfStage = decoded!!.validationRun?.stages?.find { it.stage == "VERAPDF" }
        assertNotNull(verapdfStage)
        assertEquals("veraPDF-CLI nicht ausführbar (/opt/verapdf/verapdf)", verapdfStage!!.engineError)
    }

    // args — carries a compliance finding's message-format arguments so a rendering UI can fill
    // the messageKey's {0}/{1} placeholders (fix for the raw-i18n-key defect in the validation
    // report's "1.1 Einzelbefunde" list).
    @Test
    fun `round-trips a message's args`() {
        val result = KositValidationResult(
            status = KositOutcome.INVALID,
            details = listOf(
                ValidationMessage(
                    severity = "ERROR",
                    message = "compliance.tax.categoryMismatch",
                    ruleId = "TAX.CATEGORY_MISMATCH",
                    stage = "BUSINESS",
                    validator = "compliance",
                    args = listOf("S", "AE"),
                ),
            ),
        )
        val decoded = KositReportPayload.decode(KositReportPayload.encode(result))
        assertNotNull(decoded)
        assertEquals(listOf("S", "AE"), decoded!!.details[0].args)
    }

    // A legacy payload written before args existed decodes with the field null, not an empty
    // list and not a throw — same tolerant-reader convention as every other field here.
    @Test
    fun `decodes a document written before args existed with the field null`() {
        val legacyJson = """
            {"status":"INVALID","details":[{"severity":"ERROR","message":"BR-01 missing"}]}
        """.trimIndent()
        val decoded = KositReportPayload.decode(legacyJson)
        assertNotNull(decoded)
        assertNull(decoded!!.details[0].args)
    }

    // A payload written before engineError existed decodes with the field null, no throw.
    @Test
    fun `decodes a stage without engineError as null`() {
        val legacyJson = """
            {"status":"ERROR","details":[],
             "validationRun":{
               "stages":[{"stage":"VERAPDF","outcome":"ERROR","findings":[],"skipped":false,"skipReason":null}],
               "overall":"ERROR",
               "validatorVersions":{},
               "startedAt":"2026-08-01T10:00:00Z"
             }}
        """.trimIndent()
        val decoded = KositReportPayload.decode(legacyJson)
        assertNotNull(decoded)
        val verapdfStage = decoded!!.validationRun?.stages?.find { it.stage == "VERAPDF" }
        assertNotNull(verapdfStage)
        assertNull(verapdfStage!!.engineError)
    }

    @Test
    fun `withStage replaces the pending stage and recomputes the flat fields`() {
        val finding = ValidationMessage("ERROR", "rule failed", stage = "VERAPDF")
        val run = ValidationRun(
            stages = listOf(
                ValidationStageResult("PDFA", KositOutcome.VALID),
                ValidationStageResult(ValidationStageResult.VERAPDF, KositOutcome.VALID, pending = true, advisory = true),
            ),
            overall = KositOutcome.VALID,
            startedAt = "2026-10-12T10:00:00Z",
        )
        val report = KositReportPayload(status = "VALID", validationRun = run)

        val updated = report.withStage(
            ValidationStageResult(ValidationStageResult.VERAPDF, KositOutcome.INVALID, listOf(finding), advisory = true),
        )!!

        assertEquals("VALID", updated.status)
        assertEquals(listOf(finding), updated.details)
        assertEquals("1 validation finding(s) across 2 stage(s)", updated.summary)
        assertEquals(false, updated.validationRun!!.stages.last().pending)
        assertEquals(KositOutcome.INVALID, KositReportPayload.decode(updated.toJson())!!.validationRun!!.stages.last().outcome)
    }

    @Test
    fun `withStage without a stage breakdown has nothing to replace`() {
        assertNull(KositReportPayload(status = "VALID").withStage(ValidationStageResult("X", KositOutcome.VALID)))
    }
}
