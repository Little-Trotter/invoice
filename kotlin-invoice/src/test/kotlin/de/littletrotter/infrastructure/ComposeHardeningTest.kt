package de.littletrotter.infrastructure

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Container-hardening spec, base package (PO 30.08.2026): pins the runtime-privilege contract at
 * the compose and Dockerfile level, the same layer `ComposeCrashResilienceTest` pins for restart
 * policies. Covers:
 *  - every service in the base stack sets `security_opt: no-new-privileges:true`;
 *  - the app services (little-trotter-invoice, little-trotter-job-worker, kotlin-xpdf) also drop
 *    all Linux capabilities and cap their PID count;
 *  - both app Dockerfiles' final stage runs as a non-root `USER`.
 *
 * A sibling file rather than an extension of `ComposeCrashResilienceTest`: kept separate here
 * (with its own copy of the same indentation-based block parser) to avoid touching a file that
 * a concurrent change is extending with healthchecks — merging two independent additive test
 * files is far less conflict-prone than two edits of the same one.
 */
class ComposeHardeningTest {

    private val appServices = listOf("little-trotter-invoice", "little-trotter-job-worker", "kotlin-xpdf")

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
    fun `every service in the base stack disallows privilege escalation`() {
        val services = serviceBlocks(File(repoRoot(), "docker-compose.yml"))
        assertTrue(services.isNotEmpty(), "expected services in docker-compose.yml")
        services.forEach { (name, block) ->
            assertTrue(
                block.contains("no-new-privileges:true"),
                "service '$name' must set security_opt: no-new-privileges:true",
            )
        }
    }

    @Test
    fun `the app services drop all capabilities and cap their pid count`() {
        val services = serviceBlocks(File(repoRoot(), "docker-compose.yml"))
        appServices.forEach { name ->
            val block = services[name]
            assertTrue(block != null, "expected app service '$name' in docker-compose.yml")
            assertTrue(
                block!!.contains("cap_drop:") && block.contains("- ALL"),
                "app service '$name' must set cap_drop: [ALL]",
            )
            // Accepts either the top-level `pids_limit:` shorthand or the nested
            // `deploy.resources.limits.pids:` form — docker compose v5's classic resolver rejects
            // the two combined on one service, so services that also set deploy.resources.limits
            // (all three app services do, for memory) use the nested form instead.
            assertTrue(
                Regex("""pids(_limit)?:\s*\d+""").containsMatchIn(block),
                "app service '$name' must set a pids limit (pids_limit or deploy.resources.limits.pids)",
            )
        }
    }

    @Test
    fun `kotlin-invoice Dockerfile final stage runs as a non-root user`() {
        assertFinalStageIsNonRoot(File(repoRoot(), "kotlin-invoice/Dockerfile"))
    }

    @Test
    fun `kotlin-xpdf Dockerfile final stage runs as a non-root user`() {
        assertFinalStageIsNonRoot(File(repoRoot(), "kotlin-xpdf/Dockerfile"))
    }

    /** The last `FROM` in a multi-stage Dockerfile starts the final (shipped) stage; a `USER` line
     *  after it must be present and must not name root (by name or numeric uid/gid 0). */
    private fun assertFinalStageIsNonRoot(dockerfile: File) {
        assertTrue(dockerfile.isFile, "expected Dockerfile at ${dockerfile.path}")
        val lines = dockerfile.readText().lines()
        val lastFromIndex = lines.indexOfLast { it.trim().startsWith("FROM ") }
        assertTrue(lastFromIndex >= 0, "no FROM instruction found in ${dockerfile.path}")
        val finalStage = lines.subList(lastFromIndex, lines.size)
        val userLine = finalStage.firstOrNull { it.trim().startsWith("USER ") }
        assertTrue(userLine != null, "final stage of ${dockerfile.path} must set a USER")
        val user = userLine!!.trim().removePrefix("USER").trim()
        val isRoot = user == "root" || user.startsWith("root:") ||
            user == "0" || user.startsWith("0:")
        assertTrue(!isRoot, "final stage of ${dockerfile.path} must not run as root (found: USER $user)")
    }
}
