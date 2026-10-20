package de.littletrotter.xpdf.impl

import de.littletrotter.shared.KositOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parsing and finding mapping of veraPDF's JSON report, without needing the engine.
 *
 * The samples are veraPDF's real `--format json` shape; the point of these tests is that a
 * conformance result is read out of the engine's own output rather than re-derived, and that an
 * engine failure never masquerades as a document defect.
 */
class VeraPdfReportParserTest {

    private fun validator() = VeraPdfReportParser("veraPDF 1.30.2")

    private val compliantJson = """
        {
          "report": {
            "buildInformation": { "releaseDetails": [ { "id": "validation-model", "version": "1.30.2" } ] },
            "jobs": [
              {
                "itemDetails": { "name": "/tmp/invoice.pdf", "size": 45123 },
                "validationResult": {
                  "profileName": "PDF/A-3B validation profile",
                  "statement": "PDF file is compliant with Validation Profile requirements.",
                  "compliant": true,
                  "details": { "passedRules": 132, "failedRules": 0, "passedChecks": 5231, "failedChecks": 0, "ruleSummaries": [] }
                }
              }
            ]
          }
        }
    """.trimIndent()

    private val nonCompliantJson = """
        {
          "report": {
            "jobs": [
              {
                "itemDetails": { "name": "/tmp/broken.pdf", "size": 3311 },
                "validationResult": {
                  "profileName": "PDF/A-3B validation profile",
                  "statement": "PDF file is not compliant with Validation Profile requirements.",
                  "compliant": false,
                  "details": {
                    "passedRules": 130,
                    "failedRules": 2,
                    "passedChecks": 5100,
                    "failedChecks": 3,
                    "ruleSummaries": [
                      {
                        "specification": "ISO 19005-3:2012",
                        "clause": "6.3.5",
                        "testNumber": 2,
                        "ruleStatus": "FAILED",
                        "failedChecks": 2,
                        "description": "A font that is used for rendering shall be embedded.",
                        "object": "PDFont",
                        "checks": [
                          { "status": "FAILED", "context": "root/document[0]/pages[0]/contentStream[0]/operators[12]" }
                        ]
                      },
                      {
                        "specification": "ISO 19005-3:2012",
                        "clause": "6.2.2",
                        "testNumber": 1,
                        "ruleStatus": "FAILED",
                        "failedChecks": 1,
                        "description": "DeviceRGB shall only be used if an OutputIntent is present.",
                        "object": "PDDeviceRGB",
                        "checks": [ { "status": "FAILED", "context": "root/document[0]" } ]
                      }
                    ]
                  }
                }
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `V2-T01 - a compliant report is parsed into a typed VALID result`() {
        val result = validator().parse(compliantJson)

        assertEquals(KositOutcome.VALID, result.outcome)
        assertTrue(result.conformant)
        assertEquals("PDF/A-3B validation profile", result.profile)
        assertEquals(0, result.failedRuleCount)
        assertTrue(result.findings.isEmpty())
        assertNull(result.engineError)
        assertTrue(result.summary().startsWith("konform"), "summary was: ${result.summary()}")
    }

    @Test
    fun `V2-T01 - a non-compliant report yields one finding per failed rule, with its clause`() {
        val result = validator().parse(nonCompliantJson)

        assertEquals(KositOutcome.INVALID, result.outcome)
        assertEquals(2, result.failedRuleCount)
        assertEquals(3, result.failedCheckCount)
        assertEquals(2, result.findings.size)

        val fontRule = result.findings.first { it.ruleId == "6.3.5-2" }
        assertTrue(fontRule.message.contains("embedded"), "the engine's own description must survive: ${fontRule.message}")
        assertTrue(fontRule.message.contains("6.3.5"), "the clause belongs in the message too")
        assertNotNull(fontRule.location, "veraPDF's context path is the finding's location")

        assertTrue(result.findings.any { it.ruleId == "6.2.2-1" }, "every failed rule must appear")
    }

    @Test
    fun `V2-T03 - findings carry the F1 contract fields, not a second format`() {
        val result = validator().parse(nonCompliantJson)

        result.findings.forEach { finding ->
            assertEquals("PDFA", finding.stage, "stage must be PDFA (F1's stage vocabulary)")
            assertTrue(
                finding.validator!!.startsWith("veraPDF "),
                "validator must name the engine and version, was: ${finding.validator}",
            )
            assertEquals("ERROR", finding.severity)
            assertNotNull(finding.ruleId, "the ISO clause is the rule id")
        }
    }

    @Test
    fun `V2-T02 - unusable output is an engine ERROR with a reason`() {
        val v = validator()

        listOf(
            "" to "keine Ausgabe",
            """{"report":{"jobs":[]}}""" to "keinen Prüflauf",
            """{"report":{"jobs":{}},"jobs":[]}""" to "keinen Prüflauf",
        ).forEach { (json, expectedFragment) ->
            val result = v.parse(json)
            assertEquals(KositOutcome.ERROR, result.outcome, "input was: $json")
            assertTrue(
                result.engineError!!.contains(expectedFragment),
                "reason must say what went wrong, was: ${result.engineError}",
            )
        }
    }

    @Test
    fun `V2-T02 - a job veraPDF could not process at all is an ERROR, not a defect`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "itemDetails": { "name": "/tmp/corrupt.pdf" },
                    "taskResult": { "type": "PARSE", "exceptionMessage": "Document is encrypted" }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertEquals(KositOutcome.ERROR, result.outcome)
        assertTrue(result.engineError!!.contains("Document is encrypted"), "was: ${result.engineError}")
    }

    @Test
    fun `parse handles jobs array at top level when report is missing`() {
        val json = """
            {
              "jobs": [
                {
                  "itemDetails": { "name": "/tmp/test.pdf" },
                  "validationResult": {
                    "profileName": "PDF/A-3B",
                    "compliant": true,
                    "details": { "failedRules": 0, "failedChecks": 0, "ruleSummaries": [] }
                  }
                }
              ]
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertEquals(KositOutcome.VALID, result.outcome)
        assertEquals("PDF/A-3B", result.profile)
    }

