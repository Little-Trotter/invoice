package de.littletrotter.shared.b2b.codes

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins the ".class" suffix convention on jacoco class-name excludes across every module
 * pom. Rationale: a jacoco `<exclude>` pattern that targets
 * one or more specific classes rather than a whole package subtree must end in ".class" — jacoco
 * matches Ant-style patterns against *file* names, so a bare class-name segment (e.g. two stars,
 * a slash, a star, then "_", with no ".class") never matches "Foo_.class" and silently excludes
 * nothing. This exact bug hit twice before this
 * test existed: kotlin-shared's `B2bCodeListValues*` excludes originally lacked the suffix (fixed
 * earlier), and the audit that added this test found kotlin-invoice's
 * "LittleTrotterApplication*" pattern dead — the class had been renamed to `Main` and the pattern
 * was never updated, leaving the real bootstrap class silently uncounted by any exclude. Any
 * pattern already ending in a wildcard (whole package-subtree excludes, or a bare trailing star)
 * is exempt: that trailing star already absorbs the ".class" extension by construction — only a
 * pattern ending in a literal, non-wildcard character needs the suffix spelled out.
 *
 * A plain XML parse over the checked-in poms, mirroring `B2bTermSchemaRangeTest`
 * (kotlin-invoice, a resource-parse drift test in the same spirit) — no reactor build needed for
 * this to fail the moment someone reintroduces a bare class-name pattern.
 */
class JacocoExcludePatternTest {

    private fun repoRoot(): File {
        // Maven sets the module directory as the test JVM's working directory.
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            if (File(dir, "kotlin-shared/pom.xml").isFile && File(dir, "kotlin-invoice/pom.xml").isFile) {
                return dir
            }
            dir = dir.parentFile ?: return@repeat
        }
        return dir
    }

    private val modulePomPaths = listOf(
        "pom.xml",
        "kotlin-shared/pom.xml",
        "kotlin-invoice/pom.xml",
        "kotlin-xpdf/pom.xml",
        "kotlin-e2e-tests/pom.xml",
    )

    /** Every `<exclude>` text found anywhere under a `<plugin>` whose artifactId is jacoco-maven-plugin. */
    private fun jacocoExcludes(pomFile: File): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile)
        val excludes = mutableListOf<String>()
        val plugins = doc.getElementsByTagName("plugin")
        for (i in 0 until plugins.length) {
            val plugin = plugins.item(i) as Element
            val artifactIds = plugin.getElementsByTagName("artifactId")
            val isJacoco = (0 until artifactIds.length).any { j ->
                val node = artifactIds.item(j)
                node.parentNode === plugin && node.textContent.trim() == "jacoco-maven-plugin"
            }
            if (!isJacoco) continue
            val excludeNodes = plugin.getElementsByTagName("exclude")
            for (j in 0 until excludeNodes.length) {
                excludes += excludeNodes.item(j).textContent.trim()
            }
        }
        return excludes
    }

    @Test
    fun `every jacoco class-name exclude carries the class suffix`() {
        val repoRoot = repoRoot()
        val violations = mutableListOf<String>()
        modulePomPaths.forEach { relPath ->
            val pomFile = File(repoRoot, relPath)
            assertTrue(pomFile.isFile, "expected pom at $pomFile")
            jacocoExcludes(pomFile).forEach { pattern ->
                // A trailing wildcard (whole-package "/**", or a bare "*") already matches any
                // suffix, ".class" included, so only a literal, non-wildcard ending is at risk.
                if (!pattern.endsWith("*") && !pattern.endsWith(".class")) {
                    violations += "$relPath: \"$pattern\""
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "jacoco <exclude> patterns targeting specific classes must end in \".class\" " +
                "(a bare class-name segment silently matches nothing): $violations",
        )
    }
}
