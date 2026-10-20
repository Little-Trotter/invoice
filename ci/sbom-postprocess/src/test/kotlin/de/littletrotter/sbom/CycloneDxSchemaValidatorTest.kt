package de.littletrotter.sbom

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

class CycloneDxSchemaValidatorTest {

    private val mapper = ObjectMapper()

    @Test
    fun `validate(File) delegates to the same schema check as validate(JsonNode)`() {
        val bomFile = Files.createTempFile("bom", ".json").toFile()
        bomFile.writeText("""{"bomFormat":"CycloneDX","specVersion":"1.6","version":1}""")

        assertThat(CycloneDxSchemaValidator.validate(bomFile)).isEmpty()
    }

    @Test
    fun `validate(File) reports an error message for an invalid document`() {
        val bomFile = Files.createTempFile("bom", ".json").toFile()
        bomFile.writeText("""{"notABom":true}""")

        assertThat(CycloneDxSchemaValidator.validate(bomFile)).isNotEmpty()
    }

    @Test
    fun `a minimal valid CycloneDX 1_6 document has no validation errors`() {
        val root = mapper.readTree(
            """{"bomFormat":"CycloneDX","specVersion":"1.6","version":1}""",
        ) as ObjectNode

        assertThat(CycloneDxSchemaValidator.validate(root)).isEmpty()
    }

    @Test
    fun `a document that is not CycloneDX at all fails validation with a message`() {
        val root = mapper.readTree("""{"notABom":true}""") as ObjectNode

        val errors = CycloneDxSchemaValidator.validate(root)

        assertThat(errors).isNotEmpty()
    }

    // The exact check the reviewer asked for: BsiFieldEnricher hand-edits the JSON tree (inserting
    // manufacturer/properties/compositions nodes) — this proves that output is still a valid
    // CycloneDX 1.6 document, not merely one that happens to carry the right field names.
    @Test
    fun `BsiFieldEnricher's output for a small hand-built document is still schema-valid`() {
        val root = mapper.readTree(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": {
                "timestamp": "2026-09-07T00:00:00Z",
                "component": {
                  "type": "library",
                  "bom-ref": "pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar",
                  "name": "kotlin-invoice",
                  "version": "1.0.0",
                  "purl": "pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar"
                }
              },
              "components": [
                {
                  "type": "library",
                  "bom-ref": "pkg:maven/io.quarkus/quarkus-kotlin@3.39.1?type=jar",
                  "name": "quarkus-kotlin",
                  "version": "3.39.1",
                  "purl": "pkg:maven/io.quarkus/quarkus-kotlin@3.39.1?type=jar",
                  "externalReferences": [
                    {"type": "website", "url": "https://github.com/quarkusio/quarkus"}
                  ]
                }
              ],
              "dependencies": [
                {
                  "ref": "pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar",
                  "dependsOn": ["pkg:maven/io.quarkus/quarkus-kotlin@3.39.1?type=jar"]
                },
                {"ref": "pkg:maven/io.quarkus/quarkus-kotlin@3.39.1?type=jar"}
              ]
            }
            """.trimIndent(),
        ) as ObjectNode

        BsiFieldEnricher.enrich(root, mapper, "https://github.com/little-trotter/invoice")

        val errors = CycloneDxSchemaValidator.validate(root)
        assertThat(errors).describedAs("schema errors: %s", errors).isEmpty()
    }

    @Test
    fun `NpmSbomMerger's output for a small hand-built pair of documents is still schema-valid`() {
        val main = mapper.readTree(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": {
                "component": {
                  "type": "library",
                  "bom-ref": "pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar",
                  "name": "kotlin-invoice",
                  "version": "1.0.0"
                }
              },
              "components": [],
              "dependencies": [
                {"ref": "pkg:maven/de.littletrotter/kotlin-invoice@1.0.0?type=jar"}
              ]
            }
            """.trimIndent(),
        ) as ObjectNode
        val frontend = mapper.readTree(
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "version": 1,
              "metadata": {"component": {"type": "application", "bom-ref": "no-name", "name": "no-name"}},
              "components": [
                {"type": "library", "bom-ref": "no-name|react@19.2.8", "name": "react", "version": "19.2.8"}
              ],
              "dependencies": [
                {"ref": "no-name", "dependsOn": ["no-name|react@19.2.8"]},
                {"ref": "no-name|react@19.2.8"}
              ]
            }
            """.trimIndent(),
        ) as ObjectNode

        NpmSbomMerger.merge(main, frontend, mapper, "kotlin-invoice-frontend", "1.0.0")
        BsiFieldEnricher.enrich(main, mapper, "https://github.com/little-trotter/invoice")

        val errors = CycloneDxSchemaValidator.validate(main)
        assertThat(errors).describedAs("schema errors: %s", errors).isEmpty()
    }
}
