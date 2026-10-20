package de.littletrotter.xpdf.impl

import de.littletrotter.shared.process.ProcessResult
import de.littletrotter.shared.process.ProcessRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The process handling around the Typst CLI, with the runner faked: every failure shape the
 * engine can produce must come back as a [TypstCompileResult.Failure] with the compiler's
 * diagnostics attached — never as an exception, never as a silently empty document. The real
 * binary is exercised by `TypstCompileIT`.
 */
class TypstCompilerTest {

    private val creation: Instant = Instant.ofEpochSecond(1_790_000_000L)

    private fun request() = TypstCompileRequest(
        templateSource = "#let data = json(\"data.json\")\n#data.title",
        dataJson = """{"title":"Synthetische Rechnung"}""",
        creationTimestamp = creation,
    )

    private fun ok(stdout: String = "", stderr: String = "") = ProcessResult(exitCode = 0, stdout = stdout, stderr = stderr, timedOut = false)

    private fun compiler(runner: ProcessRunner, fontPath: String? = "/opt/typst/fonts") = TypstCompiler(
        cliPath = "/opt/typst/typst",
        pinnedVersion = "0.15.1",
        fontPath = fontPath,
        timeoutSeconds = 7,
        processRunner = runner,
    )

    /** A runner that "compiles": it writes a fake PDF at the output path the command names. */
    private fun writingRunner(payload: ByteArray = "%PDF-1.7 fake".toByteArray()): ProcessRunner {
        val runner = mock<ProcessRunner>()
        doAnswer { invocation ->
            val command = invocation.getArgument<List<String>>(0)
            Files.write(Path.of(command.last()), payload)
            ok()
        }.whenever(runner).run(any(), any(), any(), anyOrNull())
        return runner
    }

    private fun fieldValue(target: Any, name: String): Any? =
        TypstCompiler::class.java.getDeclaredField(name).apply { isAccessible = true }.get(target)

