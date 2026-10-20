package de.littletrotter.sbom

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Fills in the CycloneDX 1.6 fields BSI TR-03183-2 requires (§ 5.2) that cyclonedx-maven-plugin
 * does not itself emit, using the exact key names and JSON shapes of the mapping in § 8.2:
 *
 *  - Creator of the SBOM (§ 5.2.1) → `metadata.manufacturer.url`
 *  - Timestamp of the SBOM (§ 5.2.1) → `metadata.timestamp`; cyclonedx-maven-plugin drops it when
 *    `project.build.outputTimestamp` makes the build reproducible, so the same fixed instant is
 *    written back here and the document stays byte-stable across rebuilds
 *  - Component creator (§ 5.2.2) → `manufacturer.url` on the primary component and on each entry
 *    of `components`
 *  - Filename / executable / archive / structured (§ 5.2.2, § 8.1.4-8.1.7) →
 *    `bsi:component:filename` / `bsi:component:executable` / `bsi:component:archive` /
 *    `bsi:component:structured` properties (the BSI CycloneDX property taxonomy keys)
 *  - Completeness of the dependency enumeration (§ 5.2.2) → a `compositions` entry with
 *    `aggregate: "complete"`
 *
 * Every value is derived from data cyclonedx-maven-plugin already recorded (the component's own
 * `purl` and `externalReferences`) — nothing is invented. Where a value is genuinely not
 * determinable this way, the corresponding field is left absent, as § 3.2.1 explicitly permits.
 */
object BsiFieldEnricher {

    private const val PROP_FILENAME = "bsi:component:filename"
    private const val PROP_EXECUTABLE = "bsi:component:executable"
    private const val PROP_ARCHIVE = "bsi:component:archive"
    private const val PROP_STRUCTURED = "bsi:component:structured"

    private const val VALUE_EXECUTABLE = "executable"
    private const val VALUE_ARCHIVE = "archive"
    private const val VALUE_STRUCTURED = "structured"

    private const val EXT_REF_TYPE_WEBSITE = "website"
    private const val PURL_MAVEN_JAR_TYPE = "jar"

    /**
     * Mutates [root] in place. [sbomCreatorUrl] is used both as the creator of the SBOM itself
     * (§ 5.2.1) and, since the primary component is the very product that produced this document,
     * as that primary component's own creator (§ 5.2.2) — the two are the same entity here.
     */
    fun enrich(
        root: ObjectNode,
        mapper: ObjectMapper,
        sbomCreatorUrl: String,
        timestamp: String? = null,
    ) {
        val metadata = root.get("metadata") as? ObjectNode
        if (metadata != null) {
            if (!timestamp.isNullOrBlank() && metadata.get("timestamp")?.asText().isNullOrBlank()) {
                metadata.put("timestamp", timestamp)
            }
            setManufacturerUrl(metadata, mapper, sbomCreatorUrl)

            val primaryComponent = metadata.get("component") as? ObjectNode
            if (primaryComponent != null) {
                setManufacturerUrl(primaryComponent, mapper, sbomCreatorUrl)
                annotateComponent(primaryComponent, mapper)
            }
        }

        val components = root.get("components") as? ArrayNode
        components?.forEach { node ->
            val component = node as? ObjectNode ?: return@forEach
            deriveCreatorUrlFromWebsiteReference(component)?.let { url ->
                setManufacturerUrl(component, mapper, url)
            }
            annotateComponent(component, mapper)
        }

        markDependencyCompleteness(root, mapper)
    }

    private fun setManufacturerUrl(
        node: ObjectNode,
        mapper: ObjectMapper,
        url: String,
    ) {
        val manufacturer = mapper.createObjectNode()
        val urls = mapper.createArrayNode()
        urls.add(url)
        manufacturer.set<ArrayNode>("url", urls)
        node.set<ObjectNode>("manufacturer", manufacturer)
    }

    private fun deriveCreatorUrlFromWebsiteReference(component: ObjectNode): String? {
        val refs = component.get("externalReferences") as? ArrayNode ?: return null
        for (ref in refs) {
            if (ref.get("type")?.asText() == EXT_REF_TYPE_WEBSITE) {
                val url = ref.get("url")?.asText()
                if (!url.isNullOrBlank()) return url
            }
        }
        return null
    }

