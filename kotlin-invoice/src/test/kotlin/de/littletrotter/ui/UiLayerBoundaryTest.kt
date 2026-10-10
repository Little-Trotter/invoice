package de.littletrotter.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * UI-7 — codifies the healthy `ui` -> `application` boundary that UI-6's audit measured: the UI
 * layer must never import `de.littletrotter.domain.repository.*` or `jakarta.persistence.EntityManager`,
 * and must never carry a `@Transactional` annotation. Those belong to the application/domain
 * layers; the UI talks to them through facades and DTOs. This is a plain filesystem scan (no
 * ArchUnit dependency) so it keeps the boundary enforced without a new dependency.
 */
class UiLayerBoundaryTest {

    private fun uiSourceRoot(): File {
        // Test working directory is the module root (kotlin-invoice/) under both Maven Surefire
        // and IDE runners for this project.
        val candidates = listOf(
            File("src/main/kotlin/de/littletrotter/ui"),
            File("kotlin-invoice/src/main/kotlin/de/littletrotter/ui"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate src/main/kotlin/de/littletrotter/ui from working dir ${File(".").absolutePath}")
    }

    private fun uiKotlinFiles(): List<File> =
        uiSourceRoot().walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    private val forbiddenImportPrefixes = listOf(
        "import de.littletrotter.domain.repository.",
        "import jakarta.persistence.EntityManager",
    )

    private fun isTransactionalAnnotationUsage(trimmedLine: String): Boolean {
        // Match the annotation as code: bare, or after a visibility/other modifier on the same
        // line (e.g. "public @Transactional" style, or multiple annotations chained). Never
        // matches inside a comment or KDoc prose because those lines don't start with the
        // annotation token itself.
        if (trimmedLine.startsWith("@Transactional")) return true
        val modifiers = setOf("public", "private", "protected", "internal", "open", "final")
        val firstToken = trimmedLine.substringBefore(' ')
        return firstToken in modifiers && trimmedLine.substringAfter(' ').trimStart().startsWith("@Transactional")
    }

    @Test
    fun `UI layer never imports the repository or EntityManager or uses @Transactional`() {
        val violations = mutableListOf<String>()

        for (file in uiKotlinFiles()) {
            file.readLines().forEachIndexed { index, rawLine ->
                val trimmed = rawLine.trim()
                val lineNumber = index + 1

                if (trimmed.startsWith("import ")) {
                    forbiddenImportPrefixes.firstOrNull { trimmed.startsWith(it) }?.let { hit ->
                        violations += "${file.path}:$lineNumber imports forbidden symbol ($hit...): $trimmed"
                    }
                }

                if (isTransactionalAnnotationUsage(trimmed)) {
                    violations += "${file.path}:$lineNumber uses @Transactional, which is forbidden in the UI layer: $trimmed"
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "UI layer boundary violated — move the offending code behind a facade/application service:\n" +
                violations.joinToString("\n"),
        )
    }
}
