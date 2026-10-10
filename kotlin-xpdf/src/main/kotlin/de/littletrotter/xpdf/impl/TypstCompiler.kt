package de.littletrotter.xpdf.impl

import de.littletrotter.shared.process.ProcessResult
import de.littletrotter.shared.process.ProcessRunner
import de.littletrotter.xpdf.readEnv
import jakarta.enterprise.context.ApplicationScoped
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

/** Default compile deadline when `TYPST_TIMEOUT` is unset. A4 invoices compile in well under a second. */
private const val DEFAULT_TIMEOUT_SECONDS = 60L

/** `--version` is a trivial call; if it has not answered by now, the CLI is not usable. */
private const val PROBE_TIMEOUT_SECONDS = 15L

/** How many characters of the compiler's diagnostics to keep in a failure. */
private const val DIAGNOSTICS_PREVIEW_LENGTH = 2000

/** An extra work file's name may contain no path separators and nothing but lowercase, digits,
 *  dot and dash — the `--root` sandbox confines reads to `workDir`, but a name carrying a path
 *  part (or resolving to "." / "..") could still escape it or overwrite `main.typ`/`data.json`. */
private val EXTRA_FILE_NAME_PATTERN = Regex("^[a-z0-9.-]+$")

/** Everything a compile run needs, in one value: the template, its data and the moment to stamp. */
data class TypstCompileRequest(
    /** Template source, written as `main.typ` into the sandboxed working directory. */
    val templateSource: String,
    /** Preformatted document data, written next to the template as `data.json` and read by it. */
    val dataJson: String,
    /** The document's creation moment. Passed explicitly so two runs with the same input yield
     *  byte-identical PDFs; the caller takes it from the shared time source. */
    val creationTimestamp: Instant,
    /** Extra files written next to `data.json` in the sandboxed working directory — for example a
     *  vector QR code the template embeds by filename. Keyed by the filename the template refers to. */
    val files: Map<String, String> = emptyMap(),
)

/** Outcome of one compile run. Never an exception: an unusable engine is a result, not a crash. */
sealed interface TypstCompileResult {
    /** The compiled PDF/A-3b base document. Not a data class: array equality by identity would
     *  make the generated `equals` misleading, and nothing compares results structurally. */
    class Success(val pdfBytes: ByteArray) : TypstCompileResult

    /** Engine or template failure. [reason] is the operator-facing summary, [diagnostics] the
     *  compiler's own stderr (source positions, hints), trimmed to a preview. */
    data class Failure(val reason: String, val diagnostics: String) : TypstCompileResult
}

/**
 * Compiles a Typst template to a PDF/A-3b base document through the **Typst CLI**, as a separate
 * process — the same shape as [VeraPdfCliValidator], for the same reason: the engine is a native
 * binary with no JVM binding, and a process boundary keeps it out of the JVM's dependency graph
 * (and out of the native-image reflection budget).
 *
 * The CLI is sandboxed by construction: `--root` confines every file read to the working
 * directory this class creates per run, `--ignore-system-fonts` plus `--font-path` pin the glyphs
 * to the fonts the project ships, and Typst has no network or shell access at all. A template is
 * therefore data, never code — the property that makes user-supplied layouts acceptable in the
 * first place.
 *
 * Determinism: with `--creation-timestamp` fixed by the caller, identical inputs produce
 * byte-identical PDFs. Golden tests and the archive's hash chain rely on that.
 *
 * Engine failure (CLI missing, timeout, unexpected exit code, no output written) yields
 * [TypstCompileResult.Failure] with the compiler's diagnostics attached, never an exception —
 * "the engine could not run" and "the template is wrong" are both results the pipeline reports.
 *
 * Configuration follows the veraPDF precedent: environment variables read once at construction,
 * `TYPST_CLI` (default `typst`), `TYPST_VERSION` (pinned, reported by the status endpoint),
 * `TYPST_FONT_PATH` (directory with the shipped fonts) and `TYPST_TIMEOUT` (seconds).
 */
