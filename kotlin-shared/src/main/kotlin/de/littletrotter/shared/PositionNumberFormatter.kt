package de.littletrotter.shared

/**
 * Formats invoice position numbers for display in PDFs, Excel exports, and UI grids.
 * The underlying `positionId` is stored as an unpadded string ("3"), but all display
 * layers render it as a three-digit zero-padded number ("003"), with sub-rows appended
 * as ".1", ".2", etc., preserving the padding on the main position number.
 *
 * Sub-index padding decision: for invoices with 100+ trip expenses,
 * the format uses unpadded sub-indices ("003.1".."003.100") for simplicity and readability.
 */
object PositionNumberFormatter {
    /**
     * Formats a position number with optional sub-index.
     *
     * @param positionId The position identifier string (usually a numeric string like "3" or "120").
     *                   Non-numeric IDs (e.g., "POS-001") extract only the digits.
     *                   Falls back to [fallbackIndex] if no digits can be extracted.
     * @param subIndex   Optional sub-row index (e.g., 1 for the first trip expense).
     *                   When provided, returns "003.1" format (no padding on the sub-index).
     * @param fallbackIndex When [positionId] contains no digits, use this numeric value instead.
     *                      Defaults to 0 for edge cases. Should typically be the 1-based item index.
     * @return Three-digit zero-padded position ("003") or with sub-index ("003.1").
     */
    fun format(positionId: String, subIndex: Int? = null, fallbackIndex: Int = 0): String {
        val digits = positionId.filter { it.isDigit() }
        val numeric = digits.toIntOrNull() ?: fallbackIndex
        val formatted = "%03d".format(numeric)
        return if (subIndex != null) "$formatted.$subIndex" else formatted
    }
}
