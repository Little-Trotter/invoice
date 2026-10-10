package de.littletrotter.xpdf.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Typst release is pinned in three places that cannot reference each other: the runtime
 * image, the integration-test image and the version the worker reports. This test is the
 * lockstep guard — a bump that forgets one of them fails here, not in production.
 */
class TypstToolchainPinTest {

    private val runtimeImage: String = Files.readString(Path.of("Dockerfile"))
    private val testImage: String = Files.readString(Path.of("docker", "typst", "Dockerfile"))

    private fun arg(dockerfile: String, name: String): String =
        Regex("""^ARG $name=(\S+)$""", RegexOption.MULTILINE).find(dockerfile)?.groupValues?.get(1)
            ?: error("no `ARG $name=` in Dockerfile")

    @Test
    fun `both images pin the version the compiler reports`() {
        assertEquals(TypstCompiler.DEFAULT_VERSION, arg(runtimeImage, "TYPST_VERSION"))
        assertEquals(TypstCompiler.DEFAULT_VERSION, arg(testImage, "TYPST_VERSION"))
    }

    @Test
    fun `both images verify the same checksums per architecture`() {
        for (arch in listOf("X86_64", "AARCH64")) {
            val sum = arg(runtimeImage, "TYPST_SHA256_$arch")
            assertEquals(sum, arg(testImage, "TYPST_SHA256_$arch"), arch)
            assertTrue(Regex("^[0-9a-f]{64}$").matches(sum), "$arch: not a SHA-256 hex digest: $sum")
        }
    }

    @Test
    fun `the runtime image installs Typst by default and exports the CLI location`() {
        assertTrue(runtimeImage.contains("ARG INSTALL_TYPST=true"))
        assertTrue(runtimeImage.contains("ENV TYPST_CLI=/opt/typst/typst"))
        assertTrue(runtimeImage.contains("ENV TYPST_FONT_PATH=/opt/typst/fonts"))
    }
}
