package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.shared.likePattern
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * arcs/ §1 — regression guard: every `LIKE :param` in production code must spell out
 * its `ESCAPE` clause on the same line, because [likePattern] inserts backslash escapes that are
 * otherwise matched literally (full reasoning in its KDoc). The rule was forgotten once — the
 * invoice overview shipped seven unescaped filters — so it is enforced here instead of relying
 * on review discipline.
 */
class LikeEscapeGuardTest {

    @Test
    fun `every LIKE with a bound parameter spells out its ESCAPE clause`() {
        val sourceRoot = File("src/main/kotlin")
        assertTrue(sourceRoot.isDirectory) { "expected to run from the module directory, cwd=${File(".").absolutePath}" }

        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val trimmed = line.trim()
                    val isComment = trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
                    if (!isComment && LIKE_PARAM.containsMatchIn(line) && !line.contains("ESCAPE")) {
                        "${file.path}:${index + 1}: $trimmed"
                    } else null
                }
            }
            .toList()

        assertTrue(offenders.isEmpty()) {
            "LIKE without an ESCAPE clause on the same line — pair it with likePattern(...) " +
                "(see BasePanacheRepository):\n" + offenders.joinToString("\n")
        }
    }

    private companion object {
        val LIKE_PARAM = Regex("""LIKE\s+:\w""")
    }
}
