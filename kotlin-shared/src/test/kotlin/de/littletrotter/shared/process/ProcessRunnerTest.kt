package de.littletrotter.shared.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.function.ThrowingSupplier
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * [ProcessRunner] is the process-timeout fix extracted out of `VeraPdfCliValidator`
 * (kotlin-xpdf, commit `a65bbbda`). These tests exercise real short-lived processes
 * (`/bin/echo`, `/bin/sh`, `/bin/sleep`) rather than mocks — the bug this guards against only
 * shows up against a real, misbehaving child process.
 */
@DisabledOnOs(OS.WINDOWS) // relies on POSIX shell utilities (/bin/echo, /bin/sh, /bin/sleep)
class ProcessRunnerTest {

    private val runner = ProcessRunner()

    @Test
    fun `a short-lived command completes with its stdout and exit code`() {
        val result = runner.run(listOf("/bin/echo", "hello process runner"), timeout = Duration.ofSeconds(5))

        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        assertEquals("hello process runner", result.stdout.trim())
        assertTrue(result.stderr.isBlank())
    }

    @Test
    fun `stdout and stderr are captured separately`() {
        val result = runner.run(
            listOf("/bin/sh", "-c", "echo out-line; echo err-line 1>&2; exit 3"),
            timeout = Duration.ofSeconds(5),
        )

        assertFalse(result.timedOut)
        assertEquals(3, result.exitCode)
        assertEquals("out-line", result.stdout.trim())
        assertEquals("err-line", result.stderr.trim())
    }

    @Test
    fun `a process that outlives its timeout is killed and reported as timed out`() {
        // Same shape as the IMP-4 fixture in VeraPdfCliValidatorTest: a process that never exits
        // and never closes its pipes. If run() waited on the readers before waitFor, this test
        // would hang instead of failing fast.
        val result: ProcessResult = assertTimeoutPreemptively(
            Duration.ofSeconds(20),
            ThrowingSupplier { runner.run(listOf("/bin/sleep", "60"), timeout = Duration.ofSeconds(1)) },
        )

        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun `a timed-out process is actually killed, not just abandoned`() {
        val pidFile = Files.createTempFile("process-runner-pid-", ".txt")
        try {
            val result = assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                ThrowingSupplier {
                    runner.run(
                        listOf("/bin/sh", "-c", "echo \$\$ > $pidFile; sleep 60"),
                        timeout = Duration.ofSeconds(2),
                    )
                },
            )
            assertTrue(result.timedOut)

            val childPid = Files.readString(pidFile).trim().toLong()
            // destroyForcibly() is asynchronous on some platforms; poll briefly for the child to
            // actually exit rather than asserting immediately (flaky) or not at all (meaningless).
            var stillAlive = ProcessHandle.of(childPid).map { it.isAlive }.orElse(false)
            var attempts = 0
            while (stillAlive && attempts < 20) {
                Thread.sleep(100)
                stillAlive = ProcessHandle.of(childPid).map { it.isAlive }.orElse(false)
                attempts++
            }
            assertFalse(stillAlive, "child process $childPid must be dead after the timeout kills it")
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    @Test
    fun `additional environment variables are visible to the child`() {
        val result = runner.run(
            listOf("/bin/sh", "-c", "echo \$PROCESS_RUNNER_TEST_VAR"),
            env = mapOf("PROCESS_RUNNER_TEST_VAR" to "arc3-value"),
            timeout = Duration.ofSeconds(5),
        )

        assertEquals("arc3-value", result.stdout.trim())
    }

    @Test
    fun `process runs in specified working directory`() {
        val tempDir = Files.createTempDirectory("process-runner-wd-")
        try {
            val result = runner.run(
                listOf("/bin/sh", "-c", "pwd"),
                workingDir = tempDir.toFile(),
                timeout = Duration.ofSeconds(5),
            )

            assertFalse(result.timedOut)
            assertEquals(0, result.exitCode)
            // On macOS, /tmp is sym
            // linked to /private/tmp, so just check the end of the path
            val outputPath = result.stdout.trim()
            assertTrue(outputPath.endsWith(tempDir.fileName.toString()),
                "Process output '$outputPath' should end with temp dir name")
        } finally {
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `process runs without working directory specified inherits parent cwd`() {
        val result = runner.run(
            listOf("/bin/sh", "-c", "pwd"),
            timeout = Duration.ofSeconds(5),
        )

        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.trim().startsWith("/"))
    }

    // ── drain()/await()'s own runCatching{...}.getOrDefault("") failure branches never
    //    fire through a real, well-behaved child process; internal so a unit test can drive them
    //    directly with a stream/future that actually fails, per this class's own KDoc reasoning. ──

    private class ThrowingInputStream : java.io.InputStream() {
        override fun read(): Int = throw java.io.IOException("boundary - simulated read failure")
    }

    @Test
    fun `boundary - drain returns empty text, not a thrown exception, when the stream fails to read`() {
        val future = runner.drain(ThrowingInputStream())

        assertEquals("", future.get(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun `boundary - await returns empty text, not a thrown exception, when the future completed exceptionally`() {
        val failed = CompletableFuture<String>()
        failed.completeExceptionally(RuntimeException("boundary - simulated reader-thread crash"))

        // await() is a member extension function of ProcessRunner (not top-level) — `with(runner)`
        // brings it into scope the same way ProcessRunner's own run() implicitly does.
        val result = with(runner) { failed.await() }

        assertEquals("", result)
    }
}

class ProcessRunnerEnvironmentTest {

    @Test
    fun `dropped variables are absent from the child environment and explicit ones are set`() {
        val dropped = System.getenv().keys.first()
        val builder = ProcessRunner(inheritedEnvToDrop = setOf(dropped))
            .newBuilder(listOf("x"), mapOf("JAVA_OPTS" to "-Xmx64m"), null)

        assertFalse(builder.environment().containsKey(dropped))
        assertEquals("-Xmx64m", builder.environment()["JAVA_OPTS"])
    }

    @Test
    fun `working directory is applied when given`() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"))
        val builder = ProcessRunner().newBuilder(listOf("x"), emptyMap(), dir)

        assertEquals(dir, builder.directory())
    }
}
