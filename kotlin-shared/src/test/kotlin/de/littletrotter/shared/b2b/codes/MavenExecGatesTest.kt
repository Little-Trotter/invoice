package de.littletrotter.shared.b2b.codes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Aggregator-module drift guard for the exec-maven-plugin lint gates, sibling to
 * [MavenPluginDedupTest] and born from the same class of failure: `mvn verify` died in its very
 * last reactor project because `detekt` was pointed at `coverage-report`, a module that contains
 * nothing but a pom.
 *
 * The mechanism, verified against the effective pom rather than assumed: declaring
 * exec-maven-plugin in the root's `build/plugins` (which is what binds the project-wide
 * `config-format-check` to the aggregator, so it runs ONCE and first instead of once per module)
 * hands every OTHER execution of that plugin down to the child modules too. `inherited=false`
 * does not stop that — neither on the `<plugin>` nor on each `<execution>`. Modules with sources
 * want those gates; the two aggregators have nothing for them to read.
 *
 * So each aggregator switches the source-bound gates off with `phase=none`, and this test pins
 * that the set stays complete: add a gate to the root's pluginManagement without listing it in
 * both aggregators and this fails, instead of the next full build failing minutes in.
 */
class MavenExecGatesTest {

    /** Executions that read a module's own sources/migrations and therefore make no sense in a
     *  module whose packaging is `pom`. `config-format-check` is deliberately absent: it lints the
     *  whole repository from the root and is exactly what the aggregator is meant to run. */
    private val sourceBoundGateIds = setOf(
        "ktlint-check",
        "detekt-check",
        "detekt-baseline-ratchet",
        "sqlfluff-check",
    )

    /** Repo split 05.09.2026 — the coverage aggregator moved from coverage-report/ to
     *  ci/coverage-report/ along with the rest of the build plumbing. The path is spelled out
     *  rather than discovered, so a module that quietly stops being an aggregator (or moves
     *  again) fails here instead of silently dropping out of this gate's coverage. */
    private val aggregatorPoms = listOf("pom.xml", "ci/coverage-report/pom.xml", "kotlin-email/pom.xml")

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

    private fun directChild(parent: Element, tagName: String): Element? =
        directChildren(parent, tagName).firstOrNull()

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

    private fun parse(relativePath: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(repoRoot(), relativePath)).documentElement

    private fun execPlugin(project: Element, underPluginManagement: Boolean): Element? {
        val build = directChild(project, "build") ?: return null
        val pluginsHost = if (underPluginManagement) directChild(build, "pluginManagement") ?: return null else build
        val plugins = directChild(pluginsHost, "plugins") ?: return null
        return directChildren(plugins, "plugin").firstOrNull {
            directChild(it, "artifactId")?.textContent?.trim() == "exec-maven-plugin"
        }
    }

    /** Execution id → phase, for the exec-maven-plugin block in the given section. */
    private fun executions(project: Element, underPluginManagement: Boolean): Map<String, String> {
        val plugin = execPlugin(project, underPluginManagement) ?: return emptyMap()
        val executions = directChild(plugin, "executions") ?: return emptyMap()
        return directChildren(executions, "execution").mapNotNull { execution ->
            val id = directChild(execution, "id")?.textContent?.trim() ?: return@mapNotNull null
            id to (directChild(execution, "phase")?.textContent?.trim() ?: "")
        }.toMap()
    }

    @Test
    fun `every source-bound gate this test knows about is actually declared in the root pluginManagement`() {
        val declared = executions(parse("pom.xml"), underPluginManagement = true).keys

        assertTrue(
            declared.containsAll(sourceBoundGateIds),
            "this test's own list of source-bound gates has drifted from the root pom: " +
                "${sourceBoundGateIds - declared} no longer exist there",
        )
    }

    @Test
    fun `every aggregator switches off every source-bound gate`() {
        aggregatorPoms.forEach { path ->
            val phases = executions(parse(path), underPluginManagement = false)
            sourceBoundGateIds.forEach { id ->
                assertEquals(
                    "none",
                    phases[id],
                    "$path must bind '$id' to phase 'none' — an aggregator has no sources for it " +
                        "to read, and the execution reaches it by inheritance whether it wants it " +
                        "or not (see this test's own KDoc)",
                )
            }
        }
    }

    @Test
    fun `the repository-wide config gate runs on the aggregator and nowhere else`() {
        val rootPhases = executions(parse("pom.xml"), underPluginManagement = false)
        assertEquals(
            "verify",
            rootPhases["config-format-check"],
            "config-format-check lints the whole repo and belongs to the root aggregator, where it " +
                "runs once and first",
        )

        val managed = executions(parse("pom.xml"), underPluginManagement = true)
        assertTrue(
            "config-format-check" !in managed,
            "config-format-check must not sit in pluginManagement as well — every module that " +
                "activates exec-maven-plugin would run the identical repository-wide check again, " +
                "and report a finding from a different module as its own failure",
        )
    }
}
