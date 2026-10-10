package de.littletrotter.shared.pdf

/**
 * Advance widths of the Adobe Core-14 Helvetica font (the invoice layout prints in
 * Helvetica), 1/1000 em each — the exact numbers from Adobe's own `Helvetica.afm` (unchanged since
 * 1997; the same file PDFBox ships at `org/apache/pdfbox/resources/afm/Helvetica.afm`), not an
 * approximation. Covers WinAnsiEncoding's printable ASCII range plus the German umlauts/ß and a
 * handful of typographic characters (dashes, quotes, ©/®/°, €) that turn up in a company name,
 * street or website — the set this module's callers ([fitFontSize]) actually need, not the full
 * WinAnsi codepage. A codepoint outside this table falls back to [FALLBACK_WIDTH] (the most common
 * lower-case width) rather than throwing: the footer fields this feeds are already validated
 * elsewhere for their own format (email/phone/website), so an exotic character here is a rare,
 * harmless approximation, not a defect to fail loudly over.
 *
 * `HelveticaWidthsAfmParityTest` (kotlin-xpdf, the one module that already depends on PDFBox) pins
 * every entry here against PDFBox's own [org.apache.pdfbox.pdmodel.font.PDType1Font.getStringWidth]
 * — this table is kept in pure Kotlin (no PDFBox dependency in `kotlin-shared`) precisely so the UI
 * form validator can share it without pulling PDFBox into the Vaadin module.
 */
object HelveticaWidths {

    /** The most common width in the table (lower-case Latin letters); used for any codepoint
     *  this table does not carry an exact AFM entry for. */
    const val FALLBACK_WIDTH = 556

    private val widths: Map<Char, Int> = buildMap {
        // ASCII 0x20..0x7E — StandardEncoding and WinAnsiEncoding agree on this whole range.
        putAll(
            mapOf(
                ' ' to 278, '!' to 278, '"' to 355, '#' to 556, '$' to 556, '%' to 889, '&' to 667,
                // WinAnsiEncoding and StandardEncoding (the AFM file's own `C` code column) disagree
                // on exactly these two ASCII slots: 0x27 is "quotesingle" (191) in WinAnsi, not the
                // AFM code table's "quoteright" (222); 0x60 is "grave" (333), not "quoteleft" (222).
                '\'' to 191, '(' to 333, ')' to 333, '*' to 389, '+' to 584, ',' to 278, '-' to 333,
                '.' to 278, '/' to 278,
                '0' to 556, '1' to 556, '2' to 556, '3' to 556, '4' to 556, '5' to 556, '6' to 556,
                '7' to 556, '8' to 556, '9' to 556,
                ':' to 278, ';' to 278, '<' to 584, '=' to 584, '>' to 584, '?' to 556, '@' to 1015,
                'A' to 667, 'B' to 667, 'C' to 722, 'D' to 722, 'E' to 667, 'F' to 611, 'G' to 778,
                'H' to 722, 'I' to 278, 'J' to 500, 'K' to 667, 'L' to 556, 'M' to 833, 'N' to 722,
                'O' to 778, 'P' to 667, 'Q' to 778, 'R' to 722, 'S' to 667, 'T' to 611, 'U' to 722,
                'V' to 667, 'W' to 944, 'X' to 667, 'Y' to 667, 'Z' to 611,
                '[' to 278, '\\' to 278, ']' to 278, '^' to 469, '_' to 556, '`' to 333,
                'a' to 556, 'b' to 556, 'c' to 500, 'd' to 556, 'e' to 556, 'f' to 278, 'g' to 556,
                'h' to 556, 'i' to 222, 'j' to 222, 'k' to 500, 'l' to 222, 'm' to 833, 'n' to 556,
                'o' to 556, 'p' to 556, 'q' to 556, 'r' to 333, 's' to 500, 't' to 278, 'u' to 556,
                'v' to 500, 'w' to 722, 'x' to 500, 'y' to 500, 'z' to 500,
                '{' to 334, '|' to 260, '}' to 334, '~' to 584,
            ),
        )
        // German umlauts/ß (a diacritic in Adobe's core metrics never changes the base letter's
        // advance width — Adieresis == A, adieresis == a, and so on).
        put('Ä', 667); put('Ö', 778); put('Ü', 722)
        put('ä', 556); put('ö', 556); put('ü', 556)
        put('ß', 611)
        // A handful of typographic characters plausible in a company name/website/footer line.
        put('é', 556)
        put('‘', 222); put('’', 222) // quoteleft/quoteright ('‘'/'’')
        put('“', 333); put('”', 333) // quotedblleft/quotedblright ('“'/'”')
        put('–', 556) // endash ('–')
        put('—', 1000) // emdash ('—')
        put('©', 737); put('®', 737); put('°', 400)
        put('€', 556)
    }

