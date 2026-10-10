package de.littletrotter.infrastructure.export.workbook

/**
 * Converts a 0-based column index into its Excel column letter (`0` → `"A"`, `25` → `"Z"`,
 * `26` → `"AA"`, …) — needed by every sheet writer that builds a formula string referencing one of
 * its own cells by address (each sheet's own control-formula block).
 */
internal object ExcelColumnLetters {

    private const val ALPHABET_SIZE = 26

    fun of(index: Int): String {
        require(index >= 0) { "Column index must not be negative: $index" }
        var remaining = index
        val letters = StringBuilder()
        while (remaining >= 0) {
            letters.insert(0, 'A' + remaining % ALPHABET_SIZE)
            remaining = remaining / ALPHABET_SIZE - 1
        }
        return letters.toString()
    }
}
