package de.littletrotter.xpdf.impl

/**
 * Synthetic free-text shapes for T3 print/layout tests — the pure height/line-count estimate
 * ([FreeTextLayoutTest], tests [FreeTextLayout.kt]'s own functions). Kept separate so
 * kotlin-xpdf's own tests can generate shapes without requiring a real layout engine.
 */
internal object FreeTextProseFixtures {

    /** Realistic German prose of at most [target] characters: numbered sentences (unique
     *  markers S001…), a paragraph break every fourth sentence, closing with `ENDE`. */
    fun prose(target: Int): String {
        val sb = StringBuilder()
        var n = 1
        while (true) {
            val sentence = "S${n.toString().padStart(3, '0')} Vielen Dank für Ihren Auftrag, die vereinbarten " +
                "Arbeiten am Objekt wurden vollständig und termingerecht ausgeführt."
            val add = (if (sb.isEmpty()) "" else if (n % 4 == 1) "\n" else " ") + sentence
            if (sb.length + add.length > target - 5) break
            sb.append(add)
            n++
        }
        return sb.append(" ENDE").toString()
    }

    /** One short word per line, at most [target] characters in total (the worst case for line count). */
    fun oneWordLines(target: Int): String {
        val sb = StringBuilder()
        var n = 1
        while (sb.length + 6 <= target) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append('w').append(n.toString().padStart(4, '0'))
            n++
        }
        return sb.toString()
    }

    /** `FreeTextMarkup` list lines of at most [target] characters, levels 0-2, bullets and numbers. */
    fun listLines(target: Int): String {
        val sb = StringBuilder()
        var n = 1
        while (true) {
            val level = (n - 1) % 3
            val marker = if (n % 2 == 0) "${n % 100}. " else "- "
            val line = " ".repeat(4 * level) + marker + "L${n.toString().padStart(3, '0')} Punkt der Leistungsübersicht mit etwas mehr Text"
            val add = (if (sb.isEmpty()) "" else "\n") + line
            if (sb.length + add.length > target) break
            sb.append(add)
            n++
        }
        return sb.toString()
    }
}
