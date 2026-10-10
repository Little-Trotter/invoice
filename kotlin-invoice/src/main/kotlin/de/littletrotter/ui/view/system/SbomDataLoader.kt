package de.littletrotter.ui.view.system

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val BOM_JSON = "bom.json"

data class SbomComponent(
    val name: String,
    val version: String,
    val type: String,
    val license: String,
    val description: String,
)

/**
 * Reads the single CycloneDX document this image ships with and renders it unchanged.
 *
 * SBOM spec rule R3 ("Erzeugt im Bau, unverändert ausgeliefert"): what is displayed is the build
 * artefact as-is, not a set recombined at view time. An earlier version of this loader collected
 * up to eleven candidate files (the aggregate plus every individual module document, both
 * container and local-build paths) and deduplicated their components across all of them — that
 * could show a component set that never existed as a single build artefact, which is exactly what
 * R3 forbids. The image now carries exactly one document for this product (see the Dockerfile),
 * so this loader tries a short, ordered list of paths for that one document — the production
 * container path first, then the paths a local `mvn package`/`quarkus:dev` run produces — and uses
 * the first one found. It never merges more than one file.
 */
object SbomDataLoader {
    private val objectMapper = ObjectMapper()

    private val defaultFilePaths = listOf(
        Paths.get("/app", "sbom", BOM_JSON),
        Paths.get("target", "classes", BOM_JSON),
        Paths.get("kotlin-invoice", "target", "classes", BOM_JSON),
    )

    fun loadComponents(): List<SbomComponent> = loadComponents(defaultFilePaths)

    internal fun loadComponents(filePaths: List<Path>): List<SbomComponent> {
        val path = filePaths.firstOrNull { Files.exists(it) } ?: return emptyList()
        return Files.newInputStream(path).use { input -> parse(input) }
    }

    private fun parse(input: InputStream): List<SbomComponent> {
        return try {
            val root: JsonNode = objectMapper.readTree(input)
            val components = root.get("components") ?: return emptyList()
            if (!components.isArray) return emptyList()

            components.mapNotNull { node -> toComponent(node) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun toComponent(node: JsonNode): SbomComponent? {
        val name = node.get("name")?.asText("") ?: ""
        val version = node.get("version")?.asText("") ?: ""
        if (name.isBlank() || version.isBlank()) return null

        val type = node.get("type")?.asText("") ?: ""
        val description = node.get("description")?.asText("") ?: ""
        val license = node.get("licenses")?.let { licenses ->
            if (!licenses.isArray || licenses.size() == 0) {
                ""
            } else {
                val first = licenses.get(0)
                first.get("license")?.get("id")?.asText("")
                    ?: first.get("license")?.get("name")?.asText("")
                    ?: ""
            }
        } ?: ""

        return SbomComponent(
            name = name,
            version = version,
            type = type,
            license = license,
            description = description,
        )
    }
}
