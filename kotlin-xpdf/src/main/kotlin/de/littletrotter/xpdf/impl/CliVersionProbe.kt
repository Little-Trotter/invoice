package de.littletrotter.xpdf.impl

import de.littletrotter.shared.process.ProcessRunner
import java.io.IOException
import java.time.Duration

/**
 * Runs `<cliPath> --version` and extracts a version number, or `null` when the CLI cannot answer
 * (missing binary, timeout, non-zero exit, interrupted).
 *
 * Pulled out to one place rather than left as a private helper on [TypstCompiler] and
 * [VeraPdfCliValidator] individually: both engines are probed the same way (native CLI, same
 * timeout/interrupt handling, same "search stdout and stderr for a dotted version" fallback,
 * since some CLI builds print their banner to stderr rather than stdout), and two independent
 * copies of that logic would be two chances to drift apart, not two chances to be right.
 */
internal fun probeCliVersion(processRunner: ProcessRunner, cliPath: String, probeTimeoutSeconds: Long): String? = try {
    val result = processRunner.run(listOf(cliPath, "--version"), timeout = Duration.ofSeconds(probeTimeoutSeconds))
    if (result.timedOut || result.exitCode != 0) {
        null
    } else {
        val combined = "${result.stdout}\n${result.stderr}"
        Regex("""\d+\.\d+\.\d+""").find(combined)?.value ?: combined.trim().ifBlank { null }
    }
} catch (_: IOException) {
    null
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    null
}