    /** [ch]'s Helvetica advance width, 1/1000 em; [FALLBACK_WIDTH] when not covered by [widths]. */
    fun widthOf(ch: Char): Int = widths[ch] ?: FALLBACK_WIDTH

    /** [text]'s total Helvetica advance width at 1pt, i.e. still 1/1000 em — multiply by the
     *  intended font size (pt) and divide by 1000 to get points, see [fitFontSize]. */
    fun totalWidth(text: String): Int = text.sumOf { widthOf(it) }

    /** AFM widths are per 1000 units of em (the em-square scale every Adobe Core-14 metric
     *  file uses); dividing a summed [totalWidth] by this converts it to a fraction of the font
     *  size, e.g. `totalWidth(text) * sizePt / UNITS_PER_EM` is [text]'s width in points at [sizePt]. */
    const val UNITS_PER_EM = 1000f
}

/**
 * The largest font size in `[minPt, maxPt]` (stepping down by [stepPt]) at which [text] fits
 * within [widthPt] points, measured with the real Helvetica metrics ([HelveticaWidths]) rather
 * than an AWT-substituted font — the two can disagree (AWT's Helvetica stand-in is not
 * metric-compatible), which is exactly the gap `PdfLayoutInspector`-based render tests exist to
 * catch for whatever this size feeds into.
 *
 * `null` means [text] does not fit even at [minPt] — the caller's job to decide what happens then
 * (the xpdf renderer falls back to [minPt] itself and accepts the clip; the UI form validator
 * turns `null` into a "too long for the invoice footer" validation error instead of ever reaching
 * the renderer).
 */
fun fitFontSize(
    text: String,
    widthPt: Float = 135f,
    maxPt: Float = 7.5f,
    minPt: Float = 6f,
    stepPt: Float = 0.5f,
): Float? {
    val totalUnits = HelveticaWidths.totalWidth(text)
    // Width at size `s` is totalUnits/1000 * s; solving totalUnits/1000 * s <= widthPt for s
    // avoids a float-stepping loop entirely, but the step grid (maxPt down to minPt by stepPt)
    // is the contract callers rely on (callers only ever get one of these named sizes), so
    // the loop stays — it is at most ~30 iterations for the defaults.
    var size = maxPt
    while (size >= minPt) {
        val widthAtSize = totalUnits * size / HelveticaWidths.UNITS_PER_EM
        if (widthAtSize <= widthPt) return size
        size -= stepPt
    }
    return null
}

/**
 * The footer's display form of a website: strips a leading `http://`/`https://`
 * (case-insensitive — stored as entered, see [de.littletrotter.shared.PartyDto.website]'s own
 * KDoc) and one trailing `/`. Shared by the xpdf renderer and the sender-profile form validator so
 * a "does this fit the footer line" check always measures the same text that would actually print.
 */
fun normalizeWebsiteForDisplay(website: String): String {
    val withoutScheme = website.trim().replaceFirst(SCHEME_PREFIX, "")
    // A bare "/" stays as-is rather than becoming "" — an edge case with no real input, but
    // an empty display string reading as "not set" would be a worse surprise than a stray slash.
    return if (withoutScheme.length > 1) withoutScheme.removeSuffix("/") else withoutScheme
}

private val SCHEME_PREFIX = Regex("^https?://", RegexOption.IGNORE_CASE)