    @Nested
    inner class Configuration {

        @Test
        fun `boundary - the CDI constructor reads every TYPST_ env var when present`() {
            org.mockito.Mockito.mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
                staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("TYPST_CLI") }.thenReturn("/usr/local/bin/typst")
                staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("TYPST_VERSION") }.thenReturn("9.9.9")
                staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("TYPST_FONT_PATH") }.thenReturn("/fonts")
                staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("TYPST_TIMEOUT") }.thenReturn("33")

                val compiler = TypstCompiler()

                assertEquals("/usr/local/bin/typst", fieldValue(compiler, "cliPath"))
                assertEquals("9.9.9", compiler.pinnedVersion())
                assertEquals("/fonts", fieldValue(compiler, "fontPath"))
                assertEquals(33L, fieldValue(compiler, "timeoutSeconds"))
            }
        }

        @Test
        fun `boundary - the CDI constructor falls back to defaults when no TYPST_ env vars are set`() {
            org.mockito.Mockito.mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use {
                val compiler = TypstCompiler()

                assertEquals("typst", fieldValue(compiler, "cliPath"))
                assertEquals(TypstCompiler.DEFAULT_VERSION, compiler.pinnedVersion())
                assertNull(fieldValue(compiler, "fontPath"))
                assertEquals(60L, fieldValue(compiler, "timeoutSeconds"))
            }
        }

        @Test
        fun `boundary - a non-numeric TYPST_TIMEOUT falls back to the default deadline`() {
            org.mockito.Mockito.mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
                staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv("TYPST_TIMEOUT") }.thenReturn("soon")
                assertEquals(60L, fieldValue(TypstCompiler(), "timeoutSeconds"))
            }
        }
    }

    @Nested
    inner class Command {

        @Test
        fun `happy path - the invocation sandboxes the root, pins fonts, standard and timestamp`() {
            val workDir = Path.of("/tmp/typst-x")
            val command = TypstCompiler.commandArguments("/opt/typst/typst", "/opt/typst/fonts", workDir, creation)

            assertEquals(
                listOf(
                    "/opt/typst/typst", "compile",
                    "--root", "/tmp/typst-x",
                    "--ignore-system-fonts",
                    "--font-path", "/opt/typst/fonts",
                    "--pdf-standard", "a-3b",
                    "--creation-timestamp", "1790000000",
                    "/tmp/typst-x/main.typ", "/tmp/typst-x/document.pdf",
                ),
                command,
            )
        }

        @Test
        fun `boundary - without a font path the flag is omitted but system fonts stay ignored`() {
            val command = TypstCompiler.commandArguments("typst", null, Path.of("/w"), creation)

            assertFalse(command.contains("--font-path"))
            assertTrue(command.contains("--ignore-system-fonts"))
        }
    }

    @Nested
    inner class Compile {

        @Test
        fun `happy path - a zero exit with an output file yields the PDF bytes`() {
            val result = compiler(writingRunner("%PDF-1.7 synthetic".toByteArray())).compile(request())

            val success = assertInstanceOf(TypstCompileResult.Success::class.java, result)
            assertEquals("%PDF-1.7 synthetic", String(success.pdfBytes))
        }

        @Test
        fun `happy path - the run stages template and data next to each other and hands the work dir to the runner`() {
            val runner = mock<ProcessRunner>()
            val staged = mutableMapOf<String, String>()
            doAnswer { invocation ->
                val command = invocation.getArgument<List<String>>(0)
                val workDir = Path.of(command[command.indexOf("--root") + 1])
                staged["template"] = Files.readString(workDir.resolve(TypstCompiler.TEMPLATE_FILE))
                staged["data"] = Files.readString(workDir.resolve(TypstCompiler.DATA_FILE))
                staged["cwd"] = invocation.getArgument<java.io.File?>(3)?.toPath().toString()
                staged["root"] = workDir.toString()
                ok()
            }.whenever(runner).run(any(), any(), any(), anyOrNull())

            val result = compiler(runner).compile(request())

            assertInstanceOf(TypstCompileResult.Failure::class.java, result) // runner wrote no PDF
            assertEquals(request().templateSource, staged["template"])
            assertEquals(request().dataJson, staged["data"])
            assertEquals(staged["root"], staged["cwd"])
        }

        @Test
        fun `boundary - the working directory is removed after the run, success or not`() {
            val captured = argumentCaptor<List<String>>()
            val runner = writingRunner()
            compiler(runner).compile(request())
            org.mockito.kotlin.verify(runner).run(captured.capture(), any(), any(), anyOrNull())

            val root = Path.of(captured.firstValue[captured.firstValue.indexOf("--root") + 1])
            assertFalse(Files.exists(root), "work dir must not survive the run: $root")
        }

        @Test
        fun `boundary - a non-zero exit is a failure carrying the compiler diagnostics`() {
            val runner = mock<ProcessRunner>()
            doReturn(ProcessResult(exitCode = 1, stdout = "", stderr = "error: unknown variable: dta\n  ┌─ main.typ:2:2", timedOut = false))
                .whenever(runner).run(any(), any(), any(), anyOrNull())

            val failure = assertInstanceOf(TypstCompileResult.Failure::class.java, compiler(runner).compile(request()))

            assertTrue(failure.reason.contains("Exitcode 1"), failure.reason)
            assertTrue(failure.diagnostics.contains("unknown variable"), failure.diagnostics)
        }

        @Test
        fun `boundary - a zero exit without an output file is still a failure`() {
            val runner = mock<ProcessRunner>()
            doReturn(ok(stderr = "warning: something")).whenever(runner).run(any(), any(), any(), anyOrNull())

            val failure = assertInstanceOf(TypstCompileResult.Failure::class.java, compiler(runner).compile(request()))

            assertTrue(failure.reason.contains("keine PDF-Datei"), failure.reason)
            assertEquals("warning: something", failure.diagnostics)
        }

        @Test
        fun `boundary - a timeout is a failure naming the deadline`() {
            val runner = mock<ProcessRunner>()
            doReturn(ProcessResult(exitCode = null, stdout = "", stderr = "", timedOut = true))
                .whenever(runner).run(any(), any(), any(), anyOrNull())

            val failure = assertInstanceOf(TypstCompileResult.Failure::class.java, compiler(runner).compile(request()))

            assertTrue(failure.reason.contains("7s"), failure.reason)
        }

        @Test
        fun `boundary - a CLI that cannot be started is a failure naming the path`() {
            val runner = mock<ProcessRunner>()
            doThrow(IOException("No such file or directory")).whenever(runner).run(any(), any(), any(), anyOrNull())

            val failure = assertInstanceOf(TypstCompileResult.Failure::class.java, compiler(runner).compile(request()))

            assertTrue(failure.reason.contains("/opt/typst/typst"), failure.reason)
        }

        @Test
        fun `boundary - an interrupted run is a failure and re-sets the interrupt flag`() {
            val runner = mock<ProcessRunner>()
            doThrow(InterruptedException("stop")).whenever(runner).run(any(), any(), any(), anyOrNull())

            val failure = assertInstanceOf(TypstCompileResult.Failure::class.java, compiler(runner).compile(request()))

            assertTrue(Thread.interrupted(), "interrupt flag must be restored for the caller")
            assertTrue(failure.reason.contains("unterbrochen"), failure.reason)
        }

        @Test
        fun `boundary - a failing file writer is a failure, not an exception`() {
            val runner = mock<ProcessRunner>()
            val compiler = compiler(runner)
            compiler.fileWriter = { _, _ -> throw IOException("disk full") }

            val failure = assertInstanceOf(TypstCompileResult.Failure::class.java, compiler.compile(request()))

            assertTrue(failure.reason.contains("disk full"), failure.reason)
            org.mockito.kotlin.verifyNoInteractions(runner)
        }

        @Test
        fun `boundary - long diagnostics are trimmed to a preview`() {
            val runner = mock<ProcessRunner>()
            doReturn(ProcessResult(exitCode = 2, stdout = "", stderr = "x".repeat(5000), timedOut = false))
                .whenever(runner).run(any(), any(), any(), anyOrNull())

            val failure = assertInstanceOf(TypstCompileResult.Failure::class.java, compiler(runner).compile(request()))

            assertEquals(2000, failure.diagnostics.length)
        }

        @Test
        fun `happy path - an extra file from the request is written into the work dir via the file writer seam`() {
            val compiler = compiler(writingRunner())
            val written = mutableMapOf<String, String>()
            compiler.fileWriter = { path, text -> written[path.fileName.toString()] = text }

            compiler.compile(request().copy(files = mapOf("qr.svg" to "<svg/>")))

            assertEquals("<svg/>", written["qr.svg"])
        }

        @Test
        fun `boundary - an invalid extra file name is a failure naming it, without ever invoking the runner`() {
            val invalidNames = listOf("../x.svg", "qr svg", "QR.svg", "main.typ")

            for (name in invalidNames) {
                val runner = mock<ProcessRunner>()
                val failure = assertInstanceOf(
                    TypstCompileResult.Failure::class.java,
                    compiler(runner).compile(request().copy(files = mapOf(name to "x"))),
                )
                assertTrue(failure.reason.contains(name), failure.reason)
                org.mockito.kotlin.verifyNoInteractions(runner)
            }
        }
    }

    @Nested
    inner class Probe {

        @Test
        fun `happy path - the version is read from the CLI banner and cached`() {
            val runner = mock<ProcessRunner>()
            doReturn(ok(stdout = "typst 0.15.1 (9dfd3a08)")).whenever(runner).run(any(), any(), any(), anyOrNull())
            val compiler = compiler(runner)

            assertEquals("0.15.1", compiler.installedVersion())
            assertEquals("0.15.1", compiler.installedVersion())
            assertTrue(compiler.isAvailable())
            org.mockito.kotlin.verify(runner, org.mockito.kotlin.times(1)).run(any(), any(), any(), anyOrNull())
        }

        @Test
        fun `boundary - a banner without a version number falls back to the trimmed text`() {
            val runner = mock<ProcessRunner>()
            doReturn(ok(stdout = " custom build \n")).whenever(runner).run(any(), any(), any(), anyOrNull())

            assertEquals("custom build", compiler(runner).installedVersion())
        }

        @Test
        fun `boundary - a missing CLI reports not available`() {
            val runner = mock<ProcessRunner>()
            doThrow(IOException("not found")).whenever(runner).run(any(), any(), any(), anyOrNull())
            val compiler = compiler(runner)

            assertNull(compiler.installedVersion())
            assertFalse(compiler.isAvailable())
        }

        @Test
        fun `boundary - a failing or hanging probe reports not available`() {
            val failing = mock<ProcessRunner>()
            doReturn(ProcessResult(exitCode = 1, stdout = "", stderr = "boom", timedOut = false)).whenever(failing).run(any(), any(), any(), anyOrNull())
            assertNull(compiler(failing).installedVersion())

            val hanging = mock<ProcessRunner>()
            doReturn(ProcessResult(exitCode = null, stdout = "", stderr = "", timedOut = true)).whenever(hanging).run(any(), any(), any(), anyOrNull())
            assertNull(compiler(hanging).installedVersion())

            val blank = mock<ProcessRunner>()
            doReturn(ok(stdout = "  ")).whenever(blank).run(any(), any(), any(), anyOrNull())
            assertNull(compiler(blank).installedVersion())
        }

        @Test
        fun `boundary - an interrupted probe reports not available and re-sets the interrupt flag`() {
            val runner = mock<ProcessRunner>()
            doThrow(InterruptedException("stop")).whenever(runner).run(any(), any(), any(), anyOrNull())

            assertNull(compiler(runner).installedVersion())
            assertTrue(Thread.interrupted())
        }

        @Test
        fun `boundary - the probe never waits longer than the configured compile deadline`() {
            val captured = argumentCaptor<java.time.Duration>()
            val runner = mock<ProcessRunner>()
            doReturn(ok(stdout = "typst 0.15.1")).whenever(runner).run(any(), any(), captured.capture(), anyOrNull())

            compiler(runner).installedVersion()

            assertEquals(java.time.Duration.ofSeconds(7), captured.firstValue)
        }
    }
}
