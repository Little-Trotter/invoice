package de.littletrotter.e2e

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Structural guard: this module carries ONLY cross-module E2E suites.
 * Unit tests live in the module that owns the code under test (conventions §11); a unit
 * test parked here would silently drop out of that module's coverage accounting.
 *
 * Rule: every Kotlin file under src/test that contains a JUnit `@Test` must be named
 * `*E2EIT.kt`. Files without `@Test` (test resources, extensions, support helpers,
 * this guard's own home) are exempt — except this guard itself, which is the single
 * allowed non-E2EIT test file.
 */
class E2eModuleStructureTest {

    @Test
    fun `every test-bearing file in this module is an E2EIT suite`() {
        val testRoot = Path.of("src/test/kotlin")
        val offenders = Files.walk(testRoot).use { paths ->
            paths.asSequence()
                .filter { it.extension == "kt" }
                .filter { it.name != "E2eModuleStructureTest.kt" }
                .filter { !it.name.endsWith("E2EIT.kt") }
                .filter { file -> Regex("""^\s*@Test\b""", RegexOption.MULTILINE).containsMatchIn(file.readText()) }
                .map { it.toString() }
                .toList()
        }
        assertTrue(
            offenders.isEmpty(),
            "kotlin-e2e-tests may contain only *E2EIT suites — move these unit tests into " +
                "the module owning the code under test: $offenders",
        )
    }
}
