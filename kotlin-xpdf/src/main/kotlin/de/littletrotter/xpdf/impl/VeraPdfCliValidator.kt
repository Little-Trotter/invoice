package de.littletrotter.xpdf.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationToolchain
import de.littletrotter.shared.VeraPdfResult
import de.littletrotter.shared.VeraPdfValidatorPort
import de.littletrotter.shared.process.ProcessResult
import de.littletrotter.shared.process.ProcessRunner
import de.littletrotter.xpdf.readEnv
import jakarta.enterprise.context.ApplicationScoped
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.Collections

/** Default validation-run deadline when `VERAPDF_TIMEOUT` is unset. */
private const val DEFAULT_TIMEOUT_SECONDS = 120L

/** How many characters of the CLI's stderr to keep in an "unexpected exit code" finding. */
private const val STDERR_PREVIEW_LENGTH = 500

/**
 * PDF/A-3 conformance through the **veraPDF CLI**, as a separate process.
 *
 * Why a process and not the library: veraPDF's only complete implementation
 * (`org.verapdf:pdfbox-validation-model`) ships its own PDFBox fork under the identical
 * `org.apache.pdfbox.*` package names as the PDFBox 3.0.8 this project runs on. Both on one
 * classpath collide at runtime (`NoSuchMethodError` in `PDDocument.load`). That is a real
 * dependency incompatibility, not a configuration mistake — the process boundary removes it
 * instead of maintaining shade relocations forever.
 *
 * This is a **release-time** proof, not a per-print step: [PdfA3MinimalCheck] stays stage 5 of the
 * export pipeline (fast, dependency-free, catches the common structural errors per document).
 *
 * Findings map into the shared finding contract — one [ValidationMessage] per failed rule, carrying
 * the ISO clause as `ruleId`, `stage = "PDFA"` and `validator = "veraPDF <version>"` — so there is no
 * second, competing finding format.
 *
 * Engine failure (CLI missing, timeout, unexpected exit code, unparsable output) yields
 * [KositOutcome.ERROR], never [KositOutcome.INVALID]: "the check could not run" and "the document
 * is wrong" are different statements, kept apart.
 *
 * `@ApplicationScoped` added here: [BasePdfStage]'s `@Inject constructor`
 * already declared a `VeraPdfValidatorPort` dependency, but this, its only implementation,
 * had no CDI scope annotation of its own — a latent gap that only a real container (not the
 * direct-construction unit tests this module runs) would have surfaced. Also needed directly by
 * [VeraPdfStatusResource], which injects this concrete class because
 * [installedVersion] is not part of [VeraPdfValidatorPort] — which is also why both live in
 * `impl`.
 *
 * **Result cache, in-process rather than via `InvoiceCachePort`.** That port is used for shared
 * state elsewhere in the app (Valkey), but it lives in kotlin-invoice and is not reachable from
 * this module — and pulling it here, or wiring Valkey into a worker process just for this, would
 * be infrastructure disproportionate to the problem. It is also the wrong shape for what this
 * cache actually is: the key is content-addressed (document hash + engine version + profile, see
 * [cacheKeyFor]), so an entry can never go stale — there is no TTL question, no invalidation
 * event, nothing that a second process would ever need to see or
 * agree on. veraPDF only ever runs inside *this* JVM; a process-local [java.util.LinkedHashMap] is
 * the whole mechanism, with none of Valkey's network hop or serialization cost on every call.
 *
 * What gets cached: only [KositOutcome.VALID] and [KositOutcome.INVALID] — real, deterministic
 * readings of the *document* under a pinned engine version. [KositOutcome.ERROR] is deliberately
 * excluded, not given a short TTL: an engine error (CLI missing, timeout, unparsable output) is a
 * statement about the environment at the moment of the call, not about the document, and the
 * project draws that line everywhere else too (see the class kdoc above). A short-TTL cache entry
 * would still risk exactly the failure mode this distinction exists to prevent — an admin fixes the
 * CLI and the very next export still reports "not checked" from a stale cache slot. Simplest correct
 * fix: engine failures are never memoized, so the next call always gets a fresh attempt.
 *
 * Size bound: [DEFAULT_CACHE_MAX_ENTRIES] entries, evicted least-recently-used. This runs on an 8 GB
 * Pi alongside everything else, so an unbounded map would itself be a waste worth avoiding.
 * The KMU invoice volumes this project targets are a few hundred documents a year — a cap sized
 * for a season's worth of distinct re-exports is generous without ever being a real heap concern;
 * LRU means a cache that does fill up
 * degrades to "recomputes the coldest entry" rather than growing further.
 */
