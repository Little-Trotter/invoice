package de.littletrotter.sbom

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * BSI TR-03183-2 § 4 requires a *valid* SBOM according to one of the named format specifications
 * (CycloneDX 1.6 or higher) — not merely a JSON file that happens to carry the right field names.
 * [BsiFieldEnricher] inserts `manufacturer`/`properties`/`compositions` nodes into a document
 * produced by cyclonedx-maven-plugin by hand; [SbomFieldCatalogueIT] proves the required fields
 * are present, but a wrong node shape there would make every one of its assertions still pass
 * while the file stopped being a CycloneDX document at all. This test is the one that would catch
 * that: it validates the actual file `prepare-package` shipped — after enrichment, not a fixture
 * built here — against the real CycloneDX 1.6 JSON schema.
 *
 * No `@QuarkusTest` — see [SbomFieldCatalogueIT]'s KDoc: this module carries no quarkus-junit5
 * dependency and this test needs neither CDI nor a running app.
 */
class SbomSchemaValidityIT {

    @Test
    fun `the delivered, post-processed bom document is a valid CycloneDX 1_6 document`() {
        val bomFile = Paths.get("target", "classes", "bom.json").toFile()
        if (!bomFile.exists()) {
            fail<Unit>("target/classes/bom.json does not exist — prepare-package's sbom-enrich execution did not run or did not produce it")
        }

        val errors = CycloneDxSchemaValidator.validate(bomFile)

        assertTrue(errors.isEmpty(), "target/classes/bom.json is not a valid CycloneDX 1.6 document:\n${errors.joinToString("\n")}")
    }
}