    @Test
    fun `parse with validationResult as array uses first element`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "itemDetails": { "name": "/tmp/test.pdf" },
                    "validationResult": [
                      {
                        "profileName": "PDF/A-3B",
                        "compliant": true,
                        "details": { "failedRules": 0, "failedChecks": 0, "ruleSummaries": [] }
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertEquals(KositOutcome.VALID, result.outcome)
    }

    @Test
    fun `parse with ruleStatus in lowercase FAILED still counts as failed`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "validationResult": {
                      "compliant": false,
                      "details": {
                        "failedRules": 1,
                        "failedChecks": 1,
                        "ruleSummaries": [
                          {
                            "clause": "6.2.1",
                            "testNumber": "1",
                            "ruleStatus": "failed",
                            "description": "Test rule"
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertEquals(KositOutcome.INVALID, result.outcome)
        assertEquals(1, result.findings.size)
    }

    @Test
    fun `parse with status field instead of ruleStatus still counts failures`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "validationResult": {
                      "compliant": false,
                      "details": {
                        "failedRules": 1,
                        "failedChecks": 1,
                        "ruleSummaries": [
                          {
                            "clause": "6.2.1",
                            "testNumber": "1",
                            "status": "FAILED",
                            "description": "Test rule"
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertEquals(KositOutcome.INVALID, result.outcome)
        assertEquals(1, result.findings.size)
    }

    @Test
    fun `parse with missing test number uses only clause for ruleId`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "validationResult": {
                      "compliant": false,
                      "details": {
                        "failedRules": 1,
                        "failedChecks": 1,
                        "ruleSummaries": [
                          {
                            "clause": "6.2.1",
                            "ruleStatus": "FAILED",
                            "description": "Test"
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertEquals(1, result.findings.size)
        assertEquals("6.2.1", result.findings[0].ruleId)
    }

    @Test
    fun `parse with missing clause and testNumber uses PDFA as fallback ruleId`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "validationResult": {
                      "compliant": false,
                      "details": {
                        "failedRules": 1,
                        "failedChecks": 1,
                        "ruleSummaries": [
                          {
                            "ruleStatus": "FAILED",
                            "description": "Generic failure"
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertEquals(1, result.findings.size)
        assertEquals("PDFA", result.findings[0].ruleId)
    }

    @Test
    fun `parse with empty checks array has null location`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "validationResult": {
                      "compliant": false,
                      "details": {
                        "failedRules": 1,
                        "failedChecks": 1,
                        "ruleSummaries": [
                          {
                            "clause": "6.2.1",
                            "testNumber": "1",
                            "ruleStatus": "FAILED",
                            "checks": [],
                            "description": "Test"
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertEquals(1, result.findings.size)
        assertNull(result.findings[0].location)
    }

    @Test
    fun `parse with missing specification defaults to ISO 19005-3`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "validationResult": {
                      "compliant": false,
                      "details": {
                        "failedRules": 1,
                        "failedChecks": 1,
                        "ruleSummaries": [
                          {
                            "clause": "6.2.1",
                            "ruleStatus": "FAILED",
                            "description": "Test"
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertTrue(result.findings[0].message.contains("ISO 19005-3"))
    }

    @Test
    fun `parse skips rule summaries that are not FAILED status`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "validationResult": {
                      "compliant": false,
                      "details": {
                        "failedRules": 1,
                        "failedChecks": 1,
                        "ruleSummaries": [
                          { "clause": "6.1.1", "testNumber": "1", "ruleStatus": "PASSED", "description": "ok rule" },
                          { "clause": "6.2.1", "testNumber": "2", "ruleStatus": "FAILED", "description": "bad rule" }
                        ]
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertEquals(1, result.findings.size, "only the FAILED rule summary becomes a finding")
        assertEquals("6.2.1-2", result.findings.single().ruleId)
    }

    @Test
    fun `parse treats an explicit null validationResult the same as a missing one`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "itemDetails": { "name": "/tmp/corrupt.pdf" },
                    "validationResult": null,
                    "taskResult": { "type": "PARSE" }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertEquals(KositOutcome.ERROR, result.outcome)
        assertTrue(result.engineError!!.contains("konnte das Dokument nicht"), "was: ${result.engineError}")
    }

    @Test
    fun `parse reports the plain could-not-process reason when the taskResult carries no exception message`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "itemDetails": { "name": "/tmp/corrupt.pdf" },
                    "taskResult": { "type": "PARSE" }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertEquals(KositOutcome.ERROR, result.outcome)
        assertEquals("veraPDF konnte das Dokument nicht prüfen", result.engineError)
    }

    @Test
    fun `parse with a checks entry that has a blank context still has a null location`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "validationResult": {
                      "compliant": false,
                      "details": {
                        "failedRules": 1,
                        "failedChecks": 1,
                        "ruleSummaries": [
                          {
                            "clause": "6.2.1",
                            "testNumber": "1",
                            "ruleStatus": "FAILED",
                            "checks": [ { "status": "FAILED", "context": "  " } ],
                            "description": "Test"
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertNull(result.findings.single().location)
    }

    @Test
    fun `parse with no checks field at all has a null location`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "validationResult": {
                      "compliant": false,
                      "details": {
                        "failedRules": 1,
                        "failedChecks": 1,
                        "ruleSummaries": [
                          { "clause": "6.2.1", "testNumber": "1", "ruleStatus": "FAILED", "description": "Test" }
                        ]
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertNull(result.findings.single().location)
    }

    @Test
    fun `parse with missing description omits it from the message`() {
        val json = """
            {
              "report": {
                "jobs": [
                  {
                    "validationResult": {
                      "compliant": false,
                      "details": {
                        "failedRules": 1,
                        "failedChecks": 1,
                        "ruleSummaries": [
                          {
                            "clause": "6.2.1",
                            "testNumber": "1",
                            "ruleStatus": "FAILED"
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = validator().parse(json)

        assertEquals(1, result.findings.size)
        // Should not contain the engine's description
        assertTrue(!result.findings[0].message.contains("zzz"), "descriptive message should be built")
    }
}
