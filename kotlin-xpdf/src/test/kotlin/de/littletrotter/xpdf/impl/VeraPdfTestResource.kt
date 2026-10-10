package de.littletrotter.xpdf.impl

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File

/**
 * Runs the pinned veraPDF CLI (`verapdf/cli:v1.30.2`, matching [VeraPdfCliValidator.DEFAULT_VERSION])
 * inside a Testcontainer instead of requiring the CLI on the host/CI runner's PATH — same principle
 * as the module's other `*TestResource`/`GenericContainer` fixtures (`ValkeyTestContainersExtension`),
 * applied here because [VeraPdfConformanceIT] is a plain JUnit class rather than a `@QuarkusTest`,
 * so a `QuarkusTestResourceLifecycleManager` (as `PostgresTestResource` uses) does not apply.
 *
 * The image's entrypoint is `/opt/verapdf/verapdf` with no long-running command, so it would exit
 * right after printing its usage; the entrypoint is overridden to an idle shell so the container
 * stays up between calls. Each [runVeraPdf] copies the PDF under test into the container and execs
 * the exact `--format json --flavour <flavour>` invocation [VeraPdfCliValidator.runValidation] uses
 * on the host, returning raw stdout for [VeraPdfCliValidator.parse] to interpret — the JSON
 * parsing itself is never duplicated.
 */
class VeraPdfTestResource(
    /** PD-01 targets 3b — the same default [VeraPdfCliValidator]'s own `flavour` parameter uses. */
    private val flavour: String = "3b",
) : GenericContainer<VeraPdfTestResource>(DockerImageName.parse(IMAGE)) {

    init {
        withCreateContainerCmdModifier { cmd ->
            cmd.withEntrypoint("sh", "-c", "while true; do sleep 3600; done")
            // The image's default user ("verapdf", uid 100) cannot read a file docker cp copies
            // in under the host process's own (owner-only) uid/permissions -- veraPDF then
            // silently reports zero jobs for the unreadable path instead of erroring. Root
            // reads/execs regardless of the copied file's ownership, so the container runs as
            // root instead.
            cmd.withUser("root")
        }
    }

    /** Copies [pdfFile] into the container and runs veraPDF on it, returning raw stdout JSON. */
    fun runVeraPdf(pdfFile: File): String {
        val remotePath = "/data/${pdfFile.name}"
        copyFileToContainer(MountableFile.forHostPath(pdfFile.toPath()), remotePath)

        val result = execInContainer(CLI_BIN, "--format", "json", "--flavour", flavour, remotePath)
        // veraPDF's own exit codes: 0 compliant, 1 non-compliant — both are real results with a
        // JSON body on stdout, the same contract VeraPdfCliValidator.runValidation relies on.
        check(result.exitCode == 0 || result.exitCode == 1) {
            "veraPDF container exited ${result.exitCode}: ${result.stderr.take(500)}"
        }
        return result.stdout
    }

    companion object {
        const val IMAGE = "verapdf/cli:v1.30.2"
        private const val CLI_BIN = "/opt/verapdf/verapdf"
    }
}