@ApplicationScoped
class VeraPdfCliValidator(
    /** Path or name of the veraPDF CLI. Config: `VERAPDF_CLI`, default `verapdf`. */
    private val cliPath: String = readEnv("VERAPDF_CLI") ?: "verapdf",
    /** Pinned version, reported with every finding. Config: `VERAPDF_VERSION`. Never `latest`. */
    private val pinnedVersion: String = readEnv("VERAPDF_VERSION") ?: DEFAULT_VERSION,
    /** Validation profile flavour; targets 3b. */
    private val flavour: String = "3b",
    private val timeoutSeconds: Long = readEnv("VERAPDF_TIMEOUT")?.toLongOrNull() ?: DEFAULT_TIMEOUT_SECONDS,
    /** The veraPDF-hang fix that used to live entirely in this class is now [ProcessRunner]
     *  (kotlin-shared), shared with `BackupService`. Injectable so tests can substitute a fake,
     *  same default-real-instance pattern as the rest of the repo. */
    private val processRunner: ProcessRunner = ProcessRunner(),
    /** Escape hatch. Config: `VERAPDF_CACHE_ENABLED`, default on — the cache can only ever
     *  return an answer this same CLI/version already produced for these exact bytes, so leaving it
     *  on is safe; the switch exists for diagnosing a cache-shaped bug report without a redeploy. */
    private val cacheEnabled: Boolean = readEnv("VERAPDF_CACHE_ENABLED")?.toBooleanStrictOrNull() ?: true,
    /** Size bound. Config: `VERAPDF_CACHE_MAX_ENTRIES`. See the class kdoc for why this
     *  number is generous for the target workload without being unbounded. */
    private val cacheMaxEntries: Int = readEnv("VERAPDF_CACHE_MAX_ENTRIES")?.toIntOrNull() ?: DEFAULT_CACHE_MAX_ENTRIES,
) : VeraPdfValidatorPort {

    companion object {
        /** Pinned deliberately: a conformance claim must name the engine that produced it, and a
         *  floating `latest` would silently change what "conformant" meant between two runs.
         *  Single source is [ValidationToolchain.VERAPDF_DEFAULT_VERSION] (kotlin-shared) — the
         *  same number the UI's toolchain provenance hint shows, so the version reported with
         *  findings and the one named to users can never drift apart. */
        const val DEFAULT_VERSION = ValidationToolchain.VERAPDF_DEFAULT_VERSION
        const val PROFILE = "PDF/A-3B"
        const val STAGE = "PDFA"

        /** `--version` is a trivial call; if it has not answered by now, the CLI is not usable. */
        private const val PROBE_TIMEOUT_SECONDS = 30L

        /** See the class kdoc's result-cache section for the sizing rationale. */
        const val DEFAULT_CACHE_MAX_ENTRIES = 200
    }

    private val mapper = jacksonObjectMapper()

    /** Test seam: real value stages the invoice bytes via [Files.write]; tests substitute a
     *  failing writer to exercise the "could not stage the PDF" engine-error path without faking
     *  a real disk-full or permission failure. */
    internal var tempFileWriter: (java.nio.file.Path, ByteArray) -> Unit = { path, bytes -> Files.write(path, bytes) }

    /**
     * Process-local, content-addressed result cache. `accessOrder = true` plus the
     * `removeEldestEntry` override turns the plain [LinkedHashMap] into an LRU with a hard cap; the
     * [Collections.synchronizedMap] wrapper is enough here because every access is a single `get`
     * or `put` — there is no read-modify-write span across two calls that would need a wider lock.
     */
    private val cache: MutableMap<String, VeraPdfResult> = Collections.synchronizedMap(
        object : LinkedHashMap<String, VeraPdfResult>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VeraPdfResult>?): Boolean =
                size > cacheMaxEntries
        },
    )

    /** Cache key: document hash + engine version + profile. Reuses [sha256Hex] — the
     *  same digest that already exists for the journal-chain checksum ([PdfExportServiceImpl]) —
     *  rather than a second hashing pass. The version and profile are folded in explicitly, not
     *  left implicit, so a pinned-version bump or a future second flavour can never return a
     *  result computed under different rules. */
    private fun cacheKeyFor(pdfBytes: ByteArray): String =
        "${sha256Hex(pdfBytes)}:${installedVersionOrPinned()}:$flavour"

    /** Probed once: every finding names the validator, and re-spawning the CLI per finding would
     *  turn a report into a process storm. Availability does not change mid-run. */
    private val detectedVersion: String? by lazy { probeVersion() }

    /** The engine's own reported version, or null when the CLI is not usable here. */
    fun installedVersion(): String? = detectedVersion

    /**
     * `--version` gets at most [PROBE_TIMEOUT_SECONDS], but never longer than the deadline
     * configured for a whole validation run: someone who sets `VERAPDF_TIMEOUT` short does not
     * want determining the version number of all things to block longer than the check itself.
     * This is not an academic case — [engineError] names the validator in every message, which
     * triggers the probe right after a timeout, against the very same hanging CLI.
     */
    private val probeTimeoutSeconds: Long get() = minOf(PROBE_TIMEOUT_SECONDS, timeoutSeconds)

    // Some veraPDF builds print the version banner to stderr rather than stdout; probeCliVersion
    // searches both rather than requiring one specific stream (the old mergeStderr flag did this
    // by piping stderr into stdout, ProcessRunner keeps the streams apart instead).
    private fun probeVersion(): String? = probeCliVersion(processRunner, cliPath, probeTimeoutSeconds)

    /** True when a veraPDF CLI can actually be executed here. Callers must **skip visibly** when
     *  this is false — a conformance test that quietly passes without an engine proves nothing. */
    fun isAvailable(): Boolean = installedVersion() != null

    /** The validator identity that goes into every finding, e.g. `veraPDF 1.30.2`. */
    fun validatorId(): String = "veraPDF ${installedVersion() ?: pinnedVersion}"

    override fun validate(pdfBytes: ByteArray): VeraPdfResult {
        val cacheKey = cacheKeyFor(pdfBytes)
        cacheLookup(cacheKey)?.let { return it }

        // Files.createTempFile rather than File.createTempFile: the NIO variant creates the file
        // with owner-only permissions from the start. The temporary copy is a customer invoice —
        // on a shared host, world-readable would be the wrong default even for a few seconds.
        val temp = Files.createTempFile("verapdf-", ".pdf")
        val result = try {
            tempFileWriter(temp, pdfBytes)
            runValidation(temp.toFile())
        } catch (ex: java.io.IOException) {
            engineError("Konnte PDF nicht für die Prüfung ablegen: ${ex.message}")
        } finally {
            Files.deleteIfExists(temp)
        }
        cacheStore(cacheKey, result)
        return result
    }

    /** Public File overload (used directly by tests and `VeraPdfConformanceIT` — the port only
     *  exposes the byte-array form above). Reads the file to derive the same cache key the
     *  byte-array path would use, so a caller that happens to already have a [File] gets the same
     *  cache benefit rather than a second, key-less code path. */
    fun validate(pdfFile: File): VeraPdfResult {
        val cacheKey = runCatching { cacheKeyFor(Files.readAllBytes(pdfFile.toPath())) }.getOrNull()
        cacheKey?.let { key -> cacheLookup(key)?.let { return it } }

        val result = runValidation(pdfFile)
        cacheKey?.let { cacheStore(it, result) }
        return result
    }

    private fun runValidation(pdfFile: File): VeraPdfResult {
        val command = listOf(cliPath, "--format", "json", "--flavour", flavour, pdfFile.absolutePath)
        val result = try {
            processRunner.run(command, timeout = Duration.ofSeconds(timeoutSeconds))
        } catch (ex: java.io.IOException) {
            // The CLI is not installed/executable — an engine failure, not a document defect.
            return engineError("veraPDF-CLI nicht ausführbar ($cliPath): ${ex.message}")
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            return engineError("veraPDF-Aufruf unterbrochen: ${ex.message}")
        }
        // The rest split out into interpretProcessResult() to keep this method's own
        // ReturnCount down; behaviour is unchanged.
        return interpretProcessResult(result)
    }

    private fun interpretProcessResult(result: ProcessResult): VeraPdfResult {
        if (result.timedOut) {
            return engineError("veraPDF-Lauf nach ${timeoutSeconds}s abgebrochen (Timeout)")
        }

        // veraPDF exits 0 for a compliant file and 1 for a non-compliant one — both are real
        // results. Anything else means the engine itself failed.
        if (result.exitCode != 0 && result.exitCode != 1) {
            return engineError("veraPDF beendete sich mit Exitcode ${result.exitCode}: ${result.stderr.take(STDERR_PREVIEW_LENGTH)}")
        }

        return try {
            parse(result.stdout)
        } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
            engineError("veraPDF-Ausgabe nicht auswertbar: ${ex.message}")
        }
    }

    private fun cacheLookup(key: String): VeraPdfResult? = if (cacheEnabled) cache[key] else null

    /** [KositOutcome.ERROR] is excluded on purpose — see the class kdoc's result-cache section: it
     *  is a statement about the environment, not the document, and must never outlive the
     *  environment that produced it. */
    private fun cacheStore(key: String, result: VeraPdfResult) {
        if (cacheEnabled && result.outcome != KositOutcome.ERROR) {
            cache[key] = result
        }
    }

    /** Parses veraPDF's machine-readable report. Tolerant about where the job node sits: the CLI
     *  has moved it between `report.jobs` and a top-level `jobs` across versions, and a pinned
     *  version should not be the only thing standing between us and an unreadable result. */
    internal fun parse(json: String): VeraPdfResult {
        if (json.isBlank()) return engineError("veraPDF lieferte keine Ausgabe")
        val root = mapper.readTree(json)
        val jobs = root.path("report").path("jobs").takeIf { it.isArray && it.size() > 0 }
            ?: root.path("jobs").takeIf { it.isArray && it.size() > 0 }
            ?: return engineError("veraPDF-Ausgabe enthält keinen Prüflauf")

        val job = jobs.first()
        val validationResult = job.path("validationResult").let { if (it.isArray) it.first() else it }
        if (validationResult.isMissingNode || validationResult.isNull) {
            // A job without a validation result means veraPDF could not even parse the document.
            val taskError = job.path("taskResult").path("exceptionMessage").asText("")
            return engineError("veraPDF konnte das Dokument nicht prüfen${if (taskError.isBlank()) "" else ": $taskError"}")
        }

        val compliant = validationResult.path("compliant").asBoolean(false)
        val profileName = validationResult.path("profileName").asText(PROFILE)
        val details = validationResult.path("details")
        val failedRules = details.path("failedRules").asInt(0)
        val failedChecks = details.path("failedChecks").asInt(0)

        val findings = details.path("ruleSummaries")
            .filter { it.path("ruleStatus").asText(it.path("status").asText("")).equals("FAILED", ignoreCase = true) }
            .map { summary -> toFinding(summary) }

        return VeraPdfResult(
            outcome = if (compliant) KositOutcome.VALID else KositOutcome.INVALID,
            profile = profileName,
            validator = validatorId(),
            failedRuleCount = if (failedRules > 0) failedRules else findings.size,
            failedCheckCount = failedChecks,
            findings = findings,
        )
    }

    private fun toFinding(summary: JsonNode): ValidationMessage {
        val clause = summary.path("clause").asText("")
        val testNumber = summary.path("testNumber").asText("")
        val specification = summary.path("specification").asText("ISO 19005-3")
        val description = summary.path("description").asText("")
        val checks = summary.path("failedChecks").asInt(0)
        val location = summary.path("checks").takeIf { it.isArray && it.size() > 0 }
            ?.first()?.path("context")?.asText()?.takeIf { it.isNotBlank() }

        val ruleId = listOf(clause, testNumber).filter { it.isNotBlank() }.joinToString("-").ifBlank { "PDFA" }
        val message = buildString {
            append(specification)
            if (clause.isNotBlank()) append(", Klausel $clause")
            if (testNumber.isNotBlank()) append(" (Test $testNumber)")
            if (description.isNotBlank()) append(": $description")
            if (checks > 0) append(" — $checks betroffene Stelle(n)")
        }

        return ValidationMessage(
            severity = "ERROR",
            message = message,
            location = location,
            ruleId = ruleId,
            stage = STAGE,
            validator = validatorId(),
        )
    }

    private fun engineError(reason: String) = VeraPdfResult(
        outcome = KositOutcome.ERROR,
        profile = PROFILE,
        validator = "veraPDF ${installedVersionOrPinned()}",
        failedRuleCount = 0,
        failedCheckCount = 0,
        findings = emptyList(),
        engineError = reason,
    )

    /** An engine error may itself be "no CLI here" — then the pinned version is all we can name. */
    private fun installedVersionOrPinned(): String = detectedVersion ?: pinnedVersion
}
