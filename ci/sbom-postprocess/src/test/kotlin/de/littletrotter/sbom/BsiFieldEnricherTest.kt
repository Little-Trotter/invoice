package de.littletrotter.sbom

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BsiFieldEnricherTest {

    private val mapper = ObjectMapper()

    private fun parse(json: String): ObjectNode = mapper.readTree(json) as ObjectNode

    @Test
    fun `enrich sets the SBOM creator as a manufacturer url on metadata`() {
        val root = parse("""{"metadata":{}}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.at("/metadata/manufacturer/url/0").asText())
            .isEqualTo("https://example.org/project")
    }

    @Test
    fun `enrich does nothing and does not throw when metadata is absent`() {
        val root = parse("""{}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.has("metadata")).isFalse()
    }

    @Test
    fun `enrich sets the primary component creator to the same SBOM creator url`() {
        val root = parse(
            """{"metadata":{"component":{"name":"kotlin-invoice","version":"1.0.0","purl":"pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar"}}}""",
        )

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.at("/metadata/component/manufacturer/url/0").asText())
            .isEqualTo("https://example.org/project")
    }

    @Test
    fun `enrich handles metadata without a component gracefully`() {
        val root = parse("""{"metadata":{"timestamp":"2026-01-01T00:00:00Z"}}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.at("/metadata/manufacturer/url/0").asText()).isEqualTo("https://example.org/project")
        assertThat(root.at("/metadata/component").isMissingNode).isTrue()
    }

    @Test
    fun `enrich writes the reproducible timestamp when the plugin omitted it`() {
        val root = parse("""{"metadata":{}}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project", "2026-01-01T00:00:00Z")

        assertThat(root.at("/metadata/timestamp").asText()).isEqualTo("2026-01-01T00:00:00Z")
    }

    @Test
    fun `enrich keeps an existing timestamp`() {
        val root = parse("""{"metadata":{"timestamp":"2025-05-05T00:00:00Z"}}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project", "2026-01-01T00:00:00Z")

        assertThat(root.at("/metadata/timestamp").asText()).isEqualTo("2025-05-05T00:00:00Z")
    }

    @Test
    fun `enrich annotates the primary component as a maven jar`() {
        val root = parse(
            """{"metadata":{"component":{"name":"kotlin-invoice","version":"1.0.0","purl":"pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar"}}}""",
        )

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val properties = root.at("/metadata/component/properties").associate { it.get("name").asText() to it.get("value").asText() }
        assertThat(properties["bsi:component:filename"]).isEqualTo("kotlin-invoice-1.0.0.jar")
        assertThat(properties["bsi:component:executable"]).isEqualTo("executable")
        assertThat(properties["bsi:component:archive"]).isEqualTo("archive")
        assertThat(properties["bsi:component:structured"]).isEqualTo("structured")
    }

    @Test
    fun `enrich derives component creator from the website external reference when present`() {
        val root = parse(
            """
            {
              "components": [
                {"name":"quarkus-kotlin","version":"3.39.1","purl":"pkg:maven/io.quarkus/quarkus-kotlin@3.39.1?type=jar",
                 "externalReferences":[{"type":"distribution-intake","url":"https://repo.maven.apache.org/maven2"},
                                        {"type":"website","url":"https://github.com/quarkusio/quarkus"}]}
              ]
            }
            """.trimIndent(),
        )

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.at("/components/0/manufacturer/url/0").asText())
            .isEqualTo("https://github.com/quarkusio/quarkus")
    }

    @Test
    fun `enrich leaves component creator absent when no website reference exists`() {
        val root = parse(
            """{"components":[{"name":"kotlin-shared","version":"1.0.0","purl":"pkg:maven/de.littletrotter/kotlin-shared@1.0.0?type=jar"}]}""",
        )

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.at("/components/0/manufacturer").isMissingNode).isTrue()
    }

    @Test
    fun `enrich leaves component creator absent when externalReferences carries no website entry`() {
        val root = parse(
            """
            {"components":[{"name":"lib","version":"1.0.0","purl":"pkg:maven/g/lib@1.0.0?type=jar",
             "externalReferences":[{"type":"vcs","url":"https://example.org/repo"}]}]}
            """.trimIndent(),
        )

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.at("/components/0/manufacturer").isMissingNode).isTrue()
    }

    @Test
    fun `enrich leaves component creator absent when an external reference carries no type at all`() {
        val root = parse(
            """{"components":[{"name":"lib","version":"1.0.0","purl":"pkg:maven/g/lib@1.0.0?type=jar",
             "externalReferences":[{"url":"https://example.org/repo"}]}]}""",
        )

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.at("/components/0/manufacturer").isMissingNode).isTrue()
    }

    @Test
    fun `enrich leaves component creator absent when the website reference carries no url at all`() {
        val root = parse(
            """{"components":[{"name":"lib","version":"1.0.0","purl":"pkg:maven/g/lib@1.0.0?type=jar",
             "externalReferences":[{"type":"website"}]}]}""",
        )

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.at("/components/0/manufacturer").isMissingNode).isTrue()
    }

    @Test
    fun `enrich skips a blank website url and falls through to a later qualifying reference`() {
        val root = parse(
            """{"components":[{"name":"lib","version":"1.0.0","purl":"pkg:maven/g/lib@1.0.0?type=jar",
             "externalReferences":[{"type":"website","url":""},{"type":"website","url":"https://example.org/real"}]}]}""",
        )

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.at("/components/0/manufacturer/url/0").asText()).isEqualTo("https://example.org/real")
    }

    @Test
    fun `enrich derives a filename with a classifier when the purl carries one`() {
        val root = parse(
            """{"components":[{"name":"lib","version":"2.0.0","purl":"pkg:maven/g/lib@2.0.0?type=jar&classifier=sources"}]}""",
        )

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val filename = root.at("/components/0/properties").first { it.get("name").asText() == "bsi:component:filename" }
        assertThat(filename.get("value").asText()).isEqualTo("lib-2.0.0-sources.jar")
    }

    @Test
    fun `enrich defaults the filename extension to jar when the purl carries no type`() {
        val root = parse("""{"components":[{"name":"lib","version":"2.0.0","purl":"pkg:maven/g/lib@2.0.0"}]}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val filename = root.at("/components/0/properties").first { it.get("name").asText() == "bsi:component:filename" }
        assertThat(filename.get("value").asText()).isEqualTo("lib-2.0.0.jar")
    }

    @Test
    fun `enrich leaves filename absent when the component carries no purl`() {
        val root = parse("""{"components":[{"name":"lib","version":"2.0.0"}]}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.at("/components/0/properties").isMissingNode).isTrue()
    }

    @Test
    fun `enrich leaves filename absent when name or version is missing despite a purl`() {
        val root = parse("""{"components":[{"purl":"pkg:maven/g/lib@2.0.0?type=jar"}]}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val properties = root.at("/components/0/properties")
        val hasFilename = !properties.isMissingNode && properties.any { it.get("name").asText() == "bsi:component:filename" }
        assertThat(hasFilename).isFalse()
    }

    @Test
    fun `enrich leaves filename and executable properties absent when the purl is present but blank`() {
        val root = parse("""{"components":[{"name":"lib","version":"1.0.0","purl":""}]}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.at("/components/0/properties").isMissingNode).isTrue()
    }

    @Test
    fun `enrich defaults the filename extension to jar when the type parameter is present but blank`() {
        val root = parse("""{"components":[{"name":"lib","version":"2.0.0","purl":"pkg:maven/g/lib@2.0.0?type="}]}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val filename = root.at("/components/0/properties").first { it.get("name").asText() == "bsi:component:filename" }
        assertThat(filename.get("value").asText()).isEqualTo("lib-2.0.0.jar")
    }

    @Test
    fun `enrich ignores a blank classifier parameter`() {
        val root = parse("""{"components":[{"name":"lib","version":"2.0.0","purl":"pkg:maven/g/lib@2.0.0?type=jar&classifier="}]}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val filename = root.at("/components/0/properties").first { it.get("name").asText() == "bsi:component:filename" }
        assertThat(filename.get("value").asText()).isEqualTo("lib-2.0.0.jar")
    }

    @Test
    fun `enrich does not mark a maven component with a non-jar type as executable, archive or structured`() {
        val root = parse("""{"components":[{"name":"lib","version":"1.0.0","purl":"pkg:maven/g/lib@1.0.0?type=pom"}]}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val names = root.at("/components/0/properties").map { it.get("name").asText() }.toSet()
        assertThat(names).doesNotContain("bsi:component:executable", "bsi:component:archive", "bsi:component:structured")
    }

    @Test
    fun `enrich matches the maven jar type case-insensitively`() {
        val root = parse("""{"components":[{"name":"lib","version":"1.0.0","purl":"pkg:maven/g/lib@1.0.0?type=JAR"}]}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val properties = root.at("/components/0/properties").associate { it.get("name").asText() to it.get("value").asText() }
        assertThat(properties["bsi:component:executable"]).isEqualTo("executable")
    }

    @Test
    fun `enrich does not mark executable, archive or structured for a non-maven component`() {
        val root = parse(
            """{"components":[{"name":"aura","version":"25.2.7","purl":"pkg:npm/%40vaadin/aura@25.2.7"}]}""",
        )

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val properties = root.at("/components/0/properties")
        val names = if (properties.isMissingNode) emptySet() else properties.map { it.get("name").asText() }.toSet()
        assertThat(names).doesNotContain("bsi:component:executable", "bsi:component:archive", "bsi:component:structured")
    }

    @Test
    fun `enrich does not mark executable, archive or structured when the purl has no maven prefix at all`() {
        val root = parse("""{"components":[{"name":"lib","version":"1.0.0","purl":"not-a-purl"}]}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val properties = root.at("/components/0/properties")
        val names = properties.map { it.get("name").asText() }.toSet()
        assertThat(names).doesNotContain("bsi:component:executable")
    }

    @Test
    fun `enrich skips a components array entry that is not a JSON object and still processes the rest`() {
        val root = parse("""{"components":["not-an-object", {"name":"lib","version":"1.0.0","purl":"pkg:maven/g/lib@1.0.0?type=jar"}]}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val filename = root.at("/components/1/properties").first { it.get("name").asText() == "bsi:component:filename" }
        assertThat(filename.get("value").asText()).isEqualTo("lib-1.0.0.jar")
    }

    @Test
    fun `enrich appends a new bsi property alongside an unrelated pre-existing one`() {
        val root = parse(
            """{"components":[{"name":"lib","version":"1.0.0","purl":"pkg:maven/g/lib@1.0.0?type=jar",
             "properties":[{"name":"cdx:some:other","value":"x"}]}]}""",
        )

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val properties = root.at("/components/0/properties").associate { it.get("name").asText() to it.get("value").asText() }
        assertThat(properties["cdx:some:other"]).isEqualTo("x")
        assertThat(properties["bsi:component:filename"]).isEqualTo("lib-1.0.0.jar")
    }

    @Test
    fun `enrich is idempotent — running it twice does not duplicate bsi properties`() {
        val root = parse("""{"components":[{"name":"lib","version":"1.0.0","purl":"pkg:maven/g/lib@1.0.0?type=jar"}]}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")
        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        val properties = root.at("/components/0/properties")
        val filenameEntries = properties.filter { it.get("name").asText() == "bsi:component:filename" }
        assertThat(filenameEntries).hasSize(1)
    }

    @Test
    fun `enrich marks the recorded dependency graph complete with every distinct ref`() {
        val root = parse(
            """
            {
              "dependencies": [
                {"ref":"a","dependsOn":["b"]},
                {"ref":"b"},
                {"ref":"a","dependsOn":["b"]}
              ]
            }
            """.trimIndent(),
        )

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.at("/compositions/0/aggregate").asText()).isEqualTo("complete")
        val refs = root.at("/compositions/0/dependencies").map { it.asText() }
        assertThat(refs).containsExactly("a", "b")
    }

    @Test
    fun `enrich adds no compositions entry when there is no dependencies array`() {
        val root = parse("""{}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.has("compositions")).isFalse()
    }

    @Test
    fun `enrich adds no compositions entry when every dependency ref is blank or absent`() {
        val root = parse("""{"dependencies":[{"dependsOn":["x"]},{"ref":""}]}""")

        BsiFieldEnricher.enrich(root, mapper, "https://example.org/project")

        assertThat(root.has("compositions")).isFalse()
    }
}
