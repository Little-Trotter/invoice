package de.littletrotter.domain.shared

import java.security.SecureRandom

/**
 * Stable handle for a record, deliberately not semantic and never changes.
 * 10 chars from Crockford-Base32 (no I, L, O, U — confusion-resistant when spoken/typed).
 * Random, not data-derived: a hash would change with the data it describes.
 */
object ShortHash {

    const val LENGTH = 10
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    /** Excluded characters (I, L, O, U). */
    val excludedCharacters: String get() = "ILOU"

    fun random(): String {
        val sb = StringBuilder(LENGTH)
        repeat(LENGTH) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /** Generate another hash if it would collide with existing. */
    fun randomExcept(existing: Set<String>): String {
        var candidate = random()
        while (candidate in existing) candidate = random()
        return candidate
    }
}
