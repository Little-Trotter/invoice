package de.littletrotter.sbom

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * SBOM spec R1 / "Abgrenzungstest" — no artefact of the build or deployment process may appear in
 * the delivered document (`target/classes/bom.json`). Checked against a concrete list of known
 * tooling: test frameworks, linters, Maven plugins, Testcontainers, and the frontend's npm
 * devDependencies. Without this test the R1 decision quietly erodes at the next dependency
 * change — a hit against this list is a failure, not a warning.
 */
@QuarkusTest
class SbomDelimitationIT {

    @Test
    fun `no known build or deployment tooling appears among the delivered components`() {
        components.forEach { component ->
            val identity = componentIdentity(component)
            forbiddenMarkers.forEach { marker ->
                assertTrue(!identity.contains(marker), "forbidden tooling marker \"$marker\" found in delivered component: $identity")
            }
        }
    }

    @Test
    fun `only compile- and runtime-scoped components are recorded`() {
        components.forEach { component ->
            val scope = component.get("scope")?.asText()
            assertTrue(
                scope == null || scope == "required" || scope == "optional",
                "unexpected scope \"$scope\" on delivered component ${componentIdentity(component)}",
            )
        }
    }

    private fun componentIdentity(component: JsonNode): String {
        val name = component.get("name")?.asText()?.lowercase() ?: ""
        val group = component.get("group")?.asText()?.lowercase() ?: ""
        val purl = component.get("purl")?.asText()?.lowercase() ?: ""
        return "$name $group $purl"
    }

    companion object {
        private val mapper = ObjectMapper()
        private val root: JsonNode = Files.newInputStream(Paths.get("target", "classes", "bom.json")).use { mapper.readTree(it) }
        private val components: List<JsonNode> = root.get("components")?.toList().orEmpty()

        // Known build-/deployment-process tooling that must never appear as a delivered
        // component: test frameworks, linters, Maven plugins, Testcontainers, npm devDependencies.
        private val forbiddenMarkers = listOf(
            "junit",
            "mockito",
            "assertj",
            "hamcrest",
            "testcontainers",
            "jacoco",
            "surefire",
            "failsafe",
            "cyclonedx-maven-plugin",
            "detekt",
            "ktlint",
            "sqlfluff",
            "archunit",
            "karibu-testing",
            // frontend build tooling (npm devDependencies) — B1/S1's own delimitation
            "vite",
            "typescript",
            "babel",
            "rollup",
            "vite-plugin-checker",
        )
    }
}
