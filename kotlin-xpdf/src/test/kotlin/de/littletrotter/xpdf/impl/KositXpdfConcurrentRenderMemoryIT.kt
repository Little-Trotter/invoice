package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.infrastructure.messaging.JobConsumer
import de.littletrotter.xpdf.infrastructure.messaging.JobLane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The spec's own "Tests" requirement: "a memory test in kotlin-xpdf that renders
 * JOB_WORKER_THREADS demo invoices concurrently under the configured `-Xmx` and asserts no OOM
 * (run with the compose limits, not the IDE's)". Forks a genuinely separate JVM constrained to
 * [HEAP_MB] — the exact heap `docker-compose.yml`'s START profile resolves to (75 % of the 2048M container limit) — rather than whatever
 * heap surefire's own forked JVM (or an IDE run configuration) happens to default to; the render
 * work itself runs in [ConcurrentRenderMemoryHarness]'s `main`, in that child process.
 */
class KositXpdfConcurrentRenderMemoryIT {

    @Test
    fun `JOB_WORKER_THREADS concurrent renders complete within the compose-configured heap without OOM`() {
        val bulkThreads = JobConsumer.defaultWorkerThreads(maxHeapBytes = HEAP_MB * 1024L * 1024L)
        assertTrue(bulkThreads >= 1, "the formula itself must never derive fewer than one worker thread")
        // The interactive lane renders at the same time as the bulk lane, so the harness runs both.
        val threadCount = bulkThreads + JobLane.DEFAULT_INTERACTIVE_THREADS

        val javaHome = System.getProperty("java.home")
        val javaBin = "$javaHome/bin/java"
        val classpath = System.getProperty("java.class.path")

        val process = ProcessBuilder(
            javaBin,
            "-Xmx${HEAP_MB}m",
            "-XX:+UseG1GC",
            "-XX:+ExitOnOutOfMemoryError",
            "-cp", classpath,
            "de.littletrotter.xpdf.impl.ConcurrentRenderMemoryHarnessKt",
            threadCount.toString(),
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }

        assertTrue(finished, "the memory harness process did not finish within the timeout -- output so far:\n$output")
        assertEquals(
            0, process.exitValue(),
            "the memory harness process must exit 0 (no OOM, no render exception) at $threadCount concurrent " +
                "renders inside a ${HEAP_MB}m heap -- output:\n$output",
        )
    }

    private companion object {
        // Same numbers as docker-compose.yml's own LT_XPDF_MEMORY_LIMIT/MaxRAMPercentage comment:
        // 75 % MaxRAMPercentage of the 2048M container limit.
        const val HEAP_MB = 1536L
    }
}
