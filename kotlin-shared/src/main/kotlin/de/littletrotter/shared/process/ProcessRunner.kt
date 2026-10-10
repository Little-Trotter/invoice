package de.littletrotter.shared.process

import java.io.File
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Outcome of a single [ProcessRunner.run] call.
 *
 * [exitCode] is `null` exactly when [timedOut] is `true`: the process was killed before it could
 * exit on its own, so there is no exit code to report. When [timedOut] is `true`, [stdout] and
 * [stderr] are empty rather than partial — see [ProcessRunner.run]'s kdoc for why nothing is read
 * back from a process that just proved it can hang.
 */
data class ProcessResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

/**
 * Runs external processes with a timeout that actually holds.
 *
 * **Origin:** extracted from `VeraPdfCliValidator` (kotlin-xpdf), which carried this
 * exact fix as of commit `a65bbbda`. The bug it fixed: the previous version called
 * `readText()` on the child's stdout *before* `waitFor(timeout)`. `readText()` only returns once
 * the child closes its pipe, so a process that hangs without exiting (or without ever writing and
 * closing its output) blocked the calling thread forever — the timeout code below it was never
 * reached. A configured timeout looked like a safeguard and was none.
 *
 * This helper lives in `kotlin-shared` (not kotlin-xpdf) so both `VeraPdfCliValidator` and
 * `BackupService` (kotlin-invoice, `pg_dump`/`tar`/`git`) share the one correct implementation
 * instead of `BackupService` re-inventing — or getting wrong a second time — the same fix.
 *
 * The fix: both pipes are drained on their own daemon threads *while* the process runs, and only
 * then is the process waited for, with a timeout that actually fires. Daemon threads on purpose:
 * if the child never closes its pipes even after [Process.destroyForcibly], a reader thread must
 * not keep the JVM alive waiting for output that will never come.
 */
class ProcessRunner {

    companion object {
        /** Grace period for handing the already-read text back from a reader thread once the
         *  process has exited. The process is done at that point, so this is not a second, hidden
         *  process timeout — just the hand-off between threads. */
        private const val STREAM_HANDOVER_SECONDS = 10L
    }

    /**
     * Runs [command] and waits at most [timeout] for it to finish.
     *
     * @param env additional environment variables merged into the child's inherited environment
     *   (e.g. `PGPASSWORD`) — this never replaces the inherited environment, only adds to it.
     * @param workingDir working directory for the child process, or `null` to inherit this JVM's.
     *
     * On timeout, the process is killed with [Process.destroyForcibly] and this returns
     * immediately with `timedOut = true`, `exitCode = null`, and empty `stdout`/`stderr`.
     * Deliberately not waiting for the reader threads in that case: whatever partial output they
     * have is discarded, and waiting on a process that just proved it can hang is how the
     * original bug looked in the first place.
     */
    fun run(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        timeout: Duration,
        workingDir: File? = null,
    ): ProcessResult {
        val builder = ProcessBuilder(command)
        if (env.isNotEmpty()) {
            builder.environment().putAll(env)
        }
        if (workingDir != null) {
            builder.directory(workingDir)
        }

        val process = builder.start()
        // Nothing is ever written to the child's stdin. Closing it means a process that decides
        // to read from it gets EOF instead of waiting forever for input that will never come.
        runCatching { process.outputStream.close() }

        val stdout = drain(process.inputStream)
        val stderr = drain(process.errorStream)

        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return ProcessResult(exitCode = null, stdout = "", stderr = "", timedOut = true)
        }

        return ProcessResult(
            exitCode = process.exitValue(),
            stdout = stdout.await(),
            stderr = stderr.await(),
            timedOut = false,
        )
    }

    /** Reads [stream] to the end on a daemon thread. [CompletableFuture] only hands the result
     *  back, not for composition — the read itself is blocking by nature.
     *
     *  `internal` (not `private`): the `runCatching { ... }.getOrDefault("")` failure
     *  branch (a real child process's stdout/stderr pipe failing mid-read) has no reliable,
     *  deterministic trigger through a real [Process] in a unit test — a unit test with a
     *  synthetic, always-failing [InputStream] exercises it directly instead. */
    internal fun drain(stream: InputStream): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        Thread(
            { future.complete(runCatching { stream.bufferedReader().readText() }.getOrDefault("")) },
            "process-runner-reader",
        ).apply { isDaemon = true }.start()
        return future
    }

    /** The process has exited, so its pipes are closed and the reader is about to finish — the
     *  short grace period only covers the hand-off, never an open-ended wait.
     *
     *  `internal` (not `private`): the `getOrDefault` failure branch fires only if the
     *  reader thread itself throws (crashes) rather than completing normally — that never happens
     *  through a real drain() call in a unit test, so a directly completed-exceptionally
     *  [CompletableFuture] exercises it instead. */
    internal fun CompletableFuture<String>.await(): String =
        runCatching { get(STREAM_HANDOVER_SECONDS, TimeUnit.SECONDS) }.getOrDefault("")
}
