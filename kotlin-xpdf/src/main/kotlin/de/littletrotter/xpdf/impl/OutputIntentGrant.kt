package de.littletrotter.xpdf.impl

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64

class OutputIntentGrant private constructor(
    val subjectHash: String,
    val notAfter: LocalDate?,
    val signature: ByteArray,
) {
    val signedPayload: String = "$VERSION.$subjectHash.${notAfter?.toString() ?: UNLIMITED}"

    companion object {
        private const val VERSION = "oi1"
        private const val UNLIMITED = "-"
        private const val PART_COUNT = 4
        private const val VERSION_AT = 0
        private const val HASH_AT = 1
        private const val EXPIRY_AT = 2
        private const val SIGNATURE_AT = 3
        private const val HASH_BYTES = 32
        private const val SIGNATURE_BYTES = 64
        private val DATE_SHAPE = Regex("\\d{4}-\\d{2}-\\d{2}")

        fun parse(token: String?): OutputIntentGrant? {
            val parts = token?.trim()?.split('.')
            return if (parts != null && parts.size == PART_COUNT && parts[VERSION_AT] == VERSION) fromParts(parts) else null
        }

        private fun fromParts(parts: List<String>): OutputIntentGrant? {
            val hash = parts[HASH_AT].takeIf { decodedSize(it) == HASH_BYTES }
            val signature = decode(parts[SIGNATURE_AT])?.takeIf { it.size == SIGNATURE_BYTES }
            val expiry = parts[EXPIRY_AT]
            val notAfter = if (expiry == UNLIMITED) null else parseDate(expiry)
            val expiryValid = notAfter != null || expiry == UNLIMITED
            if (hash == null || signature == null || !expiryValid) return null
            return OutputIntentGrant(hash, notAfter, signature)
        }

        private fun parseDate(text: String): LocalDate? {
            if (!DATE_SHAPE.matches(text)) return null
            return try {
                LocalDate.parse(text)
            } catch (_: DateTimeParseException) {
                null
            }
        }

        private fun decode(text: String): ByteArray? =
            try {
                Base64.getUrlDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                null
            }

        private fun decodedSize(text: String): Int? = decode(text)?.size
    }
}
