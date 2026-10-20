package de.littletrotter.sbom

import com.fasterxml.jackson.databind.JsonNode
import org.cyclonedx.Version
import org.cyclonedx.parsers.JsonParser
import java.io.File

/**
 * Validates a document against the official CycloneDX 1.6 JSON schema (as bundled by
 * cyclonedx-core-java, the reference parser cyclonedx-maven-plugin itself is built on).
 *
 * § 4 of BSI TR-03183-2 does not merely ask for "a JSON file containing the required fields" — it
 * requires a *valid* SBOM according to one of the named format specifications, in one of the named
 * versions. [BsiFieldEnricher] and [NpmSbomMerger] mutate a document produced by someone else by
 * hand (inserting `manufacturer`, `properties`, `compositions` nodes); a wrong shape there would
 * make every field-catalogue assertion still pass while the file quietly stopped being a
 * CycloneDX document at all. This is the check that would catch that.
 */
object CycloneDxSchemaValidator {

    /**
     * Returns the schema validation error messages for [file] — empty if the document is a valid
     * CycloneDX 1.6 document. Each message is the schema's own text, identifying the offending
     * node.
     */
    fun validate(file: File): List<String> = JsonParser().validate(file, Version.VERSION_16).map { errorMessage(it) }

    /** Same as [validate], for a document already parsed into a tree (no file round-trip needed). */
    fun validate(root: JsonNode): List<String> = JsonParser().validate(root, Version.VERSION_16).map { errorMessage(it) }

    private fun errorMessage(error: Exception): String = error.message ?: error.toString()
}
