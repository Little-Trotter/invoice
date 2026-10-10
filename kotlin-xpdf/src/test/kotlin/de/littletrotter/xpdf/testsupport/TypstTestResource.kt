package de.littletrotter.xpdf.testsupport

import de.littletrotter.xpdf.impl.TypstCompiler
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.Transferable
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * kotlin-xpdf's own directory, regardless of which module's basedir the current test JVM's
 * working directory actually is (surefire/failsafe fork with `user.dir` = the module under test,
 * not the reactor root) — see [TypstTestResource]'s own class KDoc for why this matters.
 */
private fun kotlinXpdfDir(): File {
    val cwd = File(System.getProperty("user.dir")).canonicalFile
    if (cwd.name == "kotlin-xpdf") return cwd
    val sibling = File(cwd.parentFile, "kotlin-xpdf")
    return if (sibling.isDirectory) sibling else File(cwd, "kotlin-xpdf")
}

/**
 * Runs the pinned Typst CLI inside a Testcontainer built from `docker/typst/Dockerfile` — the
 * same download, checksum and font layout the runtime image installs — instead of requiring the
 * binary on the host or CI runner. Same principle as [VeraPdfTestResource]: a container that
 * fails to build is a red test with a clear message, never a silent skip.
 *
 * Each [compile] stages template and data under a fresh directory in the container and executes
 * exactly the argument list [TypstCompiler.commandArguments] produces for production, so the
 * invocation under test is the invocation that ships.
 *
 * Reached from this module's own tests. A test JVM's working directory is its OWN module's
 * basedir, so [kotlinXpdfDir] resolves the path independent of the test run location (same
 * idiom `VeraPdfConformanceIT.writeArtifact` already uses).
 */
class TypstTestResource : GenericContainer<TypstTestResource>(
    ImageFromDockerfile("littletrotter/typst-it:${TypstCompiler.DEFAULT_VERSION}", false)
        .withFileFromPath("Dockerfile", kotlinXpdfDir().resolve("docker/typst/Dockerfile").toPath())
        .withFileFromPath("fonts", kotlinXpdfDir().resolve("src/main/resources/fonts").toPath()),
) {
    init {
        withCreateContainerCmdModifier { cmd ->
            // The image has no long-running command; keep it idle between exec calls.
            cmd.withEntrypoint("sh", "-c", "while true; do sleep 3600; done")
        }
    }

    /**
     * What one containerized compile produced. Not a data class: [pdfBytes] is a [ByteArray], and
     * a generated `equals`/`hashCode` would compare it by identity rather than content — nothing
     * in this module compares two [Outcome]s wholesale, but a plain class with explicit
     * `contentEquals` semantics keeps that guarantee true even if a future caller does.
     */
    class Outcome(val exitCode: Int, val stderr: String, val pdfBytes: ByteArray?) {
        override fun equals(other: Any?): Boolean =
            other is Outcome && exitCode == other.exitCode && stderr == other.stderr && pdfBytes.contentEquals(other.pdfBytes)

        override fun hashCode(): Int = (exitCode * 31 + stderr.hashCode()) * 31 + (pdfBytes?.contentHashCode() ?: 0)

        override fun toString(): String = "Outcome(exitCode=$exitCode, stderr=$stderr, pdfBytes=${pdfBytes?.size ?: 0} bytes)"
    }

    fun compile(templateSource: String, dataJson: String, creationTimestamp: Instant, files: Map<String, String> = emptyMap()): Outcome {
        val workDir = "/work/${UUID.randomUUID()}"
        execInContainer("mkdir", "-p", workDir).also { check(it.exitCode == 0) { "mkdir failed: ${it.stderr}" } }
        copyFileToContainer(Transferable.of(templateSource), "$workDir/${TypstCompiler.TEMPLATE_FILE}")
        copyFileToContainer(Transferable.of(dataJson), "$workDir/${TypstCompiler.DATA_FILE}")
        for ((name, content) in files) {
            copyFileToContainer(Transferable.of(content), "$workDir/$name")
        }

        val command = TypstCompiler.commandArguments(CLI_BIN, FONT_PATH, Path.of(workDir), creationTimestamp)
        val result = execInContainer(*command.toTypedArray())
        val pdf = if (result.exitCode == 0) {
            copyFileFromContainer("$workDir/${TypstCompiler.OUTPUT_FILE}") { it.readAllBytes() }
        } else {
            null
        }
        return Outcome(result.exitCode, result.stderr, pdf)
    }

    fun installedVersion(): String = execInContainer(CLI_BIN, "--version").stdout.trim()

    companion object {
        const val CLI_BIN = "/opt/typst/typst"
        const val FONT_PATH = "/opt/typst/fonts"
    }
}