    private fun annotateComponent(
        component: ObjectNode,
        mapper: ObjectMapper,
    ) {
        deriveFilename(component)?.let { filename ->
            upsertProperty(component, mapper, PROP_FILENAME, filename)
        }
        if (isMavenJar(component)) {
            upsertProperty(component, mapper, PROP_EXECUTABLE, VALUE_EXECUTABLE)
            upsertProperty(component, mapper, PROP_ARCHIVE, VALUE_ARCHIVE)
            upsertProperty(component, mapper, PROP_STRUCTURED, VALUE_STRUCTURED)
        }
    }

    /**
     * The actual filename of the component (§ 5.2.2), derived from the Maven repository layout
     * convention the purl already encodes: `<artifactId>-<version>[-<classifier>].<type>`. This is
     * the deterministic rule that produced the real file, not a guess — but it needs a `purl` to
     * read the type/classifier from, so components without one (e.g. a reference that is not
     * resolvable to a concrete Maven artifact) are left without this property.
     */
    private fun deriveFilename(component: ObjectNode): String? {
        val purl = component.get("purl")?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val name = component.get("name")?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val version = component.get("version")?.asText()?.takeIf { it.isNotBlank() } ?: return null

        val params = purlQueryParams(purl)
        val type = params["type"]?.takeIf { it.isNotBlank() } ?: PURL_MAVEN_JAR_TYPE
        val classifier = params["classifier"]?.takeIf { it.isNotBlank() }
        val classifierSuffix = if (classifier == null) "" else "-$classifier"
        return "$name-$version$classifierSuffix.$type"
    }

    /**
     * Every Maven jar is executable (interpreted/compiled code a JVM runs, § 8.1.4), an archive
     * (a zip-format combination of multiple class files, § 8.1.5) and structured (its manifest and
     * package layout let the original contents be determined afterwards, § 8.1.6) — this applies
     * uniformly, it is not a per-component judgement call. Only Maven-resolved `pkg:maven/...`
     * components with a jar type (or no type, which purl defaults to "jar") qualify; anything else
     * (no purl at all) is left without these properties rather than guessed at.
     */
    private fun isMavenJar(component: ObjectNode): Boolean {
        val purl = component.get("purl")?.asText()?.takeIf { it.isNotBlank() } ?: return false
        if (!purl.startsWith("pkg:maven/")) return false
        val type = purlQueryParams(purl)["type"]
        return type == null || type.equals(PURL_MAVEN_JAR_TYPE, ignoreCase = true)
    }

    private fun purlQueryParams(purl: String): Map<String, String> {
        val query = purl.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                key to value
            }
    }

    private fun upsertProperty(
        component: ObjectNode,
        mapper: ObjectMapper,
        name: String,
        value: String,
    ) {
        val properties = component.get("properties") as? ArrayNode
            ?: mapper.createArrayNode().also { component.set<ArrayNode>("properties", it) }

        val existing = properties.firstOrNull { it.get("name")?.asText() == name } as? ObjectNode
        if (existing != null) {
            existing.put("value", value)
        } else {
            val propertyNode = mapper.createObjectNode()
            propertyNode.put("name", name)
            propertyNode.put("value", value)
            properties.add(propertyNode)
        }
    }

    /**
     * § 5.2.2 requires that "the completeness of this enumeration MUST be clearly indicated" for
     * every component's dependency list. cyclonedx-maven-plugin's dependency resolution over the
     * compile+runtime scope is exhaustive by construction (Maven's own dependency graph is
     * complete for the resolved scopes), so every ref already present in the top-level
     * `dependencies` array is marked "complete" via a single `compositions` entry — the norm's
     * mapping (§ 8.2) for this field.
     */
    private fun markDependencyCompleteness(
        root: ObjectNode,
        mapper: ObjectMapper,
    ) {
        val dependencies = root.get("dependencies") as? ArrayNode ?: return
        val refs = mapper.createArrayNode()
        val seen = linkedSetOf<String>()
        dependencies.forEach { dependency ->
            val ref = dependency.get("ref")?.asText()
            if (!ref.isNullOrBlank() && seen.add(ref)) {
                refs.add(ref)
            }
        }
        if (refs.size() == 0) return

        val composition = mapper.createObjectNode()
        composition.put("aggregate", "complete")
        composition.set<ArrayNode>("dependencies", refs)

        val compositions = mapper.createArrayNode()
        compositions.add(composition)
        root.set<ArrayNode>("compositions", compositions)
    }
}
