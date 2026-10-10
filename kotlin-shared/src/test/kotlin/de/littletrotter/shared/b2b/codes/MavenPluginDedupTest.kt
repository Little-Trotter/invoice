package de.littletrotter.shared.b2b.codes

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Root-pom dedup drift guard (RPL-1 side-finding): pins the exact failure class the root
 * `pom.xml` exec-maven-plugin duplication caused — three `<plugin>` blocks under
 * `pluginManagement/plugins` sharing the same `groupId:artifactId` key. Maven's model builder
 * resolves `pluginManagement` lookups by that key, so when the same key appears more than once
 * the later block silently wins and every `<execution>` declared in the earlier blocks (there:
 * ktlint-check, detekt-check, sqlfluff-check) is dropped from the reactor build without any
 * warning — the module poms merely reference the plugin by groupId/artifactId and "inherit"
 * whatever the parent's pluginManagement resolves to.
 *
 * A plain XML parse over the root pom, in the same spirit as `JacocoExcludePatternTest` right
 * above — no reactor build needed for this to fail the moment a second `<plugin>` block for an
 * already-declared artifactId is reintroduced under `pluginManagement/plugins`.
 */
class MavenPluginDedupTest {

    private fun repoRoot(): File {
        // Maven sets the module directory as the test JVM's working directory.
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            if (File(dir, "kotlin-shared/pom.xml").isFile && File(dir, "pom.xml").isFile) {
                return dir
            }
            dir = dir.parentFile ?: return@repeat
        }
        return dir
    }

    private fun directChild(parent: Element, tagName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tagName) {
                return node as Element
            }
        }
        return null
    }

    private fun directChildren(parent: Element, tagName: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tagName) {
                result += node as Element
            }
        }
        return result
    }

    /** The `<artifactId>` text of each `<plugin>` listed directly under `pluginManagement/plugins`. */
    private fun pluginManagementArtifactIds(pomFile: File): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile)
        val project = doc.documentElement
        val build = directChild(project, "build") ?: return emptyList()
        val pluginManagement = directChild(build, "pluginManagement") ?: return emptyList()
        val plugins = directChild(pluginManagement, "plugins") ?: return emptyList()
        return directChildren(plugins, "plugin").mapNotNull { plugin ->
            directChild(plugin, "artifactId")?.textContent?.trim()
        }
    }

    @Test
    fun `root pom pluginManagement declares each artifactId at most once`() {
        val pomFile = File(repoRoot(), "pom.xml")
        assertTrue(pomFile.isFile, "expected root pom at $pomFile")

        val artifactIds = pluginManagementArtifactIds(pomFile)
        val duplicates = artifactIds.groupingBy { it }.eachCount().filter { it.value > 1 }

        assertTrue(
            duplicates.isEmpty(),
            "root pom declares the same artifactId more than once under " +
                "pluginManagement/plugins — Maven keeps only the LAST such block, silently " +
                "dropping every <execution> declared in the earlier ones: $duplicates",
        )
    }
}
