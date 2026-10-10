package de.littletrotter.xpdf.impl

import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.VeraPdfResult
import de.littletrotter.shared.process.ProcessResult
import de.littletrotter.shared.process.ProcessRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * V2-T01…T03 — parsing and finding mapping for the veraPDF CLI wrapper, without needing the CLI.
 *
 * The samples are veraPDF's real `--format json` shape; the point of these tests is that a
 * conformance result is read out of the engine's own output rather than re-derived, and that an
 * engine failure never masquerades as a document defect.
 */
class VeraPdfCliValidatorTest {

    @TempDir
    lateinit var tmp: Path

    /** A validator whose CLI path cannot exist — used where no process should be needed. */
    private fun validator() = VeraPdfCliValidator(
        cliPath = "verapdf-does-not-exist-here",
        pinnedVersion = "1.30.2",
    )

    private fun fieldValue(target: Any, name: String): Any? =
        VeraPdfCliValidator::class.java.getDeclaredField(name).apply { isAccessible = true }.get(target)

    // ─── constructor defaults (VERAPDF_* env vars) ─────────────────────────────

    @Test
    fun `boundary - the CDI constructor reads all VERAPDF_ env vars when present`() {
        org.mockito.Mockito.mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("VERAPDF_CLI") }.thenReturn("myverapdf")
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("VERAPDF_VERSION") }.thenReturn("9.9.9")
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("VERAPDF_TIMEOUT") }.thenReturn("77")
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("VERAPDF_CACHE_ENABLED") }.thenReturn("false")
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("VERAPDF_CACHE_MAX_ENTRIES") }.thenReturn("5")

            val validator = VeraPdfCliValidator()

            assertEquals("myverapdf", fieldValue(validator, "cliPath"))
            assertEquals("9.9.9", fieldValue(validator, "pinnedVersion"))
            assertEquals(77L, fieldValue(validator, "timeoutSeconds"))
            assertEquals(false, fieldValue(validator, "cacheEnabled"))
            assertEquals(5, fieldValue(validator, "cacheMaxEntries"))
        }
    }

    @Test
    fun `boundary - the CDI constructor falls back to defaults when no VERAPDF_ env vars are set`() {
        org.mockito.Mockito.mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use {
            val validator = VeraPdfCliValidator()

            assertEquals("verapdf", fieldValue(validator, "cliPath"))
            assertEquals(VeraPdfCliValidator.DEFAULT_VERSION, fieldValue(validator, "pinnedVersion"))
            assertEquals(120L, fieldValue(validator, "timeoutSeconds"))
            assertEquals(true, fieldValue(validator, "cacheEnabled"))
            assertEquals(VeraPdfCliValidator.DEFAULT_CACHE_MAX_ENTRIES, fieldValue(validator, "cacheMaxEntries"))
        }
    }

    // ─── staging the temp file (validate(ByteArray)) ───────────────────────────

    @Test
    fun `boundary - validate reports an engine error when staging the temp file fails`() {
        val validator = validator()
        val field = VeraPdfCliValidator::class.java.getDeclaredField("tempFileWriter").apply { isAccessible = true }
        field.set(validator, { _: Path, _: ByteArray -> throw java.io.IOException("disk full") })

        val result = validator.validate(byteArrayOf(0x25, 0x50, 0x44, 0x46))

        assertEquals(KositOutcome.ERROR, result.outcome)
        assertTrue(
            result.engineError!!.contains("nicht für die Prüfung ablegen"),
            "was: ${result.engineError}",
        )
    }

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
    fun `V2-T02 - a missing CLI is an engine ERROR, never INVALID`() {
        val result = validator().validate(byteArrayOf(0x25, 0x50, 0x44, 0x46))

        assertEquals(
            KositOutcome.ERROR, result.outcome,
            "a check that could not run must never be reported as a wrong document",
        )
        assertNotNull(result.engineError)
        assertTrue(result.findings.isEmpty(), "an engine failure produces no document findings")
        assertTrue(result.summary().startsWith("nicht geprüft"), "summary was: ${result.summary()}")
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
    fun `boundary - genuinely unparsable veraPDF stdout (not just empty or job-less JSON) is an engine ERROR, not an uncaught exception`() {
        // The "unusable output" test above feeds parse() valid-JSON-but-semantically-empty input,
        // which parse() itself turns into an engineError() without ever throwing. Syntactically
        // broken JSON is different: mapper.readTree() throws, and only runValidation's own
        // try/catch around parse() (not parse() itself) converts that into a result rather than
        // letting the exception escape to the caller. A fake ProcessRunner reaches that path
        // without needing a real veraPDF CLI on the test host.
        val fakeRunner: ProcessRunner = mock()
        whenever(fakeRunner.run(any(), any(), any(), anyOrNull()))
            .thenReturn(ProcessResult(exitCode = 0, stdout = "not valid json {{{", stderr = "", timedOut = false))
        val validator = VeraPdfCliValidator(
            cliPath = "irrelevant-never-invoked",
            pinnedVersion = "1.30.2",
            processRunner = fakeRunner,
        )

        val result = validator.validate(byteArrayOf(0x25, 0x50, 0x44, 0x46))

        assertEquals(KositOutcome.ERROR, result.outcome, "unparsable output must never masquerade as a document defect")
        assertTrue(
            result.engineError!!.contains("nicht auswertbar"),
            "reason must say the output could not be evaluated, was: ${result.engineError}",
        )
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
    fun `the pinned version is reported when no CLI is installed`() {
        // A conformance claim must name the engine that produced it; without an engine the wrapper
        // still reports which version it is pinned to, so a report can never look version-less.
        assertEquals("veraPDF 1.30.2", validator().validatorId())
        assertTrue(!validator().isAvailable())
    }

    // ─── IMP-4 — the timeout must actually take effect ──────────────────

    /**
     * A CLI stand-in that ignores its arguments, writes nothing, and never exits — it holds the
     * output pipe open this way. Exactly this kind of process could previously hang forever:
     * reading `stdout` to the end of the pipe **before** calling `waitFor(timeout)`. A process
     * that never writes and never exits never reaches the end of the pipe; the timeout existed in
     * the code but was never reached.
     */
    private fun hangingCliScript(): Path {
        val script = Files.createTempFile(tmp, "verapdf-hangs-", ".sh")
        Files.writeString(script, "#!/bin/sh\n# ignoriert alle Argumente und endet nie\nsleep 60\n")
        check(script.toFile().setExecutable(true)) { "Testskript liess sich nicht ausfuehrbar machen" }
        return script
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the test script is a POSIX shell script
    fun `IMP-4 - ein haengendes CLI wird nach dem Timeout abgebrochen, statt den Aufrufer zu blockieren`() {
        val script = hangingCliScript()
        val pdf = Files.createTempFile(tmp, "imp4-", ".pdf")
        try {
            Files.write(pdf, byteArrayOf(1, 2, 3))
            val validator = VeraPdfCliValidator(
                cliPath = script.toString(),
                pinnedVersion = "1.30.2",
                timeoutSeconds = 1L,
            )

            // The call must return on its own. If the timeout didn't take effect, it would run for
            // 60s (the script) or indefinitely (the old version) -- the bound turns that into a
            // test failure instead of a hanging build.
            // ThrowingSupplier explicit: otherwise Kotlin picks the Executable overload and the
            // return value would be Unit instead of the result.
            val result: VeraPdfResult = assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                ThrowingSupplier { validator.validate(pdf.toFile()) },
            )

            // "The check could not run" is not "the document is wrong" (F1).
            assertEquals(KositOutcome.ERROR, result.outcome)
            assertTrue(
                result.engineError!!.contains("Timeout"),
                "der Abbruchgrund muss als Timeout erkennbar sein, nicht als Dokumentbefund: ${result.engineError}",
            )
            assertTrue(result.findings.isEmpty(), "ein Engine-Fehler erzeugt keine Dokumentbefunde")
        } finally {
            Files.deleteIfExists(pdf)
            Files.deleteIfExists(script)
        }
    }

    // ─── veraPDF result cache keyed on the document hash ─────────────────────────────────

    /**
     * A CLI stand-in that answers `--version` immediately (without counting it) and logs every
     * *validation* call to [counterFile] before emitting [jsonFile]. This counts exactly the
     * expensive validation runs P4 is meant to save — not the already-once, cheap version
     * detection (`detectedVersion` is itself already `by lazy`). Same pattern as
     * [hangingCliScript]: a real script instead of a mocked
     * [de.littletrotter.shared.process.ProcessRunner], because that has no open interface for a
     * test fake.
     */
    private fun countingCliScript(counterFile: Path, jsonFile: Path): Path {
        val script = Files.createTempFile(tmp, "verapdf-counts-", ".sh")
        Files.writeString(
            script,
            """
            #!/bin/sh
            if [ "${'$'}1" = "--version" ]; then
              echo "1.30.2"
              exit 0
            fi
            echo x >> "$counterFile"
            cat "$jsonFile"
            exit 0

            """.trimIndent() + "\n",
        )
        check(script.toFile().setExecutable(true)) { "Testskript liess sich nicht ausfuehrbar machen" }
        return script
    }

    /** Like [countingCliScript], except every validation call ends with an engine failure (exit
     *  code 2 — "the process itself is broken", not a document finding). */
    private fun erroringCliScript(counterFile: Path): Path {
        val script = Files.createTempFile(tmp, "verapdf-errors-", ".sh")
        Files.writeString(
            script,
            """
            #!/bin/sh
            if [ "${'$'}1" = "--version" ]; then
              echo "1.30.2"
              exit 0
            fi
            echo x >> "$counterFile"
            exit 2

            """.trimIndent() + "\n",
        )
        check(script.toFile().setExecutable(true)) { "Testskript liess sich nicht ausfuehrbar machen" }
        return script
    }

    private fun processStarts(counterFile: Path): Int =
        if (Files.exists(counterFile)) Files.readAllLines(counterFile).count { it.isNotBlank() } else 0

    @Test
    @DisabledOnOs(OS.WINDOWS) // the test script is a POSIX shell script
    fun `ARC-4 P4 - dieselbe Datei zweimal validieren startet den Pruefprozess nur einmal`() {
        val counter = Files.createTempFile(tmp, "verapdf-counter-", ".txt")
        val json = Files.createTempFile(tmp, "verapdf-json-", ".json")
        val script = countingCliScript(counter, json)
        val pdf = Files.createTempFile(tmp, "cache-same-", ".pdf")
        try {
            Files.writeString(json, compliantJson)
            Files.write(pdf, byteArrayOf(1, 2, 3, 4))
            val validator = VeraPdfCliValidator(cliPath = script.toString(), pinnedVersion = "1.30.2")

            val first = validator.validate(pdf.toFile())
            val second = validator.validate(pdf.toFile())

            assertEquals(KositOutcome.VALID, first.outcome)
            assertEquals(KositOutcome.VALID, second.outcome)
            assertEquals(
                1, processStarts(counter),
                "der zweite Lauf mit unveraendertem Inhalt haette aus dem Cache kommen muessen",
            )
        } finally {
            listOf(pdf, counter, json, script).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the test script is a POSIX shell script
    fun `ARC-4 P4 - ein geaendertes Byte erzwingt einen zweiten Pruefprozess`() {
        val counter = Files.createTempFile(tmp, "verapdf-counter-", ".txt")
        val json = Files.createTempFile(tmp, "verapdf-json-", ".json")
        val script = countingCliScript(counter, json)
        val pdfA = Files.createTempFile(tmp, "cache-a-", ".pdf")
        val pdfB = Files.createTempFile(tmp, "cache-b-", ".pdf")
        try {
            Files.writeString(json, compliantJson)
            Files.write(pdfA, byteArrayOf(1, 2, 3, 4))
            Files.write(pdfB, byteArrayOf(1, 2, 3, 5)) // exactly one byte different
            val validator = VeraPdfCliValidator(cliPath = script.toString(), pinnedVersion = "1.30.2")

            validator.validate(pdfA.toFile())
            validator.validate(pdfB.toFile())

            assertEquals(
                2, processStarts(counter),
                "unterschiedliche Bytes duerfen niemals denselben Cache-Treffer liefern",
            )
        } finally {
            listOf(pdfA, pdfB, counter, json, script).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the test script is a POSIX shell script
    fun `ARC-4 P4 - ein technischer Fehlschlag (ERROR) wird nie zwischengespeichert`() {
        // An engine failure is not a document finding (see the class doc) and must not survive
        // long enough that a repaired installation still looks broken. Test: calling the same
        // broken run twice must start a real process twice -- if ERROR were cached, the counter
        // would still read 1 after the second call.
        val counter = Files.createTempFile(tmp, "verapdf-counter-", ".txt")
        val script = erroringCliScript(counter)
        val pdf = Files.createTempFile(tmp, "cache-error-", ".pdf")
        try {
            Files.write(pdf, byteArrayOf(9, 9, 9))
            val validator = VeraPdfCliValidator(cliPath = script.toString(), pinnedVersion = "1.30.2")

            val first = validator.validate(pdf.toFile())
            val second = validator.validate(pdf.toFile())

            assertEquals(KositOutcome.ERROR, first.outcome)
            assertEquals(KositOutcome.ERROR, second.outcome)
            assertEquals(
                2, processStarts(counter),
                "ein ERROR darf nie aus dem Cache kommen, sonst saehe eine reparierte Installation " +
                    "weiter kaputt aus",
            )
        } finally {
            listOf(pdf, counter, script).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the test script is a POSIX shell script
    fun `ARC-4 P4 - cacheEnabled = false (VERAPDF_CACHE_ENABLED) schaltet den Cache ab`() {
        val counter = Files.createTempFile(tmp, "verapdf-counter-", ".txt")
        val json = Files.createTempFile(tmp, "verapdf-json-", ".json")
        val script = countingCliScript(counter, json)
        val pdf = Files.createTempFile(tmp, "cache-disabled-", ".pdf")
        try {
            Files.writeString(json, compliantJson)
            Files.write(pdf, byteArrayOf(7, 7, 7))
            val validator = VeraPdfCliValidator(
                cliPath = script.toString(),
                pinnedVersion = "1.30.2",
                cacheEnabled = false,
            )

            validator.validate(pdf.toFile())
            validator.validate(pdf.toFile())

            assertEquals(
                2, processStarts(counter),
                "bei abgeschaltetem Cache muss jeder Aufruf einen echten Prozess starten",
            )
        } finally {
            listOf(pdf, counter, json, script).forEach { Files.deleteIfExists(it) }
        }
    }

    // ─── probeVersion() branches — non-regex version strings, failing/hanging --version ─────────

    /** `--version` prints something with no `d+.d+.d+` inside it — the regex fallback path. */
    private fun nonNumericVersionCliScript(): Path {
        val script = Files.createTempFile(tmp, "verapdf-nonnumeric-version-", ".sh")
        Files.writeString(
            script,
            "#!/bin/sh\nif [ \"\$1\" = \"--version\" ]; then\n  echo \"veraPDF development build\"\n  exit 0\nfi\nexit 1\n",
        )
        check(script.toFile().setExecutable(true)) { "Testskript liess sich nicht ausfuehrbar machen" }
        return script
    }

    /** `--version` prints nothing at all — both stdout and stderr are blank. */
    private fun blankVersionCliScript(): Path {
        val script = Files.createTempFile(tmp, "verapdf-blank-version-", ".sh")
        Files.writeString(
            script,
            "#!/bin/sh\nif [ \"\$1\" = \"--version\" ]; then\n  exit 0\nfi\nexit 1\n",
        )
        check(script.toFile().setExecutable(true)) { "Testskript liess sich nicht ausfuehrbar machen" }
        return script
    }

    /** `--version` fails with a non-zero exit code but returns promptly (no timeout involved). */
    private fun failingVersionCliScript(): Path {
        val script = Files.createTempFile(tmp, "verapdf-failing-version-", ".sh")
        Files.writeString(
            script,
            "#!/bin/sh\nif [ \"\$1\" = \"--version\" ]; then\n  echo \"boom\" >&2\n  exit 3\nfi\nexit 1\n",
        )
        check(script.toFile().setExecutable(true)) { "Testskript liess sich nicht ausfuehrbar machen" }
        return script
    }

    /** `--version` never returns — used with a very small [VeraPdfCliValidator.probeTimeoutSeconds]. */
    private fun hangingVersionCliScript(): Path {
        val script = Files.createTempFile(tmp, "verapdf-hanging-version-", ".sh")
        Files.writeString(script, "#!/bin/sh\nsleep 30\n")
        check(script.toFile().setExecutable(true)) { "Testskript liess sich nicht ausfuehrbar machen" }
        return script
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a version banner without a dotted-triple number is reported verbatim`() {
        val script = nonNumericVersionCliScript()
        try {
            val validator = VeraPdfCliValidator(cliPath = script.toString(), pinnedVersion = "1.30.2")
            assertEquals("veraPDF development build", validator.installedVersion())
            assertTrue(validator.isAvailable())
        } finally {
            Files.deleteIfExists(script)
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a completely blank version banner means no engine is available`() {
        val script = blankVersionCliScript()
        try {
            val validator = VeraPdfCliValidator(cliPath = script.toString(), pinnedVersion = "1.30.2")
            assertNull(validator.installedVersion())
            assertTrue(!validator.isAvailable())
            assertEquals("veraPDF 1.30.2", validator.validatorId())
        } finally {
            Files.deleteIfExists(script)
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a non-zero exit code from --version means no engine is available`() {
        val script = failingVersionCliScript()
        try {
            val validator = VeraPdfCliValidator(cliPath = script.toString(), pinnedVersion = "1.30.2")
            assertNull(validator.installedVersion())
            assertTrue(!validator.isAvailable())
        } finally {
            Files.deleteIfExists(script)
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a hanging --version probe times out and reports no engine, without blocking the caller`() {
        val script = hangingVersionCliScript()
        try {
            // timeoutSeconds = 1 -> probeTimeoutSeconds = min(30, 1) = 1s, so the probe itself is fast.
            val validator = VeraPdfCliValidator(cliPath = script.toString(), pinnedVersion = "1.30.2", timeoutSeconds = 1L)
            val version = assertTimeoutPreemptively(Duration.ofSeconds(15), ThrowingSupplier { validator.installedVersion() })
            assertNull(version)
        } finally {
            Files.deleteIfExists(script)
        }
    }

    // ─── runValidation() exit-code branches ──────────────────────────────────

    /** A real CLI stand-in that exits 1 (the "non-compliant but valid run" exit code) with a body. */
    private fun nonCompliantExitOneCliScript(jsonFile: Path): Path {
        val script = Files.createTempFile(tmp, "verapdf-exit1-", ".sh")
        Files.writeString(
            script,
            """
            #!/bin/sh
            if [ "${'$'}1" = "--version" ]; then
              echo "1.30.2"
              exit 0
            fi
            cat "$jsonFile"
            exit 1

            """.trimIndent() + "\n",
        )
        check(script.toFile().setExecutable(true)) { "Testskript liess sich nicht ausfuehrbar machen" }
        return script
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `runValidation treats exit code 1 as a real non-compliant result, not an engine failure`() {
        val json = Files.createTempFile(tmp, "verapdf-json-", ".json")
        val pdf = Files.createTempFile(tmp, "exit1-", ".pdf")
        val script = nonCompliantExitOneCliScript(json)
        try {
            Files.writeString(json, nonCompliantJson)
            Files.write(pdf, byteArrayOf(1, 2, 3))
            val validator = VeraPdfCliValidator(cliPath = script.toString(), pinnedVersion = "1.30.2")

            val result = validator.validate(pdf.toFile())

            assertEquals(KositOutcome.INVALID, result.outcome)
            assertEquals(2, result.findings.size)
        } finally {
            listOf(json, pdf, script).forEach { Files.deleteIfExists(it) }
        }
    }

    // ─── validate(ByteArray) cache path + cacheKeyFor() failure + LRU eviction ────────────────

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `validate(ByteArray) also serves the second call from the cache`() {
        val counter = Files.createTempFile(tmp, "verapdf-counter-", ".txt")
        val json = Files.createTempFile(tmp, "verapdf-json-", ".json")
        val script = countingCliScript(counter, json)
        try {
            Files.writeString(json, compliantJson)
            val validator = VeraPdfCliValidator(cliPath = script.toString(), pinnedVersion = "1.30.2")
            val bytes = byteArrayOf(9, 8, 7, 6)

            val first = validator.validate(bytes)
            val second = validator.validate(bytes)

            assertEquals(KositOutcome.VALID, first.outcome)
            assertEquals(KositOutcome.VALID, second.outcome)
            assertEquals(1, processStarts(counter), "the second byte-array call must come from the cache")
        } finally {
            listOf(counter, json, script).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `validate(File) with an unreadable file skips the cache entirely instead of failing`() {
        val validator = validator()
        val missing = Files.createTempFile(tmp, "verapdf-will-vanish-", ".pdf")
        Files.delete(missing) // the path no longer exists -> Files.readAllBytes throws in cacheKeyFor

        val result = validator.validate(missing.toFile())

        // No cache key could be computed, so this can only be an engine error (no CLI here either) —
        // the point of this test is that cacheKeyFor()'s failure path (runCatching -> null) is taken
        // and both cache lookup and cache store are skipped without throwing.
        assertEquals(KositOutcome.ERROR, result.outcome)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `ARC-4 P4 - the LRU cap evicts the oldest entry once exceeded`() {
        val counter = Files.createTempFile(tmp, "verapdf-counter-", ".txt")
        val json = Files.createTempFile(tmp, "verapdf-json-", ".json")
        val script = countingCliScript(counter, json)
        val pdfA = Files.createTempFile(tmp, "lru-a-", ".pdf")
        val pdfB = Files.createTempFile(tmp, "lru-b-", ".pdf")
        try {
            Files.writeString(json, compliantJson)
            Files.write(pdfA, byteArrayOf(1, 1, 1))
            Files.write(pdfB, byteArrayOf(2, 2, 2))
            val validator = VeraPdfCliValidator(cliPath = script.toString(), pinnedVersion = "1.30.2", cacheMaxEntries = 1)

            validator.validate(pdfA.toFile()) // cache: [A]
            validator.validate(pdfB.toFile()) // cache cap 1 -> A evicted, cache: [B]
            validator.validate(pdfA.toFile()) // A no longer cached -> real process again

            assertEquals(3, processStarts(counter), "A must be evicted once the single-entry cap is exceeded")
        } finally {
            listOf(pdfA, pdfB, counter, json, script).forEach { Files.deleteIfExists(it) }
        }
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
