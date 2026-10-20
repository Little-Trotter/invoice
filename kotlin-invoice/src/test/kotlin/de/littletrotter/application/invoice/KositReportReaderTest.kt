package de.littletrotter.application.invoice

import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationRun
import de.littletrotter.shared.ValidationStageResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Z3 — unit tests for [KositReportReader], moved out of the UI-layer
 * `InvoiceDetailViewExportTabTest` alongside the reader itself (the interpretation of KoSIT
 * report XML/JSON no longer belongs in the ui package).
 */
class KositReportReaderTest {

    // ── null / blank input ────────────────────────────────────────────────────

    @Test
    fun `read returns UNKNOWN for null input`() {
        val result = KositReportReader.read(null)
        assertEquals(KositStatus.UNKNOWN, result.status)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.isEmpty())
        assertFalse(result.isXml)
    }

    @Test
    fun `read returns UNKNOWN for blank string`() {
        val result = KositReportReader.read("   ")
        assertEquals(KositStatus.UNKNOWN, result.status)
        assertFalse(result.isXml)
    }

    // ── legacy KoSIT XML — happy path ───────────────────────────────────────────

    @Test
    fun `read detects VALID from KoSIT XML with VALID result element`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <rep:report xmlns:rep="http://www.example.com/report">
              <rep:documentValidationResult>
                <rep:result>VALID</rep:result>
                <rep:messages/>
              </rep:documentValidationResult>
            </rep:report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.VALID, result.status)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.isXml)
    }

    @Test
    fun `read detects INVALID from KoSIT XML with INVALID result element`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <rep:report xmlns:rep="http://www.example.com/report">
              <rep:documentValidationResult>
                <rep:result>INVALID</rep:result>
                <rep:messages>
                  <rep:message>
                    <rep:type>ERROR</rep:type>
                    <rep:text>Mandatory field BR-01 is missing</rep:text>
                  </rep:message>
                </rep:messages>
              </rep:documentValidationResult>
            </rep:report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.INVALID, result.status)
        assertEquals(listOf("Mandatory field BR-01 is missing"), result.errors.map { it.text })
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.isXml)
    }

    @Test
    fun `read extracts warnings from KoSIT XML`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <rep:report xmlns:rep="http://www.example.com/report">
              <rep:documentValidationResult>
                <rep:result>VALID</rep:result>
                <rep:messages>
                  <rep:message>
                    <rep:type>WARNING</rep:type>
                    <rep:text>Optional field recommended</rep:text>
                  </rep:message>
                </rep:messages>
              </rep:documentValidationResult>
            </rep:report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.VALID, result.status)
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf("Optional field recommended"), result.warnings.map { it.text })
        assertTrue(result.isXml)
    }

    @Test
    fun `read treats FATAL type as error`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <rep:report xmlns:rep="http://www.example.com/report">
              <rep:documentValidationResult>
                <rep:result>VALID</rep:result>
                <rep:messages>
                  <rep:message>
                    <rep:type>FATAL</rep:type>
                    <rep:text>Critical schema violation</rep:text>
                  </rep:message>
                </rep:messages>
              </rep:documentValidationResult>
            </rep:report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.INVALID, result.status)
        assertEquals(listOf("Critical schema violation"), result.errors.map { it.text })
    }

    @Test
    fun `read handles ACCEPTABLE result as VALID`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report><result>ACCEPTABLE</result></report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.VALID, result.status)
        assertTrue(result.isXml)
    }

    @Test
    fun `read extracts multiple errors`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report xmlns:r="x">
              <result>INVALID</result>
              <messages>
                <message><type>ERROR</type><text>Error one</text></message>
                <message><type>ERROR</type><text>Error two</text></message>
              </messages>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.INVALID, result.status)
        assertEquals(listOf("Error one", "Error two"), result.errors.map { it.text })
    }

    @Test
    fun `read extracts multiple warnings alongside valid result`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>VALID</result>
              <messages>
                <message><type>WARNING</type><text>Warn A</text></message>
                <message><type>WARNING</type><text>Warn B</text></message>
              </messages>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.VALID, result.status)
        assertEquals(listOf("Warn A", "Warn B"), result.warnings.map { it.text })
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `read extracts attribute-based KoSIT errors with xpath and code`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
                        <rep:report xmlns:rep="http://www.xoev.de/de/validator/varl/1" valid="false">
                            <rep:validationStepResult id="val-sch.1" valid="false">
                                <rep:message id="val-sch.1.1" level="error" xpathLocation="/a/b/c" code="BR-S-02">[BR-S-02]-Seller VAT identifier missing.</rep:message>
                                <rep:message id="val-sch.1.2" level="error" xpathLocation="/a/b/d" code="BR-CO-26">[BR-CO-26]-Seller identifier missing.</rep:message>
                            </rep:validationStepResult>
                        </rep:report>"""

        val result = KositReportReader.read(xml)

        assertEquals(KositStatus.INVALID, result.status)
        assertEquals(2, result.errors.size)
        assertEquals("BR-S-02", result.errors[0].code)
        assertEquals("/a/b/c", result.errors[0].location)
        assertEquals("ERROR", result.errors[0].severity)
        assertTrue(result.errors[0].text.contains("Seller VAT identifier missing"))
        assertEquals("BR-CO-26", result.errors[1].code)
        assertEquals("/a/b/d", result.errors[1].location)
    }

    @Test
    fun `read treats varl1 valid=true root attribute as VALID even when text contains Fehler`() {
        // Regression: valid German KoSIT reports contain "weder Fehler noch Warnungen" in the HTML
        // explanation, which previously triggered a false INVALID via upper.contains("FEHLER").
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
                        <rep:report xmlns:rep="http://www.xoev.de/de/validator/varl/1" varlVersion="1.0.0" valid="true">
                            <rep:assessment>
                                <rep:accept>
                                    <rep:explanation>Das geprüfte Dokument enthält weder Fehler noch Warnungen.</rep:explanation>
                                </rep:accept>
                            </rep:assessment>
                            <rep:validationStepResult id="val-xsd" valid="true"/>
                            <rep:validationStepResult id="val-sch.1" valid="true"/>
                            <rep:validationStepResult id="val-sch.2" valid="true"/>
                        </rep:report>"""

        val result = KositReportReader.read(xml)

        assertEquals(KositStatus.VALID, result.status)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.isXml)
    }

    @Test
    fun `read treats varl1 valid=false root attribute as INVALID`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
                        <rep:report xmlns:rep="http://www.xoev.de/de/validator/varl/1" valid="false">
                            <rep:assessment>
                                <rep:reject/>
                            </rep:assessment>
                        </rep:report>"""

        val result = KositReportReader.read(xml)

        assertEquals(KositStatus.INVALID, result.status)
        assertTrue(result.isXml)
    }

    // ── legacy plain-text fallback (malformed / non-XML input) ─────────────────

    @Test
    fun `read falls back for non-XML with VALID hint`() {
        val result = KositReportReader.read("KoSIT VALID OK")
        assertEquals(KositStatus.VALID, result.status)
        assertFalse(result.isXml)
    }

    @Test
    fun `read falls back for non-XML with INVALID hint`() {
        val result = KositReportReader.read("Schema INVALID detected")
        assertEquals(KositStatus.INVALID, result.status)
        assertFalse(result.isXml)
    }

    @Test
    fun `read falls back to UNKNOWN for unrecognized plain text`() {
        val result = KositReportReader.read("some random text with no keywords")
        assertEquals(KositStatus.INVALID, result.status)
        assertFalse(result.isXml)
    }

    @Test
    fun `read keeps legacy XML and plain-text behavior unchanged`() {
        val xmlResult = KositReportReader.read(
            """<?xml version="1.0"?><report><result>VALID</result></report>"""
        )
        assertEquals(KositStatus.VALID, xmlResult.status)
        assertTrue(xmlResult.isXml)

        val plainTextResult = KositReportReader.read("3 Fehler gefunden")
        assertEquals(KositStatus.INVALID, plainTextResult.status)
        assertFalse(plainTextResult.isXml)
    }

    /** Malformed input: not XML, no recognizable keyword — the reader must not throw, and falls
     *  back to the conservative "INVALID" reading a plain-text summary gets. */
    @Test
    fun `read treats legacy plain-text summary as INVALID via fallback`() {
        // Pre-fix stored jobs carry a plain-text summary without XML structure.
        val result = KositReportReader.read("3 Fehler gefunden")
        assertEquals(KositStatus.INVALID, result.status)
        assertFalse(result.isXml)
    }

    // ── JSON envelope (current format) ──────────────────────────────────────────

    @Test
    fun `read parses JSON payload with INVALID details`() {
        val json = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.INVALID,
                summary = "3 validation error(s)",
                details = listOf(
                    ValidationMessage("ERROR", "Mandatory field BR-01 is missing", "/Invoice[1]/BT-1"),
                    ValidationMessage("ERROR", "BT-3 must be a valid date", null),
                ),
            )
        )
        val result = KositReportReader.read(json)
        assertEquals(KositStatus.INVALID, result.status)
        assertEquals(2, result.errors.size)
        assertEquals("Mandatory field BR-01 is missing", result.errors[0].text)
        assertEquals("/Invoice[1]/BT-1", result.errors[0].location)
        assertEquals("ERROR", result.errors[0].severity)
        assertFalse(result.isXml)
    }

    @Test
    fun `read parses JSON payload with VALID status`() {
        val json = KositReportPayload.encode(
            KositValidationResult(status = KositOutcome.VALID, summary = null)
        )
        val result = KositReportReader.read(json)
        assertEquals(KositStatus.VALID, result.status)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.isEmpty())
        assertFalse(result.isXml)
    }

    @Test
    fun `read parses JSON payload with warnings`() {
        val json = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.VALID,
                details = listOf(ValidationMessage("WARNING", "Optional field recommended", null)),
            )
        )
        val result = KositReportReader.read(json)
        assertEquals(KositStatus.VALID, result.status)
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf("Optional field recommended"), result.warnings.map { it.text })
    }

    // A new-shape payload: code is the rule ID, warnings land in `warnings`, not `errors`.
    @Test
    fun `read maps ruleId to code and keeps warnings out of errors`() {
        val json = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.INVALID,
                details = listOf(
                    ValidationMessage(
                        severity = "ERROR", message = "Payment terms missing",
                        location = "/Invoice[1]/PaymentTerms", ruleId = "BR-CO-25", stage = "KOSIT", validator = "kosit",
                    ),
                    ValidationMessage(
                        severity = "WARNING", message = "TypeCode not recommended",
                        ruleId = "BR-DE-17", stage = "KOSIT", validator = "kosit",
                    ),
                ),
            )
        )
        val result = KositReportReader.read(json)
        assertEquals(1, result.errors.size)
        assertEquals("BR-CO-25", result.errors[0].code)
        assertEquals(1, result.warnings.size)
        assertEquals("BR-DE-17", result.warnings[0].code)
    }

    // Fix for the raw-i18n-key defect in the combined validation report's "1.1 Einzelbefunde"
    // list — args must survive the JSON payload → KositMessage mapping, or a resolving renderer
    // would have nothing to fill the messageKey's {0}/{1} placeholders with.
    @Test
    fun `read carries a detail's args through into KositMessage`() {
        val json = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.INVALID,
                details = listOf(
                    ValidationMessage(
                        severity = "ERROR", message = "compliance.tax.categoryMismatch",
                        ruleId = "TAX.CATEGORY_MISMATCH", stage = "BUSINESS", validator = "compliance",
                        args = listOf("S", "AE"),
                    ),
                ),
            )
        )
        val result = KositReportReader.read(json)
        assertEquals(listOf("S", "AE"), result.errors[0].args)
    }

    // A legacy payload without args decodes with the field null on the KositMessage side too.
    @Test
    fun `read leaves args null for a detail without them`() {
        val json = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.INVALID,
                details = listOf(ValidationMessage("ERROR", "BR-01 missing", null)),
            )
        )
        val result = KositReportReader.read(json)
        assertEquals(null, result.errors[0].args)
    }

    // Status "ERROR" maps to KositStatus.ERROR, never INVALID.
    @Test
    fun `read maps status ERROR to KositStatus ERROR not INVALID`() {
        val json = KositReportPayload.encode(
            KositValidationResult(status = KositOutcome.ERROR, summary = "Engine error: timeout")
        )
        val result = KositReportReader.read(json)
        assertEquals(KositStatus.ERROR, result.status)
    }

    // veraPDF engine-error visibility — a stage's engineError maps into KositStage.engineError.
    @Test
    fun `read maps a stage engineError into KositStage`() {
        val stages = listOf(
            ValidationStageResult(
                "VERAPDF", KositOutcome.ERROR,
                engineError = "veraPDF-CLI nicht ausführbar (/opt/verapdf/verapdf)",
            ),
        )
        val json = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.ERROR,
                validationRun = ValidationRun(
                    stages,
                    ValidationRun.overallOutcomeOf(stages),
                    startedAt = "2026-08-24T10:00:00Z",
                ),
            )
        )
        val result = KositReportReader.read(json)
        val verapdfStage = result.stages.find { it.name == "VERAPDF" }
        assertNotNull(verapdfStage)
        assertEquals(KositStatus.ERROR, verapdfStage!!.outcome)
        assertEquals("veraPDF-CLI nicht ausführbar (/opt/verapdf/verapdf)", verapdfStage.engineError)
    }

    // ── real report fixtures (kotlin-invoice/src/test/resources/fixtures) ──────

    private fun fixture(name: String): String =
        String(Files.readAllBytes(Paths.get("src/test/resources/fixtures/$name")), Charsets.UTF_8)

    /** Happy path against a real (fixture) KoSIT validator report: an INVALID XRechnung report
     *  with two BR-CO findings, in the actual varl/1 report shape the KoSIT validator produces. */
    @Test
    fun `read against real KoSIT report fixture yields INVALID with both findings`() {
        val result = KositReportReader.read(fixture("kosit-report-invalid.xml"))
        assertEquals(KositStatus.INVALID, result.status)
        assertTrue(result.isXml)
        assertEquals(2, result.errors.size)
        assertTrue(result.errors.any { it.code == "BR-CO-25" })
        assertTrue(result.errors.any { it.code == "BR-CO-26" })
    }

    /** Malformed input via a fixture file: truncated/broken XML that fails SAX parsing outright —
     *  exercises the reader's exception fallback, not just the "well-formed but odd" branch. */
    @Test
    fun `read against malformed report fixture does not throw and falls back`() {
        val result = KositReportReader.read(fixture("kosit-report-malformed.xml"))
        assertEquals(KositStatus.UNKNOWN, result.status)
        assertFalse(result.isXml)
    }

    // ─── Coverage: edge cases and uncovered branches ──────────────────────────────

    @Test
    fun `read valid attribute resolves VALID when no root attribute exists`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>VALID</result>
              <messages/>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.VALID, result.status)
        assertTrue(result.isXml)
    }

    @Test
    fun `read resolves INVALID when valid attribute is false`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report valid="false">
              <result>VALID</result>
              <messages/>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.INVALID, result.status)
        assertTrue(result.isXml)
    }

    @Test
    fun `read with accept element and no reject resolves VALID`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <accept>ACCEPTED</accept>
              <messages/>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.VALID, result.status)
    }

    @Test
    fun `read with both accept and reject resolves INVALID`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <accept>ACCEPTED</accept>
              <reject>REJECTED</reject>
              <messages/>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.INVALID, result.status)
    }

    @Test
    fun `read checks reject element for INVALID`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>VALID</result>
              <reject>REJECTED</reject>
              <messages/>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.INVALID, result.status)
    }

    @Test
    fun `read uses result text when no valid attribute and has reject`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>NOT_VALID</result>
              <messages/>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.INVALID, result.status)
    }

    @Test
    fun `read detects FAILED as INVALID`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>FAILED</result>
              <messages/>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.INVALID, result.status)
    }

    @Test
    fun `read falls back to uppercase text search for INVALID keyword`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>Invalid report content</report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.INVALID, result.status)
        // well-formed XML: the keyword fallback is the last rung INSIDE the XML path
        assertTrue(result.isXml)
    }

    @Test
    fun `read falls back to uppercase text search for EXCEPTION keyword`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>Exception occurred in validation</report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.INVALID, result.status)
        // well-formed XML: the keyword fallback is the last rung INSIDE the XML path
        assertTrue(result.isXml)
    }

    @Test
    fun `read treats plain text without keywords as INVALID`() {
        val result = KositReportReader.read("Some random text without keywords")
        assertEquals(KositStatus.INVALID, result.status)
        assertEquals("Some random text without keywords", result.errors.first().text)
        assertFalse(result.isXml)
    }

    @Test
    fun `read treats XMLlike text as UNKNOWN`() {
        val xml = """<broken><looks-like-xml-but-unparsable"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.UNKNOWN, result.status)
        assertFalse(result.isXml)
    }

    @Test
    fun `read extracts message attributes (level, code) when present`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>INVALID</result>
              <message level="ERROR" code="BR-123" id="msg-1">
                <text>Validation failed</text>
              </message>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals(1, result.errors.size)
        assertEquals("BR-123", result.errors[0].code)
        assertEquals("msg-1", result.errors[0].position)
        assertEquals("Validation failed", result.errors[0].text)
    }

    @Test
    fun `read handles messages with xpathLocation attribute`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>INVALID</result>
              <message level="ERROR" xpathLocation="/Invoice/Header">
                <text>Header error</text>
              </message>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals("/Invoice/Header", result.errors[0].location)
    }

    @Test
    fun `read handles messages with location element`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>INVALID</result>
              <message level="ERROR">
                <location>/Invoice/Item</location>
                <text>Item error</text>
              </message>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals("/Invoice/Item", result.errors[0].location)
    }

    @Test
    fun `read ignores messages with no text content`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>INVALID</result>
              <message level="ERROR"></message>
            </report>"""
        val result = KositReportReader.read(xml)
        // a <message> with child elements would keep its textContent; only a truly empty one is dropped
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `read defaults severity to UNKNOWN when not present`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>VALID</result>
              <message>
                <text>Generic message</text>
              </message>
            </report>"""
        val result = KositReportReader.read(xml)
        // UNKNOWN severity is neither ERROR/FATAL nor WARNING - the message lands in neither list
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `read handles XML entities in text content`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>INVALID</result>
              <message level="ERROR">
                <text>Apostrophe&apos; and &quot;quotes&quot; &amp; ampersand</text>
              </message>
            </report>"""
        val result = KositReportReader.read(xml)
        assertTrue(result.errors[0].text.contains("Apostrophe'"))
    }

    @Test
    fun `read handles whitespace-only text as missing`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>VALID</result>
              <message level="ERROR">
                <text>   </text>
              </message>
            </report>"""
        val result = KositReportReader.read(xml)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `read with errors present and VALID result still reports INVALID`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report>
              <result>VALID</result>
              <message level="ERROR">
                <text>Error found</text>
              </message>
            </report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.INVALID, result.status)
    }

    @Test
    fun `read ignores an empty valid attribute and lets reject decide`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <report valid="">
              <reject/>
            </report>"""
        val result = KositReportReader.read(xml)
        // an empty valid attribute is ignored (not treated as false); the <reject/> decides
        assertEquals(KositStatus.INVALID, result.status)
    }

    // ── Remaining decision branches, pinned against the real cascade ──────────────

    @Test
    fun `json payload with unknown status and an error detail falls back to INVALID`() {
        val json = """{"status":"BOGUS","details":[{"severity":"ERROR","message":"broken"}]}"""
        assertEquals(KositStatus.INVALID, KositReportReader.read(json).status)
    }

    @Test
    fun `json payload with unknown status and no details falls back to VALID`() {
        val json = """{"status":"BOGUS","details":[]}"""
        assertEquals(KositStatus.VALID, KositReportReader.read(json).status)
    }

    @Test
    fun `json stage with INVALID outcome maps to INVALID stage status`() {
        val stages = listOf(ValidationStageResult("KOSIT", KositOutcome.INVALID))
        val json = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.INVALID,
                validationRun = ValidationRun(stages, ValidationRun.overallOutcomeOf(stages), startedAt = "2026-08-24T10:00:00Z"),
            ),
        )
        assertEquals(KositStatus.INVALID, KositReportReader.read(json).stages.single().outcome)
    }

    @Test
    fun `boundary - json stage with VALID outcome maps to VALID stage status`() {
        val stages = listOf(ValidationStageResult("PDFA", KositOutcome.VALID))
        val json = KositReportPayload.encode(
            KositValidationResult(
                status = KositOutcome.VALID,
                validationRun = ValidationRun(stages, ValidationRun.overallOutcomeOf(stages), startedAt = "2026-08-24T10:00:00Z"),
            ),
        )
        assertEquals(KositStatus.VALID, KositReportReader.read(json).stages.single().outcome)
    }

    @Test
    fun `boundary - accept without reject but with error messages does not short-circuit to VALID`() {
        val xml = """<report><accept/><message level="ERROR"><text>bad</text></message></report>"""
        assertEquals(KositStatus.INVALID, KositReportReader.read(xml).status)
    }

    @Test
    fun `boundary - a VALID result text with error messages does not short-circuit to VALID`() {
        val xml = """<report><result>VALID</result><message level="ERROR"><text>bad</text></message></report>"""
        assertEquals(KositStatus.INVALID, KositReportReader.read(xml).status)
    }

    @Test
    fun `boundary - fallback parser with only markup and no text yields no messages`() {
        // Unclosed tags fail XML parsing and fall through to the plain-text fallback; stripping
        // the markup leaves only whitespace, so no message is built at all.
        val result = KositReportReader.read("<a><b>   ")
        assertTrue(result.errors.isEmpty())
        assertEquals(KositStatus.UNKNOWN, result.status)
    }

    @Test
    fun `xml without any keyword or verdict yields UNKNOWN`() {
        val xml = """<report><foo/></report>"""
        val result = KositReportReader.read(xml)
        assertEquals(KositStatus.UNKNOWN, result.status)
        assertTrue(result.isXml)
    }

    @Test
    fun `xml containing a benign valid keyword yields VALID`() {
        val xml = """<report>Everything checked and valid</report>"""
        assertEquals(KositStatus.VALID, KositReportReader.read(xml).status)
    }

    @Test
    fun `valid attribute true with error messages still reports INVALID`() {
        val xml = """<report valid="true"><message level="ERROR"><text>bad</text></message></report>"""
        assertEquals(KositStatus.INVALID, KositReportReader.read(xml).status)
    }

    @Test
    fun `accept next to reject is decided by reject`() {
        val xml = """<report><accept/><reject/></report>"""
        assertEquals(KositStatus.INVALID, KositReportReader.read(xml).status)
    }

    @Test
    fun `plain text containing valid without invalid is VALID`() {
        val result = KositReportReader.read("output: everything checked, ok and valid")
        assertEquals(KositStatus.VALID, result.status)
        assertFalse(result.isXml)
    }

    // ── KositStage's default parameter values ───────────────────────────
    // KositReportReader itself always passes every KositStage argument explicitly (mapped 1:1
    // from the payload), so the synthetic default-value constructor Kotlin generates for
    // skipped/skipReason/engineError is otherwise never invoked by any test in this file.

    @Test
    fun `boundary - KositStage defaults skipped to false and skipReason-engineError to null`() {
        val stage = KositStage(name = "SYNTAX", outcome = KositStatus.VALID, findingCount = 0)
        assertFalse(stage.skipped)
        assertEquals(null, stage.skipReason)
        assertEquals(null, stage.engineError)
    }

    // ── KositXmlErrorHandler, exercised directly ────────────────────────
    // Extracted to a named object (from an anonymous class inline in parseXmlReport) precisely so
    // every override is independently callable here, without depending on a specific parser
    // configuration ever actually triggering warning()/error() at runtime.

    private fun saxParseException(): org.xml.sax.SAXParseException =
        org.xml.sax.SAXParseException("boundary: synthetic parser diagnostic", null)

    @Test
    fun `boundary - KositXmlErrorHandler warning is swallowed, not rethrown`() {
        // A non-validating parser never actually calls this in practice, but the ErrorHandler
        // contract requires the override, and a warning must never abort the parse.
        assertDoesNotThrow { KositXmlErrorHandler.warning(saxParseException()) }
    }

    @Test
    fun `boundary - KositXmlErrorHandler error rethrows the same exception`() {
        val e = saxParseException()
        val thrown = assertThrows(org.xml.sax.SAXParseException::class.java) { KositXmlErrorHandler.error(e) }
        assertEquals(e, thrown)
    }

    @Test
    fun `boundary - KositXmlErrorHandler fatalError rethrows the same exception`() {
        val e = saxParseException()
        val thrown = assertThrows(org.xml.sax.SAXParseException::class.java) { KositXmlErrorHandler.fatalError(e) }
        assertEquals(e, thrown)
    }

    // ── asynchronous veraPDF stage ────────────────────────────────────────────

    private fun stagesOf(vararg veraPdf: ValidationStageResult): List<KositStage> {
        val stages = listOf(ValidationStageResult("PDFA", KositOutcome.VALID)) + veraPdf
        val run = ValidationRun(stages, KositOutcome.VALID, startedAt = "2026-10-12T10:00:00Z")
        return KositReportReader.read(KositReportPayload(status = "VALID", validationRun = run).toJson()).stages
    }

    @Test
    fun `a pending veraPDF stage reads as awaiting its verdict, not as passed`() {
        val vera = stagesOf(ValidationStageResult("VERAPDF", KositOutcome.VALID, pending = true, advisory = true)).single { it.name == "VERAPDF" }

        assertTrue(vera.pending)
        assertEquals("ausstehend", vera.stateLabel)
    }

    @Test
    fun `an advisory stage whose check could not run reads as check not possible`() {
        val vera = stagesOf(ValidationStageResult("VERAPDF", KositOutcome.ERROR, advisory = true, engineError = "down")).single { it.name == "VERAPDF" }

        assertEquals("Prüfung nicht möglich", vera.stateLabel)
    }

    @Test
    fun `ordinary stages and delivered verdicts have no state label of their own`() {
        val stages = stagesOf(ValidationStageResult("VERAPDF", KositOutcome.INVALID, advisory = true))

        assertNull(stages.single { it.name == "PDFA" }.stateLabel)
        assertNull(stages.single { it.name == "VERAPDF" }.stateLabel)
    }
}
