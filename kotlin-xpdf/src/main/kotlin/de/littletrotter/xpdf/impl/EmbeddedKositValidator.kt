package de.littletrotter.xpdf.impl

import de.kosit.validationtool.api.AcceptRecommendation
import de.kosit.validationtool.api.Check
import de.kosit.validationtool.api.Configuration
import de.kosit.validationtool.api.InputFactory
import de.kosit.validationtool.impl.DefaultCheck
import de.kosit.validationtool.impl.EngineInformation
import de.kosit.validationtool.impl.xml.ProcessorProvider
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationToolchain
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * Embedded KoSIT validator using the official Maven library (`org.kosit:validator`).
 *
 * The XRechnung scenario configuration is shipped as a versioned ZIP in the classpath
 * (`xrechnung/xrechnung-3.0.2-validator-configuration-2026-01-31.zip`) and extracted to a
 * temp directory, because the KoSIT engine resolves scenario artifacts against `file:` URIs only.
 *
 * The verdict comes typed from the API (`AcceptRecommendation`) — no report heuristics.
 * An engine failure yields [KositOutcome.ERROR], never `VALID`.
 *
 * Deliberately **not** `@Startup`/`@PostConstruct` anymore: that combination used
 * to force this scenario compile (extract the ZIP, then `Configuration.load(...).build(...)` —
 * Saxon/XSLT parsing the whole XRechnung scenario) to run synchronously at container boot, for
 * every CDI-managed instance regardless of whether a job had even arrived yet. Under the
 * 2026-09-18 stress finding's memory limit, that boot-time compile was the OOM crash's actual
 * peak, and — because it re-ran identically on every restart — a worker that crashed once during
 * boot would crash again immediately, before its NATS pull consumer
 * ([de.littletrotter.xpdf.infrastructure.messaging.JobConsumer], also `@Startup`) could ever
 * become reachable again. CDI injects [de.littletrotter.shared.KositValidatorPort] (this bean,
 * transitively, via [de.littletrotter.shared.PdfExportPort]) into `JobConsumer`'s constructor as
 * an ordinary client proxy for a normal-scoped bean: nothing about that eager constructor
 * injection invokes a method on it, so simply not forcing eager creation here is what defers the
 * compile to the first *real* [validate] call — off the NATS-readiness critical path entirely.
 * [check] guards that first call with Kotlin's synchronized `by lazy` (thread-safe, exactly-once,
 * shared by every subsequent caller) rather than a bespoke lock, and does not cache a failed
 * attempt (Kotlin's `Lazy` never stores a thrown initializer's result), so a transient failure
 * (e.g. a corrupted temp-extraction race) is retried on the very next call instead of wedging this
 * singleton in a permanently-broken state for the rest of the process's life — strictly better
 * than the previous `@PostConstruct` failure mode, which crashed the whole bean creation (and,
 * transitively, the JVM's own startup) outright.
 *
 * `DefaultCheck`'s own JavaDoc is the answer to "verify thread-safety of `DefaultCheck` if reused
 * across threads": *"After initialisation, instances are threadsafe and should be reused since
 * initializing saxon runtime objects is an rather heavyweight process."* — the library's own
 * documented contract, not an assumption this class adds; sharing the one lazily-built [Check]
 * across every `kotlin-xpdf` worker thread ([JOB_WORKER_THREADS][de.littletrotter.xpdf
 * .infrastructure.messaging.JobConsumer] concurrent renders) is exactly its intended usage.
 */
@ApplicationScoped
class EmbeddedKositValidator : KositValidatorPort, AutoCloseable {

    @Volatile
    private var scenarioDir: Path? = null

    private var validatorVersion: String? = null

    // `internal` so a test can force a specific (e.g. throwing) Check without
    // depending on a corrupted classpath resource to exercise validate()'s ERROR-degradation path
    // deterministically; production code only ever reads [check], never writes this delegate.
    internal var checkOverrideForTest: Check? = null

    private val checkDelegate: Lazy<Check> = lazy { buildCheck() }

    private val check: Check
        get() = checkOverrideForTest ?: checkDelegate.value

    /**
     * Forces [check] to build now instead of on the first [validate] call — most test fixtures
     * want a deterministic setup cost, not one paid inside their first timed assertion. Production
     * CDI wiring never calls this (see this class's own KDoc for why laziness is the default
     * there); every existing `EmbeddedKositValidator().apply { init() }` test fixture keeps this
     * exact eager-warm-up shape unchanged.
     */
    fun init() {
        check
    }

    private fun buildCheck(): Check = buildCheckWith()

    /** The engine reads the extracted scenario files lazily, so the directory must live as long
     *  as this instance; it is released here (or, at the latest, at JVM exit). */
    @PreDestroy
    override fun close() {
        scenarioDir?.let { ScratchDirectories.deleteRecursively(it) }
        scenarioDir = null
    }

    /** Test seam — [buildCheck] always delegates here with the default (real classloader)
     *  [resourceLookup]; a test can hand in a failing one to exercise this method's own
     *  "engine setup failed" wrapping and [extractScenario]'s missing-classpath-resource branch
     *  together, without touching the real bundled scenario ZIP — the same pattern
     *  `CiiXsdValidator.initSchema`'s own KDoc documents for the equivalent problem. */
    internal fun buildCheckWith(
        resourceLookup: (String) -> java.io.InputStream? = { path -> javaClass.classLoader.getResourceAsStream(path) },
    ): Check {
        try {
            val scenarioDir = extractScenario(resourceLookup)
            this.scenarioDir = scenarioDir
            val scenarioUri = scenarioDir.resolve("scenarios.xml").toUri()
            val repositoryUri = scenarioDir.toUri()
            val config: Configuration = Configuration.load(scenarioUri, repositoryUri)
                .build(ProcessorProvider.getProcessor())
            validatorVersion = "${EngineInformation.getVersion()} / $SCENARIO_RELEASE"
            return DefaultCheck(config)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to initialize embedded KoSIT validator", e)
        }
    }

    override fun validate(xml: String, timeoutMs: Long): KositValidationResult {
        return try {
            val input = InputFactory.read(
                ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)),
                "invoice.xml"
            )
            mapResult(check.checkInput(input))
        } catch (e: Exception) {
            KositValidationResult(
                status = KositOutcome.ERROR,
                summary = "Engine error: ${e.message}",
                validatorVersion = validatorVersion
            )
        }
    }

    private fun mapResult(result: de.kosit.validationtool.api.Result): KositValidationResult {
        val status = when (result.acceptRecommendation) {
            AcceptRecommendation.ACCEPTABLE -> KositOutcome.VALID
            AcceptRecommendation.REJECT -> KositOutcome.INVALID
            else -> KositOutcome.ERROR
        }

        val details = buildList {
            result.schemaViolations?.forEach { err ->
                add(
                    ValidationMessage(
                        severity = err.severity?.name ?: "ERROR",
                        message = err.message ?: "Unknown schema error",
                        location = "row ${err.rowNumber ?: "?"}, col ${err.columnNumber ?: "?"}",
                        stage = "SYNTAX",
                        validator = "kosit",
                    )
                )
            }
            // Capability probe (against the real resolved org.kosit:validator:1.6.2):
            // Result.getCustomFailedAsserts() exists but comes back EMPTY for this scenario —
            // the XRechnung 3.0.2 configuration doesn't populate the library's own
            // ErrorLevelType override. getFailedAsserts() is the one that's actually filled,
            // and its SVRL FailedAssert already carries id/flag/role/test/location — that's
            // the real source for both ruleId and severity here, not a guess.
            result.failedAsserts.forEach { fa ->
                add(
                    ValidationMessage(
                        severity = mapSeverityFlag(fa.flag),
                        message = richText(fa.text),
                        location = fa.location,
                        ruleId = fa.id?.takeIf { it.isNotBlank() }
                            ?: fa.role?.takeIf { it.isNotBlank() }
                            ?: fa.test?.takeIf { it.isNotBlank() },
                        stage = "KOSIT",
                        validator = "kosit",
                    )
                )
            }
        }

        val errorCount = details.count { it.severity == "ERROR" }
        val warningCount = details.count { it.severity == "WARNING" }
        val summary = when {
            details.isEmpty() -> null
            warningCount == 0 -> "$errorCount validation error(s)"
            errorCount == 0 -> "$warningCount validation warning(s)"
            else -> "$errorCount validation error(s), $warningCount warning(s)"
        }

        return KositValidationResult(
            status = status,
            summary = summary,
            details = details,
            validatorVersion = validatorVersion
        )
    }

    /** Maps the SVRL `flag` attribute (fatal/error/warning/information/info) to our
     *  three-level severity; an unrecognized or absent flag is treated as ERROR — a
     *  finding whose severity we can't classify must not silently disappear. */
    private fun mapSeverityFlag(flag: String?): String = when (flag?.lowercase()) {
        "fatal", "error" -> "ERROR"
        "warning" -> "WARNING"
        "information", "info" -> "INFO"
        else -> "ERROR"
    }

    private fun richText(text: org.oclc.purl.dsdl.svrl.Text?): String {
        if (text == null) return ""
        return text.content.joinToString(" ") { item ->
            when (item) {
                is String -> item
                else -> item.toString()
            }
        }.trim()
    }

    private fun extractScenario(resourceLookup: (String) -> java.io.InputStream?): Path {
        val resource = resourceLookup(SCENARIO_ZIP)
            ?: error("Scenario configuration $SCENARIO_ZIP not found on classpath")
        val target = ScratchDirectories.create("kosit-scenario")
        try {
            unzip(resource, target)
        } catch (e: Exception) {
            ScratchDirectories.deleteRecursively(target)
            throw e
        }
        return target
    }

    private fun unzip(resource: java.io.InputStream, target: Path) {
        ZipInputStream(resource).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = target.resolve(entry.name).normalize()
                check(out.startsWith(target)) { "Scenario ZIP contains path traversal entry: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    Files.copy(zip, out)
                }
                entry = zip.nextEntry
            }
        }
    }

    companion object {
        /** Built from [ValidationToolchain]'s XRechnung constants (kotlin-shared), which the UI's
         *  toolchain provenance hint also shows: a configuration upgrade that renames this ZIP
         *  forces those constants along, and `ValidationToolchainPinTest` asserts the path built
         *  here actually exists on the classpath — no silently drifting copy of the version. */
        const val SCENARIO_ZIP =
            "xrechnung/xrechnung-${ValidationToolchain.XRECHNUNG_VERSION}" +
                "-validator-configuration-${ValidationToolchain.XRECHNUNG_CONFIG_RELEASE}.zip"
        const val SCENARIO_RELEASE = ValidationToolchain.XRECHNUNG_CONFIG_RELEASE
    }
}
