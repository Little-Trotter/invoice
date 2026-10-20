package de.littletrotter.sbom

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Merges the CycloneDX document `@cyclonedx/cyclonedx-npm` produced for the delivered Vaadin
 * frontend bundle into the application's own (Java) CycloneDX document.
 *
 * SBOM spec B1/S1: the frontend bundle that ships inside the jar and runs in the user's browser is
 * part of the scope of delivery (§ 8.1.11) just as much as the JVM dependency graph, so it belongs
 * in the same document, not a separate one nobody looks at. The npm document's own primary
 * component (named after `package.json`'s "name" field, which this repo leaves at its scaffolded
 * placeholder "no-name") is renamed to bundleName/bundleVersion — supplied by the caller
 * instead of read from `package.json` — and wired in as a dependency of the Java primary
 * component, with every npm component and dependency edge renamed to match.
 */
object NpmSbomMerger {

    private const val REF_SEPARATOR = "|"

    private const val BOM_REF = "bom-ref"
    private const val COMPONENTS = "components"
    private const val DEPENDENCIES = "dependencies"
    private const val REF_FIELD = "ref"
    private const val DEPENDS_ON = "dependsOn"

    fun merge(
        mainRoot: ObjectNode,
        frontendRoot: ObjectNode,
        mapper: ObjectMapper,
        bundleName: String,
        bundleVersion: String,
    ) {
        val frontendPrimary = frontendRoot.get("metadata")?.get("component") as? ObjectNode ?: return
        val oldRootRef = frontendPrimary.get(BOM_REF)?.asText() ?: return
        // Bare name, no version suffix — matches the "|"-namespacing convention cyclonedx-npm's
        // own root ref already used (e.g. "no-name|react@19.2.8"): every child's rewritten ref
        // keeps using this same prefix, so it has to stay stable and simple.
        val newRootRef = bundleName

        val renamedPrimary = renameBundleNode(frontendPrimary, bundleName, bundleVersion, newRootRef)

        val mainComponents = mainRoot.get(COMPONENTS) as? ArrayNode
            ?: mapper.createArrayNode().also { mainRoot.set<ArrayNode>(COMPONENTS, it) }
        mainComponents.add(renamedPrimary)

        val frontendComponents = frontendRoot.get(COMPONENTS) as? ArrayNode
        frontendComponents?.forEach { node ->
            val component = node as? ObjectNode ?: return@forEach
            rewriteRef(component, BOM_REF, oldRootRef, newRootRef)
            mainComponents.add(component)
        }

        val mainDependencies = mainRoot.get(DEPENDENCIES) as? ArrayNode
            ?: mapper.createArrayNode().also { mainRoot.set<ArrayNode>(DEPENDENCIES, it) }
        val frontendDependencies = frontendRoot.get(DEPENDENCIES) as? ArrayNode
        frontendDependencies?.forEach { node ->
            val dependency = node as? ObjectNode ?: return@forEach
            rewriteRef(dependency, REF_FIELD, oldRootRef, newRootRef)
            rewriteDependsOn(dependency, oldRootRef, newRootRef, mapper)
            mainDependencies.add(dependency)
        }

        addBundleAsDependencyOfPrimary(mainRoot, mainDependencies, mapper, newRootRef)
    }

    private fun renameBundleNode(
        frontendPrimary: ObjectNode,
        bundleName: String,
        bundleVersion: String,
        newRootRef: String,
    ): ObjectNode {
        val renamed = frontendPrimary.deepCopy()
        renamed.put("name", bundleName)
        renamed.put("version", bundleVersion)
        renamed.put(BOM_REF, newRootRef)
        // Synthetic node, not a resolvable published package — a fabricated purl would claim a
        // precision that does not exist (§ 3.2.1: omit what the composition makes unavailable).
        renamed.remove("purl")
        renamed.remove("properties")
        return renamed
    }

    private fun rewriteRef(
        node: ObjectNode,
        fieldName: String,
        oldRootRef: String,
        newRootRef: String,
    ) {
        val current = node.get(fieldName)?.asText() ?: return
        node.put(fieldName, rewrite(current, oldRootRef, newRootRef))
    }

    private fun rewriteDependsOn(
        dependency: ObjectNode,
        oldRootRef: String,
        newRootRef: String,
        mapper: ObjectMapper,
    ) {
        val dependsOn = dependency.get(DEPENDS_ON) as? ArrayNode ?: return
        val rewritten = mapper.createArrayNode()
        dependsOn.forEach { entry ->
            rewritten.add(rewrite(entry.asText(), oldRootRef, newRootRef))
        }
        dependency.set<ArrayNode>(DEPENDS_ON, rewritten)
    }

    private fun rewrite(
        ref: String,
        oldRootRef: String,
        newRootRef: String,
    ): String = when {
        ref == oldRootRef -> newRootRef
        ref.startsWith(oldRootRef + REF_SEPARATOR) -> newRootRef + REF_SEPARATOR + ref.removePrefix(oldRootRef + REF_SEPARATOR)
        else -> ref
    }

    private fun addBundleAsDependencyOfPrimary(
        mainRoot: ObjectNode,
        mainDependencies: ArrayNode,
        mapper: ObjectMapper,
        bundleRef: String,
    ) {
        val primaryRef = mainRoot.get("metadata")?.get("component")?.get(BOM_REF)?.asText() ?: return
        for (dependency in mainDependencies) {
            if (dependency.get(REF_FIELD)?.asText() == primaryRef) {
                val dependsOn = dependency.get(DEPENDS_ON) as? ArrayNode
                    ?: mapper.createArrayNode().also { (dependency as ObjectNode).set<ArrayNode>(DEPENDS_ON, it) }
                dependsOn.add(bundleRef)
                return
            }
        }
    }
}
