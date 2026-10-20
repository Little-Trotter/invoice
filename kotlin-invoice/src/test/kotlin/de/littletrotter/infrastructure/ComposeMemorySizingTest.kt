package de.littletrotter.infrastructure

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * PO rules (2026-09-19) pinning the dev stack's memory defaults in `docker-compose.yml`:
 *  - kotlin-xpdf's memory limit must never exceed little-trotter-invoice's — the invoice app is
 *    the stack's primary memory consumer, and the render worker must never be sized above it;
 *  - the sum of all service limits must fit the PO-agreed dev budget (8 GB for the whole stack)
 *    with ~0.5 GB left for the OS/Docker daemon.
 *
 * Reads the `${VAR:-NNNM}` fallbacks, i.e. the repo defaults everyone gets from `make dev`.
 * A per-machine `.env` override can size the stack differently — that is the documented lever
 * for tighter hosts (drop BOTH invoice and xpdf to 2560M, e.g. a real 8 GB Raspberry Pi 5) —
 * but the checked-in defaults themselves can no longer drift apart.
 *
 * A sibling file rather than an extension of `ComposeHardeningTest`, same reasoning as that
 * file's own class doc: independent additive concerns stay in independent files, with this
 * class carrying its own copy of the same indentation-based block parser.
 */
class ComposeMemorySizingTest {

    /** PO-agreed 8 GB dev stack budget minus ~0.5 GB for the OS/Docker daemon, in MiB. */
    companion object {
        private const val DEV_STACK_BUDGET_MIB = 7680L
    }

    private val services =
        listOf("postgres", "rustfs", "nats", "valkey", "little-trotter-invoice", "little-trotter-job-worker", "kotlin-xpdf", "verapdf")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            if (File(dir, "docker-compose.yml").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate docker-compose.yml from ${File(".").absolutePath}")
    }

    /** Parses the top-level `services:` section into service-name -> block text. Indentation-based
     *  on purpose: the compose files follow the repo's fixed two-space style (same parser shape as
     *  [ComposeHardeningTest]); a full YAML parser would add a dependency for what two drift tests
     *  need. */
    private fun serviceBlocks(file: File): Map<String, String> {
        val lines = file.readText().lines()
        val services = linkedMapOf<String, StringBuilder>()
        var inServices = false
        var current: StringBuilder? = null
        for (line in lines) {
            when {
                line == "services:" -> inServices = true
                inServices && line.isNotBlank() && !line.startsWith(" ") -> inServices = false
                inServices && Regex("^  ([a-z][a-z0-9-]*):\\s*$").matches(line) -> {
                    val name = Regex("^  ([a-z][a-z0-9-]*):\\s*$").find(line)!!.groupValues[1]
                    current = StringBuilder().also { services[name] = it }
                }
                inServices -> current?.appendLine(line)
            }
        }
        return services.mapValues { it.value.toString() }
    }

    /** The `${VAR:-NNNM}` memory fallback of one service block, in MiB. */
    private fun memoryLimitDefaultMiB(blocks: Map<String, String>, service: String): Long {
        val block = blocks[service] ?: error("service '$service' not found in docker-compose.yml")
        val match = Regex("""memory:\s*\$\{[A-Z_]+:-(\d+)M\}""").find(block)
            ?: error("service '$service' has no \"\${VAR:-NNNM}\" memory default in docker-compose.yml")
        return match.groupValues[1].toLong()
    }

    @Test
    fun `xpdf memory limit never exceeds invoice memory limit`() {
        val blocks = serviceBlocks(File(repoRoot(), "docker-compose.yml"))
        val invoice = memoryLimitDefaultMiB(blocks, "little-trotter-invoice")
        val xpdf = memoryLimitDefaultMiB(blocks, "kotlin-xpdf")
        assertTrue(
            xpdf <= invoice,
            "kotlin-xpdf memory default ${xpdf}M exceeds little-trotter-invoice's ${invoice}M — " +
                "PO rule 2026-09-19: the render worker must never be sized above the invoice app; " +
                "raise LT_INVOICE_MEMORY_LIMIT and LT_XPDF_MEMORY_LIMIT together instead",
        )
    }

    @Test
    fun `dev stack memory defaults fit the 8 GB minimum target with 1 GB OS headroom`() {
        val blocks = serviceBlocks(File(repoRoot(), "docker-compose.yml"))
        val total = services.sumOf { memoryLimitDefaultMiB(blocks, it) }
        assertTrue(
            total <= DEV_STACK_BUDGET_MIB,
            "dev stack memory defaults total ${total}M — exceeds the ${DEV_STACK_BUDGET_MIB}M budget " +
                "(PO-agreed 8 GB dev stack budget minus ~0.5 GB for the OS/Docker daemon); a tighter " +
                "host drops BOTH invoice and xpdf via .env (e.g. 2560M each), never the checked-in " +
                "defaults, and never xpdf above invoice",
        )
    }

    @Test
    fun `xpdf heap follows the container limit instead of a fixed Xmx`() {
        val blocks = serviceBlocks(File(repoRoot(), "docker-compose.yml"))
        val block = blocks.getValue("kotlin-xpdf")
        val options = Regex("""JAVA_TOOL_OPTIONS:.*""").find(block)?.value ?: error("kotlin-xpdf has no JAVA_TOOL_OPTIONS")
        assertTrue(options.contains("-XX:MaxRAMPercentage=75"), "heap must be 75 % of the container limit: $options")
        assertTrue(!options.contains("-Xmx"), "a fixed -Xmx would not follow LT_XPDF_MEMORY_LIMIT: $options")
    }
}
