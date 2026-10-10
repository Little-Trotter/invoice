package de.littletrotter.shared.b2b

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Drift test for the generated portion of [B2bTerm] (the `BT_nnn`/`BT_X_nnn` block
 * between the `GENERATED-TERMS-BEGIN`/`-END` markers).
 *
 * The generator script this block comes from moved out of this repository along with the rest of
 * the dev-workshop tooling, so this test never shells out to python or touches an external
 * process: it compares the checked-in block against a committed fixture
 * (`generated-terms-block.txt`, next to this class under `src/test/resources`) — the generator's
 * last known-good output for the same source workbooks. Pure file comparison, runs everywhere.
 *
 * Regenerate the fixture (only needed when the ZUGFeRD schema workbooks change) with
 * `scripts/regen-b2b-term-drift-fixture.sh`, a manual, dev-time step that shells out to the
 * generator in the sibling `little-trotter-admin-tools` checkout — never part of `make test`. The
 * numbering/invariant tests in [B2bTermTest] cover the checked-in data unconditionally either way.
 */
class B2bTermDbDriftTest {

    private fun repoRoot(): File {
        // Maven sets the module directory as the test JVM's working directory.
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            if (File(dir, "kotlin-shared/src/main/kotlin/de/littletrotter/shared/b2b/B2bTerm.kt").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        return dir
    }

    @Test
    fun `the checked-in generated block matches the committed drift fixture`() {
        val b2bTermKt = File(repoRoot(), "kotlin-shared/src/main/kotlin/de/littletrotter/shared/b2b/B2bTerm.kt")
        val fileText = b2bTermKt.readText()
        val begin = "    // GENERATED-TERMS-BEGIN — do not hand-edit; regenerate via scripts/codegen/facturx_termdb_gen.py"
        val end = "    // GENERATED-TERMS-END"
        val beginIdx = fileText.indexOf(begin)
        val endIdx = fileText.indexOf(end, beginIdx) + end.length
        val checkedInBlock = fileText.substring(beginIdx, endIdx)

        val fixtureStream = javaClass.getResourceAsStream("generated-terms-block.txt")
            ?: error("drift fixture missing from the test classpath — see this class's KDoc")
        val expectedBlock = fixtureStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        assertEquals(
            expectedBlock.trim(),
            checkedInBlock.trim(),
            "B2bTerm.kt's generated block drifted from the committed drift fixture — regenerate " +
                "generated-terms-block.txt via scripts/regen-b2b-term-drift-fixture.sh if the ZUGFeRD " +
                "workbooks changed, or B2bTerm.kt was hand-edited without regenerating.",
        )
    }
}