@ApplicationScoped
class TypstCompiler(
    /** Path or name of the Typst CLI. Config: `TYPST_CLI`, default `typst`. */
    private val cliPath: String = readEnv("TYPST_CLI") ?: "typst",
    /** Pinned version the deployment promises. Config: `TYPST_VERSION`. Never `latest`. */
    private val pinnedVersion: String = readEnv("TYPST_VERSION") ?: DEFAULT_VERSION,
    /** Directory searched recursively for the shipped fonts. Config: `TYPST_FONT_PATH`. Unset
     *  means the CLI finds no fonts at all (system fonts are always ignored) — a deliberate loud
     *  failure rather than a silently different typeface. */
    private val fontPath: String? = readEnv("TYPST_FONT_PATH"),
    private val timeoutSeconds: Long = readEnv("TYPST_TIMEOUT")?.toLongOrNull() ?: DEFAULT_TIMEOUT_SECONDS,
    /** Injectable so unit tests can substitute a fake; the default is the real runner. */
    private val processRunner: ProcessRunner = ProcessRunner(),
) {

    companion object {
        /** Pinned deliberately — the Dockerfiles carry the same number, guarded by a test, so the
         *  version the status endpoint names and the binary in the image can never drift apart. */
        const val DEFAULT_VERSION = "0.15.1"

        /** The only standard this project renders to: ZUGFeRD/Factur-X require PDF/A-3. */
        const val PDF_STANDARD = "a-3b"

        const val TEMPLATE_FILE = "main.typ"
        const val DATA_FILE = "data.json"
        const val OUTPUT_FILE = "document.pdf"

        /**
         * The exact CLI invocation, as one pure function so the integration test that runs the
         * pinned binary in a container executes precisely the arguments production uses.
         */
        fun commandArguments(cliPath: String, fontPath: String?, workDir: Path, creationTimestamp: Instant): List<String> =
            buildList {
                add(cliPath)
                add("compile")
                add("--root")
                add(workDir.toString())
                add("--ignore-system-fonts")
                if (fontPath != null) {
                    add("--font-path")
                    add(fontPath)
                }
                add("--pdf-standard")
                add(PDF_STANDARD)
                add("--creation-timestamp")
                add(creationTimestamp.epochSecond.toString())
                add(workDir.resolve(TEMPLATE_FILE).toString())
                add(workDir.resolve(OUTPUT_FILE).toString())
            }
    }

    /** Test seam: the real value writes the template and data files; tests substitute a failing
     *  writer to exercise the "could not stage the run" path without faking a full disk. */
    internal var fileWriter: (Path, String) -> Unit = { path, text -> path.writeText(text) }

    /** Probed once: availability does not change mid-run, and the status endpoint would
     *  otherwise spawn a process per poll. */
    private val detectedVersion: String? by lazy { probeVersion() }

    /** The engine's own reported version, or null when the CLI is not usable here. */
    fun installedVersion(): String? = detectedVersion

    /** True when a Typst CLI can actually be executed here. */
    fun isAvailable(): Boolean = installedVersion() != null

    /** The version a deployment promises, used wherever the binary itself cannot answer. */
    fun pinnedVersion(): String = pinnedVersion

    private val probeTimeoutSeconds: Long get() = minOf(PROBE_TIMEOUT_SECONDS, timeoutSeconds)

    private fun probeVersion(): String? = probeCliVersion(processRunner, cliPath, probeTimeoutSeconds)

    /**
     * One run in a fresh, owner-only temporary directory that is removed afterwards whatever
     * happens — the data file is customer invoice content and must not outlive the run.
     */
    fun compile(request: TypstCompileRequest): TypstCompileResult {
        for (name in request.files.keys) {
            invalidExtraFileName(name)?.let { return it }
        }
        val workDir = Files.createTempDirectory("typst-")
        try {
            fileWriter(workDir.resolve(TEMPLATE_FILE), request.templateSource)
            fileWriter(workDir.resolve(DATA_FILE), request.dataJson)
            for ((name, content) in request.files) {
                fileWriter(workDir.resolve(name), content)
            }
            return runCompile(workDir, request.creationTimestamp)
        } catch (ex: IOException) {
            return TypstCompileResult.Failure("Typst-Arbeitsdateien konnten nicht geschrieben oder gelesen werden: ${ex.message}", "")
        } finally {
            deleteRecursively(workDir)
        }
    }

    /** Null when [name] is safe to write as an extra work file; otherwise the [TypstCompileResult.Failure]
     *  to return without ever touching the filesystem or spawning the CLI. */
    private fun invalidExtraFileName(name: String): TypstCompileResult.Failure? {
        val reserved = setOf(".", "..", TEMPLATE_FILE, DATA_FILE, OUTPUT_FILE)
        val valid = EXTRA_FILE_NAME_PATTERN.matches(name) && name !in reserved
        return if (valid) {
            null
        } else {
            TypstCompileResult.Failure("Ungültiger Dateiname für Typst-Arbeitsdatei: '$name'", "")
        }
    }

    private fun runCompile(workDir: Path, creationTimestamp: Instant): TypstCompileResult {
        val command = commandArguments(cliPath, fontPath, workDir, creationTimestamp)
        val result = try {
            processRunner.run(command, timeout = Duration.ofSeconds(timeoutSeconds), workingDir = workDir.toFile())
        } catch (ex: IOException) {
            return TypstCompileResult.Failure("Typst-CLI nicht ausführbar ($cliPath): ${ex.message}", "")
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            return TypstCompileResult.Failure("Typst-Aufruf unterbrochen: ${ex.message}", "")
        }
        return interpret(result, workDir)
    }

    private fun interpret(result: ProcessResult, workDir: Path): TypstCompileResult {
        if (result.timedOut) {
            return TypstCompileResult.Failure("Typst-Lauf nach ${timeoutSeconds}s abgebrochen (Timeout)", "")
        }
        if (result.exitCode != 0) {
            return TypstCompileResult.Failure(
                "Typst beendete sich mit Exitcode ${result.exitCode}",
                result.stderr.take(DIAGNOSTICS_PREVIEW_LENGTH),
            )
        }
        val output = workDir.resolve(OUTPUT_FILE)
        return if (output.exists()) {
            TypstCompileResult.Success(output.readBytes())
        } else {
            TypstCompileResult.Failure("Typst meldete Erfolg, schrieb aber keine PDF-Datei", result.stderr.take(DIAGNOSTICS_PREVIEW_LENGTH))
        }
    }

    private fun deleteRecursively(dir: Path) {
        Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
    }
}
