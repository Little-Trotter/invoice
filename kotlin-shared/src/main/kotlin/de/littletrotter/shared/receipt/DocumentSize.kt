package de.littletrotter.shared.receipt

/**
 * A byte count with arithmetic on it, so no caller does byte math by hand and no
 * caller can quietly pick a different unit convention (1000 vs 1024) than the rest of the
 * codebase.
 *
 * Deliberately **no formatting, no locale, no i18n** here — that is a UI concern, and
 * keeping it out of `kotlin-shared` stops a second decimal-separator rule from appearing next to
 * [de.littletrotter.shared.Money]'s.
 *
 * Two subtraction operations exist on purpose, mirroring the two precision worlds documented on
 * [de.littletrotter.shared.Money]: the [minus] operator is strict — a result that would go
 * negative is a programming error and throws, the same way [de.littletrotter.shared.Money]'s
 * division rejects a zero divisor instead of silently producing nonsense. [remainingAfter] is
 * the named "remaining budget" case —
 * subtracting more than is left is an entirely expected outcome (a receipt set that blew the
 * budget) and clamps at [ZERO] rather than throwing, so a caller computing "how much room is
 * left" never has to guard the subtraction itself.
 */
data class DocumentSize(val bytes: Long) : Comparable<DocumentSize> {

    init {
        require(bytes >= 0) { "Document size must not be negative, was $bytes bytes." }
    }

    /** Fractional MiB for display math; [de.littletrotter.shared.receipt] does no rounding or formatting itself. */
    val mebiBytes: Double
        get() = bytes.toDouble() / BYTES_PER_MEBIBYTE

    operator fun plus(other: DocumentSize): DocumentSize = DocumentSize(bytes + other.bytes)

    /**
     * Strict subtraction: throws if [other] is larger than this size. Use [remainingAfter] for
     * the "how much budget is left" case, where a negative result is an expected outcome to be
     * clamped, not a programming error to reject.
     *
     * @throws IllegalArgumentException if the result would be negative.
     */
    operator fun minus(other: DocumentSize): DocumentSize {
        require(other.bytes <= bytes) {
            "Cannot subtract $other from $this: the result would be negative."
        }
        return DocumentSize(bytes - other.bytes)
    }

    /**
     * "How much of this size is left after [other] is spent" — clamped at [ZERO] instead of
     * throwing, unlike [minus]. This is the operation an "invoice document budget still has N
     * bytes free" calculation wants: a receipt set that already exceeds the budget has zero bytes
     * remaining, not a negative number the caller must special-case.
     */
    fun remainingAfter(other: DocumentSize): DocumentSize =
        if (other.bytes >= bytes) ZERO else DocumentSize(bytes - other.bytes)

    /**
     * @throws IllegalArgumentException if [count] is negative.
     */
    operator fun times(count: Int): DocumentSize {
        require(count >= 0) { "Cannot multiply a document size by a negative count, was $count." }
        return DocumentSize(bytes * count)
    }

    /**
     * The size this many raw bytes take once Base64-encoded: `ceil(bytes/3)*4`, the 4-byte-block
     * rounding Base64 always applies. BG-24's `AttachmentBinaryObject` embeds a receipt as Base64
     * text inside the invoice XML — the strictest channel a receipt travels over — so the invoice
     * document budget sizes against this, not [bytes].
     */
    fun base64Encoded(): DocumentSize {
        val encodedBlocks = (bytes + BYTES_PER_BASE64_BLOCK - 1) / BYTES_PER_BASE64_BLOCK
        return DocumentSize(encodedBlocks * BASE64_ENCODED_BLOCK_SIZE)
    }

    override fun compareTo(other: DocumentSize): Int = bytes.compareTo(other.bytes)

    override fun toString(): String = "${bytes}B"

    companion object {
        private const val BYTES_PER_KIBIBYTE = 1024L
        private const val BYTES_PER_MEBIBYTE = BYTES_PER_KIBIBYTE * 1024L

        /** Base64 encodes 3 raw bytes into 4 output bytes, padding the last block up to it. */
        private const val BYTES_PER_BASE64_BLOCK = 3L
        private const val BASE64_ENCODED_BLOCK_SIZE = 4L

        val ZERO = DocumentSize(0L)

        fun ofBytes(bytes: Long): DocumentSize = DocumentSize(bytes)

        fun ofKibiBytes(kibiBytes: Long): DocumentSize = DocumentSize(kibiBytes * BYTES_PER_KIBIBYTE)

        fun ofMebiBytes(mebiBytes: Long): DocumentSize = DocumentSize(mebiBytes * BYTES_PER_MEBIBYTE)

        /**
         * Sums sizes without the `fold(DocumentSize.ZERO)` boilerplate — mirrors
         * [de.littletrotter.shared.Money.sum].
         */
        fun sum(sizes: Iterable<DocumentSize>): DocumentSize =
            sizes.fold(ZERO) { acc, size -> acc + size }
    }
}

/** Sums document sizes without the `fold(DocumentSize.ZERO)` boilerplate — mirrors `Iterable<Money>.sum()`. */
fun Iterable<DocumentSize>.sum(): DocumentSize = DocumentSize.sum(this)
