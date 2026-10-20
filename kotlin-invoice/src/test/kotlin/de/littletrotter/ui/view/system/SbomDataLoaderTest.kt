package de.littletrotter.ui.view.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SbomDataLoaderTest {

    @Test
    fun `loadComponents reads the components array of the given file`() {
        val tempDir = Files.createTempDirectory("sbom-loader-test")
        val bomFile = tempDir.resolve("bom.json")
        Files.writeString(
            bomFile,
            """
            {
              "components": [
                {"name":"App Core","version":"1.0.0","type":"library","description":"runtime dep","licenses":[{"license":{"id":"Apache-2.0"}}]}
              ]
            }
            """.trimIndent(),
        )

        val result = SbomDataLoader.loadComponents(listOf(bomFile))

        assertEquals(1, result.size)
        assertEquals("App Core", result.single().name)
        assertEquals("Apache-2.0", result.single().license)
    }

    @Test
    fun `loadComponents returns empty list for invalid json`() {
        val tempDir = Files.createTempDirectory("sbom-loader-invalid")
        val bomFile = tempDir.resolve("bom.json")
        Files.writeString(bomFile, "not-json")

        val result = SbomDataLoader.loadComponents(listOf(bomFile))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadComponents returns empty list when no candidate path exists`() {
        val tempDir = Files.createTempDirectory("sbom-loader-missing")
        val missing = tempDir.resolve("does-not-exist.json")

        val result = SbomDataLoader.loadComponents(listOf(missing))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadComponents uses the first existing candidate and never merges with the rest`() {
        val tempDir = Files.createTempDirectory("sbom-loader-first-match")
        val missing = tempDir.resolve("does-not-exist.json")
        val firstExisting = tempDir.resolve("first.json")
        val secondExisting = tempDir.resolve("second.json")
        Files.writeString(
            firstExisting,
            """{"components":[{"name":"First Lib","version":"1.0.0","type":"library"}]}""",
        )
        Files.writeString(
            secondExisting,
            """{"components":[{"name":"Second Lib","version":"1.0.0","type":"library"}]}""",
        )

        val result = SbomDataLoader.loadComponents(listOf(missing, firstExisting, secondExisting))

        assertEquals(listOf("First Lib"), result.map { it.name })
    }

    @Test
    fun `loadComponents returns empty list for a root with no components array and an empty components array`() {
        val tempDir = Files.createTempDirectory("sbom-loader-no-components")
        val noComponentsFile = tempDir.resolve("bom.json")
        Files.writeString(noComponentsFile, """{"bomFormat":"CycloneDX"}""")

        assertTrue(SbomDataLoader.loadComponents(listOf(noComponentsFile)).isEmpty())

        val notArrayFile = tempDir.resolve("bom2.json")
        Files.writeString(notArrayFile, """{"components": "not-an-array"}""")

        assertTrue(SbomDataLoader.loadComponents(listOf(notArrayFile)).isEmpty())
    }

    @Test
    fun `loadComponents skips entries with a blank or absent name or version`() {
        val tempDir = Files.createTempDirectory("sbom-loader-blank-fields")
        val bomFile = tempDir.resolve("bom.json")
        Files.writeString(
            bomFile,
            """
            {
              "components": [
                {"version":"1.0.0","type":"library","description":"no name field"},
                {"name":"","version":"1.0.0","type":"library","description":"blank name"},
                {"name":"No Version Lib","type":"library","description":"no version field"},
                {"name":"Blank Version Lib","version":"","type":"library","description":"blank version"}
              ]
            }
            """.trimIndent(),
        )

        val result = SbomDataLoader.loadComponents(listOf(bomFile))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadComponents keeps every component the document carries, whatever its scope`() {
        val tempDir = Files.createTempDirectory("sbom-loader-scope")
        val bomFile = tempDir.resolve("bom.json")
        Files.writeString(
            bomFile,
            """
            {
              "components": [
                {"name":"Required Lib","version":"1.0.0","type":"library","scope":"required"},
                {"name":"Optional Lib","version":"1.0.0","type":"library","scope":"optional"},
                {"name":"No Scope Lib","version":"1.0.0","type":"library"}
              ]
            }
            """.trimIndent(),
        )

        val result = SbomDataLoader.loadComponents(listOf(bomFile))

        assertEquals(
            setOf("Required Lib", "Optional Lib", "No Scope Lib"),
            result.map { it.name }.toSet(),
        )
    }

    @Test
    fun `loadComponents resolves license via id, via name fallback, and empty when unavailable`() {
        val tempDir = Files.createTempDirectory("sbom-loader-license")
        val bomFile = tempDir.resolve("bom.json")
        Files.writeString(
            bomFile,
            """
            {
              "components": [
                {"name":"Id Licensed Lib","version":"1.0.0","type":"library","licenses":[{"license":{"id":"MIT"}}]},
                {"name":"Name Licensed Lib","version":"1.0.0","type":"library","licenses":[{"license":{"name":"Apache License 2.0"}}]},
                {"name":"Empty Licenses Lib","version":"1.0.0","type":"library","licenses":[]},
                {"name":"No Licenses Field Lib","version":"1.0.0","type":"library"},
                {"name":"Empty License Object Lib","version":"1.0.0","type":"library","licenses":[{"license":{}}]}
              ]
            }
            """.trimIndent(),
        )

        val result = SbomDataLoader.loadComponents(listOf(bomFile))
        val byName = result.associate { it.name to it.license }

        assertEquals("MIT", byName["Id Licensed Lib"])
        assertEquals("Apache License 2.0", byName["Name Licensed Lib"])
        assertEquals("", byName["Empty Licenses Lib"])
        assertEquals("", byName["No Licenses Field Lib"])
        assertEquals("", byName["Empty License Object Lib"])
    }

    @Test
    fun `loadComponents parameterless overload delegates to the default candidate paths`() {
        // Whether target/classes/bom.json already exists depends on build state (a fresh
        // checkout has none; a re-run after `mvn package` does) — this only exercises the
        // public no-arg entry point without asserting on that environment-dependent result.
        val result = SbomDataLoader.loadComponents()

        assertTrue(result.all { it.name.isNotBlank() && it.version.isNotBlank() })
    }
}
