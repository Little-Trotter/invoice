package de.littletrotter.sbom

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

private const val MIN_LICENSE_COVERAGE = 0.99

/**
 * BSI TR-03183-2 § 5.2 required-data-field catalogue, checked against kotlin-xpdf's own
 * document (`target/classes/bom.json`, written by cyclonedx-maven-plugin's makeBom execution and
 * then completed by the sbom-postprocess tool — both bound to `prepare-package`, which runs
 * before this `integration-test`-phase class does). kotlin-xpdf is its own delivered product
 * (SBOM spec S4/B2) with its own primary component, so it carries the full catalogue exactly like
 * kotlin-invoice's document. One assertion per required field, not a lump check.
 *
 * No `@QuarkusTest` here — unlike kotlin-invoice, this module carries no quarkus-junit5
 * dependency (its existing *IT classes drive Testcontainers directly via `@ExtendWith`, never via
 * the Quarkus test container), and this test needs neither CDI nor a running app, only the file
 * `prepare-package` already produced.
 */
class SbomFieldCatalogueIT {

    // § 5.2.1 — required data fields for the SBOM itself

    @Test
    fun `creator of the SBOM is present as a URL`() {
        assertTrue(root.at("/metadata/manufacturer/url/0").asText().startsWith("http"))
    }

    @Test
    fun `timestamp of the SBOM is present`() {
        assertTrue(root.at("/metadata/timestamp").asText().isNotBlank())
    }

    // § 5.2.2 — required data fields for each component. Checked on the primary component (the
    // one entry sbom-postprocess is always guaranteed to enrich) plus aggregate checks over the
    // full recorded component set.

    @Test
    fun `component creator is present on the primary component`() {
        assertTrue(primary.at("/manufacturer/url/0").asText().startsWith("http"))
    }

    @Test
    fun `component name is present on every component`() {
        assertTrue(components.all { it.get("name")?.asText()?.isNotBlank() == true })
    }

    @Test
    fun `component version is present on every component`() {
        assertTrue(components.all { it.get("version")?.asText()?.isNotBlank() == true })
    }

    @Test
    fun `filename of the component is present on the primary component`() {
        assertTrue(property(primary, "bsi:component:filename")?.isNotBlank() == true)
    }

    @Test
    fun `dependencies on other components are recorded`() {
        val dependencies = root.get("dependencies")
        assertTrue(dependencies != null && dependencies.size() > 0)
    }

    @Test
    fun `completeness of the dependency enumeration is indicated`() {
        assertEquals("complete", root.at("/compositions/0/aggregate").asText())
    }

    @Test
    fun `distribution licences are present on almost every component`() {
        val withLicense = components.count { (it.get("licenses")?.size() ?: 0) > 0 }
        assertTrue(withLicense.toDouble() / components.size >= MIN_LICENSE_COVERAGE)
    }

    @Test
    fun `hash value SHA-512 is present on every component`() {
        assertTrue(
            components.all { component ->
                component.get("hashes")?.any { it.get("alg")?.asText() == "SHA-512" } == true
            },
        )
    }

    @Test
    fun `executable property is present on the primary component`() {
        assertEquals("executable", property(primary, "bsi:component:executable"))
    }

    @Test
    fun `archive property is present on the primary component`() {
        assertEquals("archive", property(primary, "bsi:component:archive"))
    }

    @Test
    fun `structured property is present on the primary component`() {
        assertEquals("structured", property(primary, "bsi:component:structured"))
    }

    companion object {
        private val mapper = ObjectMapper()
        private val root: JsonNode = Files.newInputStream(Paths.get("target", "classes", "bom.json")).use { mapper.readTree(it) }
        private val components: List<JsonNode> = root.get("components")?.toList().orEmpty()
        private val primary: JsonNode = root.at("/metadata/component")

        private fun property(
            node: JsonNode,
            name: String,
        ): String? = node.get("properties")?.firstOrNull { it.get("name")?.asText() == name }?.get("value")?.asText()
    }
}
