package de.littletrotter.shared.documents

/**
 * The characters the invoice PDF can show. The PDF is typeset with the bundled Liberation Sans
 * family and the engine refuses to render a character none of its styles has a glyph for, which
 * would leave an already finalized invoice without a document. The set is therefore the
 * intersection of the glyph coverage of every bundled style (regular, bold, italic, bold italic),
 * so text stays renderable whatever emphasis the free-text markup applies. Free text that enters
 * the XML and the PDF alike is checked against it up front, which keeps both representations
 * identical instead of silently dropping characters from one of them.
 *
 * Line breaks and tabs are layout, not glyphs, and are always allowed; the soft hyphen is allowed
 * because the fonts carry it; every other control or invisible format character is rejected since
 * it would be copied from other applications without being visible to the user.
 */
object PdfRenderableText {
    /** Start/end (inclusive) code point pairs of the glyph intersection, ascending. */
    private val ranges: IntArray = intArrayOf(
        0x20, 0x7E, 0xA0, 0x36F, 0x374, 0x375, 0x37A, 0x37E, 0x384, 0x38A, 0x38C, 0x38C, 0x38E, 0x3A1,
        0x3A3, 0x3CE, 0x3D0, 0x513, 0x51A, 0x51D, 0x591, 0x5C7, 0x5D0, 0x5EA, 0x5F0, 0x5F4, 0x1D00, 0x1DCA,
        0x1DFE, 0x1E9B, 0x1E9E, 0x1E9E, 0x1EA0, 0x1EF9, 0x1F00, 0x1F15, 0x1F18, 0x1F1D, 0x1F20, 0x1F45,
        0x1F48, 0x1F4D, 0x1F50, 0x1F57, 0x1F59, 0x1F59, 0x1F5B, 0x1F5B, 0x1F5D, 0x1F5D, 0x1F5F, 0x1F7D,
        0x1F80, 0x1FB4, 0x1FB6, 0x1FC4, 0x1FC6, 0x1FD3, 0x1FD6, 0x1FDB, 0x1FDD, 0x1FEF, 0x1FF2, 0x1FF4,
        0x1FF6, 0x1FFE, 0x2000, 0x2010, 0x2012, 0x2022, 0x2026, 0x2026, 0x202A, 0x2030, 0x2032, 0x2034,
        0x2039, 0x203A, 0x203C, 0x203C, 0x203E, 0x203E, 0x2044, 0x2044, 0x205E, 0x205E, 0x206A, 0x206F,
        0x2074, 0x2079, 0x207F, 0x2089, 0x2090, 0x2094, 0x20A0, 0x20B5, 0x20BF, 0x20BF, 0x20F0, 0x20F0,
        0x2105, 0x2105, 0x2113, 0x2113, 0x2116, 0x2117, 0x2122, 0x2122, 0x2126, 0x2126, 0x212E, 0x212E,
        0x214D, 0x214E, 0x2153, 0x2154, 0x215B, 0x215E, 0x2184, 0x2184, 0x2190, 0x2195, 0x21A8, 0x21A8,
        0x21D4, 0x21D4, 0x2202, 0x2202, 0x2206, 0x2206, 0x220F, 0x220F, 0x2211, 0x2212, 0x2215, 0x2215,
        0x2219, 0x221A, 0x221E, 0x221F, 0x2229, 0x2229, 0x222B, 0x222B, 0x2248, 0x2248, 0x2260, 0x2262,
        0x2264, 0x2265, 0x2302, 0x2302, 0x2310, 0x2310, 0x2320, 0x2321, 0x2500, 0x2500, 0x2502, 0x2502,
        0x250C, 0x250C, 0x2510, 0x2510, 0x2514, 0x2514, 0x2518, 0x2518, 0x251C, 0x251C, 0x2524, 0x2524,
        0x252C, 0x252C, 0x2534, 0x2534, 0x253C, 0x253C, 0x2550, 0x256C, 0x2580, 0x2580, 0x2584, 0x2584,
        0x2588, 0x2588, 0x258C, 0x258C, 0x2590, 0x2593, 0x25A0, 0x25A1, 0x25AA, 0x25AC, 0x25B2, 0x25B2,
        0x25BA, 0x25BA, 0x25BC, 0x25BC, 0x25C4, 0x25C4, 0x25CA, 0x25CC, 0x25CF, 0x25D9, 0x25E6, 0x25E6,
        0x263A, 0x263C, 0x263F, 0x2647, 0x2660, 0x2660, 0x2663, 0x2663, 0x2665, 0x2666, 0x2669, 0x266C,
        0x266F, 0x266F, 0x2C60, 0x2C6D, 0x2C71, 0x2C77, 0x2E17, 0x2E17, 0xA717, 0xA721, 0xA788, 0xA78C,
        0xFB01, 0xFB02, 0xFB1D, 0xFB36, 0xFB38, 0xFB3C, 0xFB3E, 0xFB3E, 0xFB40, 0xFB41, 0xFB43, 0xFB44,
        0xFB46, 0xFB4F, 0xFE20, 0xFE23, 0xFFFC, 0xFFFC,
    )

    private const val SOFT_HYPHEN = 0xAD

    /** The glyph ranges of the table; exposed so the font coverage guard can compare table and fonts. */
    fun glyphRanges(): List<IntRange> = (ranges.indices step 2).map { ranges[it]..ranges[it + 1] }

    fun isRenderable(codePoint: Int): Boolean =
        when {
            codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code -> true
            codePoint == SOFT_HYPHEN -> true
            Character.getType(codePoint) == Character.FORMAT.toInt() -> false
            else -> inGlyphTable(codePoint)
        }

    fun isRenderable(text: String?): Boolean = text == null || firstOffending(text, 1).isEmpty()

    /** The first [limit] distinct characters of [text] the PDF cannot show, in order of appearance. */
    fun firstOffending(text: String?, limit: Int = 5): List<String> {
        if (text == null) return emptyList()
        val found = LinkedHashSet<String>()
        var i = 0
        while (i < text.length && found.size < limit) {
            val cp = text.codePointAt(i)
            if (!isRenderable(cp)) found.add(describe(cp))
            i += Character.charCount(cp)
        }
        return found.toList()
    }

    private fun describe(codePoint: Int): String =
        if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT.toInt()) {
            "U+%04X".format(codePoint)
        } else {
            String(Character.toChars(codePoint))
        }

    private fun inGlyphTable(codePoint: Int): Boolean {
        var low = 0
        var high = ranges.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                codePoint < ranges[mid * 2] -> high = mid - 1
                codePoint > ranges[mid * 2 + 1] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }
}
