package de.littletrotter.sbom

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NpmSbomMergerTest {

    private val mapper = ObjectMapper()

    private fun parse(json: String): ObjectNode = mapper.readTree(json) as ObjectNode

    private val frontendBom = """
        {
          "metadata": {"component": {"type":"application","name":"no-name","bom-ref":"no-name","purl":"pkg:npm/no-name",
            "licenses":[{"license":{"name":"UNLICENSED"}}],
            "properties":[{"name":"cdx:npm:package:path","value":""}]}},
          "components": [
            {"type":"library","name":"react","version":"19.2.8","bom-ref":"no-name|react@19.2.8","purl":"pkg:npm/react@19.2.8"},
            {"type":"library","name":"date-fns","version":"4.4.0","bom-ref":"no-name|date-fns@4.4.0","purl":"pkg:npm/date-fns@4.4.0"}
          ],
          "dependencies": [
            {"ref":"no-name","dependsOn":["no-name|react@19.2.8","no-name|date-fns@4.4.0"]},
            {"ref":"no-name|react@19.2.8"},
            {"ref":"no-name|date-fns@4.4.0"}
          ]
        }
    """.trimIndent()

    private fun mainBomWithPrimary(dependsOn: String = "") = """
        {
          "metadata": {"component": {"name":"kotlin-invoice","version":"1.0.0",
            "bom-ref":"pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar"}},
          "components": [],
          "dependencies": [
            {"ref":"pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar"$dependsOn}
          ]
        }
    """.trimIndent()

    @Test
    fun `merge renames the frontend primary component and adds it to the main components array`() {
        val main = parse(mainBomWithPrimary())
        val frontend = parse(frontendBom)

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")

        val bundle = main.get("components").first { it.get("name").asText() == "kotlin-invoice-frontend" }
        assertThat(bundle.get("version").asText()).isEqualTo("1.0.0")
        assertThat(bundle.get("bom-ref").asText()).isEqualTo("kotlin-invoice-frontend")
        assertThat(bundle.has("purl")).isFalse()
        assertThat(bundle.has("properties")).isFalse()
    }

    @Test
    fun `merge appends the frontend child components with rewritten bom-refs`() {
        val main = parse(mainBomWithPrimary())
        val frontend = parse(frontendBom)

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")

        val react = main.get("components").first { it.get("name").asText() == "react" }
        assertThat(react.get("bom-ref").asText()).isEqualTo("kotlin-invoice-frontend|react@19.2.8")
    }

    @Test
    fun `merge appends the frontend dependency graph with rewritten refs and dependsOn`() {
        val main = parse(mainBomWithPrimary())
        val frontend = parse(frontendBom)

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")

        val bundleDependency = main.get("dependencies").first { it.get("ref").asText() == "kotlin-invoice-frontend" }
        val dependsOn = bundleDependency.get("dependsOn").map { it.asText() }
        assertThat(dependsOn).containsExactlyInAnyOrder(
            "kotlin-invoice-frontend|react@19.2.8",
            "kotlin-invoice-frontend|date-fns@4.4.0",
        )
    }

    @Test
    fun `merge wires the bundle in as a dependency of the java primary component`() {
        val main = parse(mainBomWithPrimary())
        val frontend = parse(frontendBom)

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")

        val primaryDependency = main.get("dependencies")
            .first { it.get("ref").asText() == "pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar" }
        val dependsOn = primaryDependency.get("dependsOn").map { it.asText() }
        assertThat(dependsOn).contains("kotlin-invoice-frontend")
    }

    @Test
    fun `merge appends to an existing dependsOn list on the primary component`() {
        val main = parse(mainBomWithPrimary(""","dependsOn":["pkg:maven/other/lib@1.0?type=jar"]"""))
        val frontend = parse(frontendBom)

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")

        val primaryDependency = main.get("dependencies")
            .first { it.get("ref").asText() == "pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar" }
        val dependsOn = primaryDependency.get("dependsOn").map { it.asText() }
        assertThat(dependsOn).containsExactlyInAnyOrder(
            "pkg:maven/other/lib@1.0?type=jar",
            "kotlin-invoice-frontend",
        )
    }

    @Test
    fun `merge does nothing when the frontend document has no primary component`() {
        val main = parse(mainBomWithPrimary())
        val frontend = parse("""{"metadata":{}}""")

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")

        assertThat(main.get("components")).isEmpty()
    }

    @Test
    fun `merge does nothing when the frontend document has no metadata at all`() {
        val main = parse(mainBomWithPrimary())
        val frontend = parse("""{}""")

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")

        assertThat(main.get("components")).isEmpty()
    }

    @Test
    fun `merge does nothing when the frontend primary component has no bom-ref`() {
        val main = parse(mainBomWithPrimary())
        val frontend = parse("""{"metadata":{"component":{"name":"no-name"}}}""")

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")

        assertThat(main.get("components")).isEmpty()
    }

    @Test
    fun `merge adds only the renamed bundle when the frontend document has no components or dependencies arrays`() {
        val main = parse(mainBomWithPrimary())
        val frontend = parse("""{"metadata":{"component":{"name":"no-name","bom-ref":"no-name"}}}""")

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")

        assertThat(main.get("components").map { it.get("name").asText() }).containsExactly("kotlin-invoice-frontend")
    }

    @Test
    fun `merge skips a non-object entry in the frontend components array and still merges the rest`() {
        val frontendWithJunk = parse(frontendBom)
        (frontendWithJunk.get("components") as com.fasterxml.jackson.databind.node.ArrayNode).add("not-an-object")
        val main = parse(mainBomWithPrimary())

        NpmSbomMerger.merge(main, frontendWithJunk, mapper, "kotlin-invoice-frontend", "1.0.0")

        assertThat(main.get("components").map { it.get("name")?.asText() }).contains("react", "date-fns")
    }

    @Test
    fun `merge skips a non-object entry in the frontend dependencies array and still merges the rest`() {
        val frontendWithJunk = parse(frontendBom)
        (frontendWithJunk.get("dependencies") as com.fasterxml.jackson.databind.node.ArrayNode).add("not-an-object")
        val main = parse(mainBomWithPrimary())

        NpmSbomMerger.merge(main, frontendWithJunk, mapper, "kotlin-invoice-frontend", "1.0.0")

        val bundleDependency = main.get("dependencies").first { it.get("ref").asText() == "kotlin-invoice-frontend" }
        assertThat(bundleDependency).isNotNull()
    }

    @Test
    fun `merge creates components and dependencies arrays on the main document when absent`() {
        val main = parse(
            """{"metadata":{"component":{"name":"kotlin-invoice","version":"1.0.0","bom-ref":"pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar"}}}""",
        )
        val frontend = parse(frontendBom)

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")

        assertThat(main.get("components")).isNotEmpty()
        assertThat(main.get("dependencies")).isNotEmpty()
    }

    @Test
    fun `merge leaves an unrelated dependency ref untouched`() {
        val frontendWithExtra = parse(frontendBom)
        val extra = mapper.createObjectNode()
        extra.put("ref", "some-unrelated-ref")
        (frontendWithExtra.get("dependencies") as com.fasterxml.jackson.databind.node.ArrayNode).add(extra)
        val main = parse(mainBomWithPrimary())

        NpmSbomMerger.merge(main, frontendWithExtra, mapper, "kotlin-invoice-frontend", "1.0.0")

        val unrelated = main.get("dependencies").first { it.get("ref").asText() == "some-unrelated-ref" }
        assertThat(unrelated).isNotNull()
    }

    @Test
    fun `merge does not fail when the main document has no primary component to attach the bundle to`() {
        val main = parse("""{"metadata":{},"components":[],"dependencies":[]}""")
        val frontend = parse(frontendBom)

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")

        assertThat(main.get("components")).isNotEmpty()
    }

    @Test
    fun `merge still appends the frontend dependency graph when the main document's primary dependency entry is missing`() {
        val main = parse(
            """{"metadata":{"component":{"name":"kotlin-invoice","version":"1.0.0","bom-ref":"pkg:maven/x@1.0?type=jar"}},"components":[],"dependencies":[]}""",
        )
        val frontend = parse(frontendBom)

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")

        val bundleDependency = main.get("dependencies").firstOrNull { it.get("ref").asText() == "kotlin-invoice-frontend" }
        assertThat(bundleDependency).isNotNull()
    }

    // package.json's own "dependencies" (runtime, bundled and executed in the browser) versus
    // "devDependencies" (build tooling — vite, typescript, babel, …): the `--omit dev` flag on the
    // cyclonedx-npm invocation (see kotlin-invoice's pom) is what actually excludes the latter
    // upstream, before this merger ever sees the file — this test documents and locks in that the
    // merge step itself is a faithful pass-through and never reintroduces anything that was
    // already filtered out, using a frontend document shaped like the real one `--omit dev`
    // produces for kotlin-invoice's actual twelve runtime dependencies.
    @Test
    fun `merge carries every npm runtime dependency through and introduces no devDependency of its own`() {
        val runtimeDependencyNames = listOf(
            "aura", "common-frontend", "react-components", "vaadin-development-mode-detector",
            "vaadin-lumo-styles", "vaadin-themable-mixin", "vaadin-usage-statistics",
            "date-fns", "lit", "react", "react-dom", "react-router",
        )
        val knownDevDependencyNames = listOf("vite", "typescript", "babel", "rollup", "magic-string", "transform-ast")

        val components = runtimeDependencyNames.joinToString(",") { name ->
            """{"type":"library","name":"$name","version":"1.0.0","bom-ref":"no-name|$name@1.0.0","purl":"pkg:npm/$name@1.0.0"}"""
        }
        val realisticFrontendBom = parse(
            """{"metadata":{"component":{"name":"no-name","bom-ref":"no-name"}},"components":[$components],"dependencies":[{"ref":"no-name"}]}""",
        )
        val main = parse(mainBomWithPrimary())

        NpmSbomMerger.merge(main, realisticFrontendBom, mapper, "kotlin-invoice-frontend", "1.0.0")

        val mergedNames = main.get("components").map { it.get("name").asText() }.toSet()
        assertThat(mergedNames).containsAll(runtimeDependencyNames)
        assertThat(mergedNames).noneMatch { name -> knownDevDependencyNames.any { it == name } }
    }
}
