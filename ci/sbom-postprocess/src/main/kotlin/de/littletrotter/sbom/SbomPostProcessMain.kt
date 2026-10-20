package de.littletrotter.sbom

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

private const val MERGE_ARGS_COUNT = 5
private const val ARG_BOM_FILE = 0
private const val ARG_CREATOR_URL = 1
private const val ARG_FRONTEND_BOM_FILE = 2
private const val ARG_BUNDLE_NAME = 3
private const val ARG_BUNDLE_VERSION = 4

// Set by the pom from project.build.outputTimestamp; absent means the plugin's own timestamp stands.
private const val TIMESTAMP_PROPERTY = "sbom.timestamp"

/**
 * CLI entry point, invoked via exec-maven-plugin's `java` goal (as a PLUGIN dependency of
 * kotlin-invoice/kotlin-xpdf, never a project dependency of either — see this module's own pom
 * for why that keeps this tool off both modules' own CycloneDX scans).
 *
 * Usage:
 *   <bom-file> <sbom-creator-url>
 *   <bom-file> <sbom-creator-url> <frontend-bom-file> <bundle-name> <bundle-version>
 *
 * The first form runs the BSI TR-03183-2 field enrichment (S3) alone. The second form first
 * merges the given npm-generated frontend document into <bom-file> (S1, kotlin-invoice only) and
 * then runs the same enrichment over the merged result, so the merged-in npm components get the
 * same treatment (component creator, dependency-completeness) as everything else.
 */
fun main(args: Array<String>) {
    require(args.size == 2 || args.size == MERGE_ARGS_COUNT) {
        "usage: <bom-file> <sbom-creator-url> [<frontend-bom-file> <bundle-name> <bundle-version>]"
    }

    val baseDir = Path.of("").toAbsolutePath().normalize()
    val bomFile = resolveWithinBaseDir(baseDir, args[ARG_BOM_FILE])
    val creatorUrl = args[ARG_CREATOR_URL]
    val mapper = ObjectMapper()

    val root = readObjectNode(mapper, bomFile)

    if (args.size == MERGE_ARGS_COUNT) {
        val frontendBomFile = resolveWithinBaseDir(baseDir, args[ARG_FRONTEND_BOM_FILE])
        val frontendRoot = readObjectNode(mapper, frontendBomFile)
        NpmSbomMerger.merge(root, frontendRoot, mapper, args[ARG_BUNDLE_NAME], args[ARG_BUNDLE_VERSION])
    }

    BsiFieldEnricher.enrich(root, mapper, creatorUrl, System.getProperty(TIMESTAMP_PROPERTY))

    Files.newOutputStream(bomFile).use { output ->
        mapper.writerWithDefaultPrettyPrinter().writeValue(output, root)
    }
}

/**
 * Resolves a CLI path argument against [baseDir] and rejects anything that escapes it after
 * normalisation. `<bom-file>`/`<frontend-bom-file>` come straight from the Maven build
 * (`exec-maven-plugin`'s `java` goal), so static taint analysis correctly treats them as
 * uncontrolled input; anchoring them to the process's working directory before they ever reach
 * `Files.newInputStream`/`Files.newOutputStream` is the sanitiser that analysis looks for, and it
 * fails loudly (an `IllegalArgumentException` with the offending argument) instead of silently
 * reading or writing outside the intended tree.
 */
private fun resolveWithinBaseDir(baseDir: Path, rawArgument: String): Path {
    val resolved = baseDir.resolve(rawArgument).normalize()
    require(resolved.startsWith(baseDir)) {
        "path argument escapes the working directory ($baseDir): $rawArgument"
    }
    return resolved
}

private fun readObjectNode(
    mapper: ObjectMapper,
    path: Path,
): ObjectNode {
    val node = Files.newInputStream(path).use { input -> mapper.readTree(input) }
    require(node is ObjectNode) { "expected a JSON object at $path" }
    return node
}
