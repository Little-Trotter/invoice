package de.littletrotter.infrastructure

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * PO 30.08.2026 ("sehr kritisch") — pins the stack's crash-resilience contract at the compose
 * level, the one layer no runtime test sees: Docker itself restarts a crashed container only if
 * the service declares a restart policy, and startup ordering only holds if the infra services
 * carry healthchecks. A silently dropped `restart: unless-stopped` would surface as "the stack
 * is down since last night" — this drift test turns it into a build failure instead. The same
 * resource-parse shape as `B2bTermSchemaRangeTest` (plain file parse, no Docker needed).
 *
 * Scope since the repo split of 05.09.2026: the BASE stack (docker-compose.yml) only. The
 * compose overlays under docker/compose/ (deploy, tls, verapdf, e2e, vies-stub, …) are no longer
 * part of this repository, and the two tests that pinned their restart policies went with them —
 * a drift test whose subject lives elsewhere proves nothing here and fails for the wrong reason.
 * That contract belongs to whoever owns the overlays.
 *
 * The other two resilience layers are pinned elsewhere: the veraPDF service guards
 * (timeout, engine-error-not-INVALID) in `VeraPdfRestClientTest` and `PdfCheckConsumerTest`, and worker-crash job recovery (JetStream ackWait + heartbeats
 * + redelivery, stale-DELEGATED sweeper) in `JobConsumerHeartbeatIT` and the job-processor ITs.
 */
class ComposeCrashResilienceTest {

    /** Services allowed to run without auto-restart: one-shot tools, not long-running parts.
     *  Empty since the repo split of 05.09.2026 — `datagen` was the only entry and left this
     *  repository with the rest of the workshop tooling. Kept rather than
     *  inlined away: the base stack is meant to grow one-shot services again (a migration or
     *  export job), and the rule they have to opt out EXPLICITLY is the point of this test. */
    private val oneShotServices = emptySet<String>()

    /** The stateful infra every app service waits for — these must define a healthcheck, or
     *  `depends_on: condition: service_healthy` ordering silently degrades to "started". */
    private val infraServices = setOf("postgres", "rustfs", "nats", "valkey")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            if (File(dir, "docker-compose.yml").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate docker-compose.yml from ${File(".").absolutePath}")
    }

    /** Parses the top-level `services:` section into service-name -> block text. Indentation-based
     *  on purpose: the compose files follow the repo's fixed two-space style, and a full YAML
     *  parser would add a dependency for what one drift test needs. */
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

    @Test
    fun `every long-running service in the base stack declares restart unless-stopped`() {
        val services = serviceBlocks(File(repoRoot(), "docker-compose.yml"))
        assertTrue(services.isNotEmpty(), "expected services in docker-compose.yml")
        services.forEach { (name, block) ->
            if (name in oneShotServices) {
                assertTrue(
                    block.contains(Regex("""restart:\s*"?no"?""")),
                    "one-shot service '$name' must opt out explicitly (restart: \"no\"), not implicitly",
                )
            } else {
                assertTrue(
                    block.contains("restart: unless-stopped"),
                    "service '$name' must auto-restart after a crash (restart: unless-stopped)",
                )
            }
        }
    }

    @Test
    fun `the stateful infra services declare healthchecks`() {
        val services = serviceBlocks(File(repoRoot(), "docker-compose.yml"))
        infraServices.forEach { name ->
            val block = services[name]
            assertTrue(block != null, "expected infra service '$name' in docker-compose.yml")
            assertTrue(
                block!!.contains("healthcheck:"),
                "infra service '$name' must define a healthcheck — service_healthy ordering depends on it",
            )
        }
    }

    @Test
    fun `the app services gate their start on healthy infra and declare healthchecks`() {
        val services = serviceBlocks(File(repoRoot(), "docker-compose.yml"))
        listOf("little-trotter-invoice", "little-trotter-job-worker", "kotlin-xpdf").forEach { name ->
            val block = services[name]
            assertTrue(block != null, "expected app service '$name' in docker-compose.yml")
            assertTrue(
                block!!.contains("condition: service_healthy"),
                "app service '$name' must wait for healthy infra (depends_on condition: service_healthy)",
            )
            assertTrue(
                block!!.contains("healthcheck:"),
                "app service '$name' must define a healthcheck for crash-resilience diagnostics",
            )
        }
    }
}
