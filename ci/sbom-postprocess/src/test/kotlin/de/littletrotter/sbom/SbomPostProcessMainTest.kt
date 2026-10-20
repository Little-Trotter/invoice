package de.littletrotter.sbom

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SbomPostProcessMainTest {

    private val mapper = ObjectMapper()

    // `main` anchors every path argument to the process's working directory and rejects anything
    // that resolves outside it (see `resolveWithinBaseDir` in SbomPostProcessMain) — fixture files
    // therefore live under `target/`, not the OS temp directory `Files.createTempFile()` alone
    // would default to.
    private val tempDir: Path = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "sbom-main-test")

    private fun tempFile(prefix: String, suffix: String): Path = Files.createTempFile(tempDir, prefix, suffix)

    @Test
    fun `main enriches the bom file in place given only a bom file and a creator url`() {
        val bomFile = tempFile("bom", ".json")
        Files.writeString(bomFile, """{"metadata":{}}""")

        main(arrayOf(bomFile.toString(), "https://example.org/project"))

        val result = mapper.readTree(bomFile.toFile())
        assertThat(result.at("/metadata/manufacturer/url/0").asText()).isEqualTo("https://example.org/project")
    }

    @Test
    fun `main merges the frontend bom and then enriches when five arguments are given`() {
        val bomFile = tempFile("bom", ".json")
        Files.writeString(
            bomFile,
            """{"metadata":{"component":{"name":"kotlin-invoice","version":"1.0.0",
                "bom-ref":"pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar"}},
                "components":[],"dependencies":[
                  {"ref":"pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar"}
                ]}""",
        )
        val frontendFile = tempFile("frontend-bom", ".json")
        Files.writeString(
            frontendFile,
            """{"metadata":{"component":{"name":"no-name","bom-ref":"no-name"}},"components":[],"dependencies":[{"ref":"no-name"}]}""",
        )

        main(
            arrayOf(
                bomFile.toString(),
                "https://example.org/project",
                frontendFile.toString(),
                "kotlin-invoice-frontend",
                "1.0.0",
            ),
        )

        val result = mapper.readTree(bomFile.toFile())
        val bundle = result.get("components").first { it.get("name").asText() == "kotlin-invoice-frontend" }
        assertThat(bundle.get("bom-ref").asText()).isEqualTo("kotlin-invoice-frontend")
        assertThat(result.at("/metadata/manufacturer/url/0").asText()).isEqualTo("https://example.org/project")
    }

    @Test
    fun `main rejects an argument count that is neither two nor five`() {
        assertThatThrownBy { main(arrayOf("one-arg")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { main(arrayOf("a", "b", "c")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { main(arrayOf("a", "b", "c", "d")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { main(arrayOf("a", "b", "c", "d", "e", "f")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `main rejects a bom file whose top-level JSON value is not an object`() {
        val bomFile = tempFile("bom", ".json")
        Files.writeString(bomFile, "[]")

        assertThatThrownBy { main(arrayOf(bomFile.toString(), "https://example.org/project")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `main rejects a bom-file argument that escapes the working directory`() {
        assertThatThrownBy { main(arrayOf("../../../../etc/passwd", "https://example.org/project")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("escapes the working directory")

        assertThatThrownBy { main(arrayOf("/etc/passwd", "https://example.org/project")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("escapes the working directory")
    }

    @Test
    fun `main rejects a frontend-bom-file argument that escapes the working directory`() {
        val bomFile = tempFile("bom", ".json")
        Files.writeString(bomFile, """{"metadata":{}}""")

        assertThatThrownBy {
            main(
                arrayOf(
                    bomFile.toString(),
                    "https://example.org/project",
                    "../../../../etc/passwd",
                    "kotlin-invoice-frontend",
                    "1.0.0",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("escapes the working directory")
    }
}
