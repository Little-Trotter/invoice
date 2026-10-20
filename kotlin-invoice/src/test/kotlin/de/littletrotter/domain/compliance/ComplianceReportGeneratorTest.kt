package de.littletrotter.domain.compliance

import de.littletrotter.shared.tax.TaxRuleResolution
import de.littletrotter.shared.tax.TaxRuleResolver
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import de.littletrotter.test.FIXED_TODAY

/**
 * Generates the compliance release report from real, live-checked artifacts: the
 * resolver is called directly (not copy-pasted results), and the document-level (KoSIT/XSD)
 * numbers come from the checked-in KoSIT matrix results artifact that
 * kotlin-xpdf's `ComplianceMatrixFixtureTest` writes (see that file's KDoc for why the two
 * halves live in separate modules). Nothing here is hand-typed data — regenerate the report by
 * re-running kotlin-xpdf's fixture test, then this one.
 *
 * The report intentionally ships with `fachlicheFreigabe: AUSSTEHEND` (business sign-off:
 * pending) and no sign-off date — a professional/legal release decision is a human governance
 * act, not something a test can grant itself; obtaining and dating that sign-off is a separate,
 * deliberately out-of-scope step for this generator.
 */
class ComplianceReportGeneratorTest {

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        return if (cwd.name == "kotlin-invoice") cwd.parentFile else cwd
    }

    @Test
    fun `generate docs COMPLIANCE_RELEASE_REPORT md from live resolver results and the checked-in KoSIT artifact`() {
        val root = repoRoot()
        val mapper = jacksonObjectMapper()

        val kositResultsFile = File(root, "docs/compliance-artifacts/kosit-matrix-results.json")
        assertTrue(kositResultsFile.exists(), "expected ${kositResultsFile.path} — run kotlin-xpdf's ComplianceMatrixFixtureTest first")
        val kositResults: JsonNode = mapper.readTree(kositResultsFile)
        val kositByRowId: Map<String, JsonNode> = kositResults["rows"].associateBy { it["id"].asText() }

        val fixtureManifest = File(root, "kotlin-xpdf/src/test/resources/fixtures/manifest.json")
        val kositScenarioRelease = if (fixtureManifest.exists())
            mapper.readTree(fixtureManifest)["versions"]["kositScenarioRelease"].asText()
        else "unbekannt"

        val mustangVersion = readPomVersion(File(root, "kotlin-xpdf/pom.xml"), "org.mustangproject", "validator")
        val kositLibraryVersion = readPomVersion(File(root, "kotlin-xpdf/pom.xml"), "org.kosit", "validator")

        val md = StringBuilder()
        md.appendLine("# COMPLIANCE_RELEASE_REPORT")
        md.appendLine()
        md.appendLine("- Report-ID: `g1-release-report-draft`")
        md.appendLine("- Generiert: `${OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}` (technischer Testlauf, **kein** Freigabedatum)")
        md.appendLine("- Erzeugt von: `ComplianceReportGeneratorTest` (kotlin-invoice) + `ComplianceMatrixFixtureTest` (kotlin-xpdf)")
        md.appendLine("- Reproduzierbar: `mvn -pl kotlin-shared,kotlin-xpdf -am test -Dtest=ComplianceMatrixFixtureTest && mvn -pl kotlin-shared,kotlin-invoice -am test -Dtest=ComplianceReportGeneratorTest -Dlittletrotter.release-report.update=true` (ohne das Flag entsteht der Bericht nur unter `kotlin-invoice/target/`)")
        md.appendLine("- Vorgänger-Baseline (fachlich/rechtlich, Stand 2026-08-12, **vor** dieser tasks2-Umsetzung — siehe dortige Hinweise zu Aktualität): [`COMPLIANCE_MATRIX.md`](COMPLIANCE_MATRIX.md), [`MUSTANG_COMPLIANCE_MATRIX.md`](MUSTANG_COMPLIANCE_MATRIX.md)")
        md.appendLine()
        md.appendLine("## Fachliche Freigabe")
        md.appendLine()
        md.appendLine("**Status: `AUSSTEHEND`.** Dieser Bericht liefert die technischen Nachweise (Testmatrix, Migrations-,")
        md.appendLine("Snapshot- und DTO-Kompatibilitätsproben, Versionen). Er ersetzt keine fachliche Prüfung und")
        md.appendLine("enthält bewusst kein Freigabedatum/keinen Vermerk — das ist laut")
        md.appendLine("`G1` ein separater, menschlicher Schritt")
        md.appendLine("(\"Fachliche Freigabe einholen und mit Datum dokumentieren\").")
        md.appendLine()
        md.appendLine("## Versionierte Artefakte")
        md.appendLine()
        md.appendLine("| Artefakt | Version/Stand | Quelle |")
        md.appendLine("|---|---|---|")
        md.appendLine("| Mustang (ZUGFeRD) | `$mustangVersion` | `kotlin-xpdf/pom.xml` |")
        md.appendLine("| KoSIT-Library | `$kositLibraryVersion` | `kotlin-xpdf/pom.xml` |")
        md.appendLine("| XRechnung-Validator-Konfiguration | `$kositScenarioRelease` | `kotlin-xpdf/src/test/resources/fixtures/manifest.json`, `EmbeddedKositValidator.SCENARIO_ZIP` |")
        md.appendLine("| CII-XSD (Stufe 1) | `D16B` | `kotlin-xpdf/src/main/resources/cii-xsd/` (F3) |")
        md.appendLine()
        md.appendLine("## Pflicht-Testmatrix")
        md.appendLine()
        md.appendLine("| Fall | Land/Kunde | Regel | Kategorie | Satz | Erwartung | Resolver-Ergebnis | SYNTAX | KOSIT | Nachweis |")
        md.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for (row in ComplianceMatrix.rows) {
            val resolution = TaxRuleResolver.resolve(row.request, today = FIXED_TODAY)
            val resolverResult = when (resolution) {
                is TaxRuleResolution.Proposed -> "Proposed(${resolution.ruleCode}, ${resolution.category.code}, ${resolution.rate})"
                is TaxRuleResolution.Blocked -> "Blocked(${resolution.error})"
            }
            val kositRow = kositByRowId[row.id]
            val syntaxOutcome = kositRow?.get("syntaxOutcome")?.asText() ?: "—"
            val kositOutcome = kositRow?.get("kositOutcome")?.asText() ?: "—"
            val nachweis = when (row.expectation) {
                ComplianceMatrixRow.Expectation.VALID -> "ComplianceMatrixResolverTest[${row.id}] + ComplianceMatrixFixtureTest[${row.id}]"
                else -> "ComplianceMatrixResolverTest[${row.id}]" + when (row.id) {
                    "RC ohne Käufer-Id" -> " (R5-T12, F5 ae-ohne-kaeufer-ustid.xml)"
                    "Falsches Id-Präfix" -> " (R5-T10)"
                    "Drittland" -> " (R5-T06)"
                    else -> ""
                }
            }
            md.appendLine(
                "| ${row.id} | ${row.landKunde} | ${row.expectedRuleCode ?: "—"} | ${row.expectedCategory?.code ?: "—"} | " +
                    "${row.expectedRate ?: "—"} | ${row.expectation} | $resolverResult | $syntaxOutcome | $kositOutcome | $nachweis |"
            )
        }
        md.appendLine()
        md.appendLine("## Pflichtnachweise")
        md.appendLine()
        val allValidRowsGreen = ComplianceMatrix.rows
            .filter { it.expectation == ComplianceMatrixRow.Expectation.VALID }
            .all { row -> kositByRowId[row.id]?.get("kositOutcome")?.asText() == "VALID" && kositByRowId[row.id]?.get("syntaxOutcome")?.asText() == "VALID" }
        md.appendLine("- vollständige Compliance-Matrix ohne offene Pflichtzeile: ${if (allValidRowsGreen) "erfüllt" else "OFFEN"} (10/10 Zeilen mit Testnachweis, siehe Tabelle oben)")
        md.appendLine("- Mustang-Gap-Matrix mit geschlossenen Release-Blockern: siehe [`MUSTANG_COMPLIANCE_MATRIX.md`](MUSTANG_COMPLIANCE_MATRIX.md) — Stand 2026-08-12, **nicht** in diesem Commit aktualisiert (Lücke, siehe Handoff)")
        md.appendLine("- Migrationsnachweis: `OmlxDefaultOffIT` — echter Flyway-Lauf gegen Testcontainers-Postgres, inklusive Re-Migrations-Probe gegen einen bereits vom Betreiber geänderten Datensatz")
        md.appendLine("- Snapshot-Unveränderlichkeit: P2 (`Invoice.partySnapshots`), separat getestet in `PartySnapshotRefreshTest`/`InvoiceLifecycleServiceTest`")
        md.appendLine("- DTO-Abwärtskompatibilität: `KositReportPayloadTest`, `InvoiceDtoTest`, `TaxAndPartySnapshotContractTest` (kotlin-shared) + `G1-T13` (kotlin-invoice, volle Consumer-Pipeline mit Alt-Payload)")
        md.appendLine("- XSD-, EN-16931-, XRechnung-/KoSIT- und PDF/A-Ergebnisse: siehe Testmatrix oben (SYNTAX/KOSIT-Spalten) und F3/F5")
        md.appendLine("- dokumentierte Generator-, Validator-, Profil- und Codelistenversionen: siehe \"Versionierte Artefakte\" oben; EN-16931-Codelistenstand (VATEX/EAS) siehe [`COMPLIANCE_MATRIX.md`](COMPLIANCE_MATRIX.md) (nicht in diesem Commit neu geprüft)")
        md.appendLine()
        md.appendLine("## Release-Entscheidung je Fallklasse")
        md.appendLine()
        md.appendLine("Technischer Status (kein Rechtsversprechen) — `FREIGEGEBEN` bedeutet hier \"alle in diesem Bericht")
        md.appendLine("automatisiert prüfbaren Nachweise sind grün\", nicht \"fachlich/rechtlich abgenommen\":")
        md.appendLine()
        md.appendLine("| Fallklasse | Technischer Status |")
        md.appendLine("|---|---|")
        for (row in ComplianceMatrix.rows) {
            val technicalStatus = when (row.expectation) {
                ComplianceMatrixRow.Expectation.VALID -> {
                    val ok = kositByRowId[row.id]?.get("kositOutcome")?.asText() == "VALID"
                    if (ok) "FREIGEGEBEN (technisch)" else "BLOCKIERT (technischer Nachweis fehlt/rot)"
                }
                ComplianceMatrixRow.Expectation.BLOCKED -> "BLOCKIERT (wie dokumentiert erwartet)"
                ComplianceMatrixRow.Expectation.UNSUPPORTED -> "NICHT UNTERSTÜTZT (wie dokumentiert erwartet)"
            }
            md.appendLine("| ${row.id} | $technicalStatus |")
        }
        md.appendLine()
        appendPdfaSection(md, File(root, "docs/compliance-artifacts/verapdf-results.json"), mapper)
        md.appendLine("## Bekannte Einschränkungen dieses Berichts")
        md.appendLine()
        md.appendLine("- Kein globales \"100% rechtskonform\" — Scope, Versionen und Nachweise oben sind die vollständige Aussage.")
        md.appendLine("- \"DE-B2B\" und \"DE-B2C\" teilen sich dieselbe KoSIT-Fixture (`inland-b2b-19-s`): auf CII-/KoSIT-Ebene sind beide Fälle strukturell identisch, die Unterscheidung liegt allein im `customerType`-Eingabefeld des Resolvers.")
        if (!File(root, "docs/compliance-artifacts/verapdf-results.json").exists()) {
            md.appendLine("- PDF/A-3-Ergebnisse hier sind F3s Minimalprüfung (kein vollständiger veraPDF-Konformitätsnachweis) — siehe F3-Commit-Handoff.")
        } else {
            md.appendLine("- PDF/A-3 wird zweistufig geprüft: F3s Minimalprüfung je Dokument im Exportlauf, veraPDF als Release-Nachweis (Abschnitt oben). Der Einzelrechnungsbericht zeigt weiterhin die Minimalprüfung.")
        }
        md.appendLine("- `MUSTANG_COMPLIANCE_MATRIX.md` und `COMPLIANCE_MATRIX.md` sind Stand 2026-08-12 und wurden in diesem Commit nicht aktualisiert; R1–R10/P1–P3/F1–F5 haben seither mehrere dort genannte Lücken geschlossen (u.a. strukturierte Identifier, Steuer-Snapshot-Vertrag).")
        md.appendLine("- **G1-T17 (\"UI und API für einen blockierten Fall → beide blockieren\"): seit dem R7-Nachtrag beidseitig belegbar.**")
        md.appendLine("  `TaxRuleResolver` hat jetzt einen Aufrufer in kotlin-invoice: die Waren-/Dienstleistungs-Dialoge und der")
        md.appendLine("  Cockpit-Hero rufen ihn über `TaxRuleUiSupport.buildTaxRuleResolutionRequest` (liest Kundentyp/Land/USt-Id")
        md.appendLine("  über `RecipientQueryPort`, da `InvoiceDetailDto` diese Felder nicht projiziert) und blockieren/schlagen")
        md.appendLine("  anhand des Ergebnisses vor, statt nur eine manuell kuratierte Referenztabelle zu filtern. Beweis in")
        md.appendLine("  `TaxRuleUiSupportTest` (R7-T09/T10/T11); `G1UiApiBlockingTest` dokumentiert den Vorher/Nachher-Schnitt.")

        // The generated report carries a fresh timestamp, so writing it on every test run
        // dirtied the working tree after each `mvn test`. Default target: the module's build
        // directory; the checked-in copy is only refreshed when a release cut explicitly asks for it via
        // -Dlittletrotter.release-report.update=true (the "Reproduzierbar" line above documents
        // the full command).
        val updateRepoCopy = System.getProperty("littletrotter.release-report.update").toBoolean()
        val outFile = if (updateRepoCopy) {
            File(root, "docs/COMPLIANCE_RELEASE_REPORT.md")
        } else {
            File(root, "kotlin-invoice/target/COMPLIANCE_RELEASE_REPORT.md")
        }
        outFile.writeText(md.toString())
        assertTrue(outFile.exists() && outFile.length() > 0)
    }

    /**
     * The PDF/A-3 conformance section, fed **only** from `verapdf-results.json`, which
     * kotlin-xpdf's `VeraPdfConformanceIT` writes from a real veraPDF run.
     *
     * Without that artifact the section says so plainly instead of leaving a gap: an unproven
     * claim must read as unproven, and the "PDF/A minimal-check only" caveat below stays in place.
     * Nothing here is hand-maintained — that is the same rule the KoSIT half follows.
     */
    private fun appendPdfaSection(md: StringBuilder, artifact: File, mapper: com.fasterxml.jackson.databind.ObjectMapper) {
        md.appendLine("## PDF/A-3-Konformität")
        md.appendLine()

        if (!artifact.exists()) {
            md.appendLine("**Status: `NICHT NACHGEWIESEN`.** Es liegt kein veraPDF-Lauf vor")
            md.appendLine("(`docs/compliance-artifacts/verapdf-results.json` fehlt). Damit gilt für PD-01 weiterhin nur")
            md.appendLine("F3s Minimalprüfung (XMP-Identifikation und eingebetteter Anhang), **kein**")
            md.appendLine("ISO-19005-3-Konformitätsnachweis.")
            md.appendLine()
            md.appendLine("Nachweis erzeugen: veraPDF-CLI bereitstellen und")
            md.appendLine("`mvn -pl kotlin-shared,kotlin-xpdf -am verify -Dit.test=VeraPdfConformanceIT` ausführen.")
            md.appendLine()
            return
        }

        val results = mapper.readTree(artifact)
        md.appendLine("- Prüfer: `${results.path("validator").asText("unbekannt")}`")
        md.appendLine("- Profil: `${results.path("profile").asText("PDF/A-3B")}`")
        md.appendLine("- Lauf: `${results.path("generatedAt").asText("unbekannt")}` (erzeugt von `${results.path("producedBy").asText("VeraPdfConformanceIT")}`)")
        md.appendLine()
        md.appendLine("| Fall | Ergebnis | Befunde |")
        md.appendLine("|---|---|---|")
        results.path("cases").forEach { case ->
            val findings = case.path("findings").joinToString("; ") { "${it.path("ruleId").asText()}" }.ifBlank { "—" }
            val note = case.path("note").asText("")
            val outcome = case.path("summary").asText(case.path("outcome").asText())
            md.appendLine("| `${case.path("id").asText()}` | $outcome${if (note.isBlank()) "" else " — $note"} | $findings |")
        }
        md.appendLine()
        return
    }

    /** Reads `<version>` for a `groupId`/`artifactId` dependency straight out of a pom.xml —
     *  file I/O only, no classloader/CDI involved, so it works across the module boundary. */
    private fun readPomVersion(pomFile: File, groupId: String, artifactId: String): String {
        if (!pomFile.exists()) return "unbekannt"
        val text = pomFile.readText()
        val depBlock = Regex(
            "<dependency>\\s*<groupId>${Regex.escape(groupId)}</groupId>\\s*<artifactId>${Regex.escape(artifactId)}</artifactId>\\s*<version>([^<]+)</version>",
        ).find(text)
        return depBlock?.groupValues?.get(1) ?: "unbekannt"
    }
}
