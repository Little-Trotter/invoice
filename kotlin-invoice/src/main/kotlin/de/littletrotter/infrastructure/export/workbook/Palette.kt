package de.littletrotter.infrastructure.export.workbook

import kotlin.math.pow

/**
 * The workbook's own design tokens (Google Material, flat; mirrors the application's own
 * `--lt-*` CSS custom properties) and a pure WCAG contrast-ratio function guarding them. Kept
 * separate from [CellStyles] (which only ever turns these values into actual POI
 * [org.apache.poi.ss.usermodel.CellStyle] instances) so the palette's own RGB values and the pure
 * maths that checks them sit together, next to each other, the way ContrastRatioTest exercises
 * them — [CellStyles]'s own job stays "how do I build a style", not "how do I compute a contrast
 * ratio".
 */
internal object Palette {

    /** Present on Windows, macOS, LibreOffice and Google Sheets — POI's own default (Calibri 11)
     *  is never left implicit. PO decision P4 (2026-09-10). */
    const val FONT_FAMILY = "Arial"
    const val BODY_FONT_SIZE = 12
    const val HEADING_FONT_SIZE = 14

    /** Two lines of 14 pt, so a wrapped two-line header label never stacks any higher. */
    const val HEADER_ROW_HEIGHT_POINTS = 36f

    /** `--lt-text-primary` (Material Grey 900-ish `#202124`). */
    val TEXT_PRIMARY_RGB = rgb(0x20, 0x21, 0x24)

    /** `--lt-text-secondary` (`#5F6368`), contrast 5.9:1 on white. */
    val TEXT_SECONDARY_RGB = rgb(0x5F, 0x63, 0x68)

    val WHITE_RGB = rgb(0xFF, 0xFF, 0xFF)

    /** Material Blue 700 (`#1967D2`) — the header row's own fill; contrast ~5.4:1 against white
     *  text. PO decision 2026-09-11: `--lt-accent`'s own Blue 600 (`#1A73E8`) was rejected here —
     *  its precise WCAG ratio against white is only ~4.505:1, clearing the AA floor by a hair
     *  with no real margin; Blue 700 keeps the same hue family with headroom. */
    val HEADER_FILL_RGB = rgb(0x19, 0x67, 0xD2)

    /** Material Blue 800 (`#174EA6`) — the header row's own bottom border. */
    val HEADER_BORDER_RGB = rgb(0x17, 0x4E, 0xA6)

    /** `--lt-hover` (Material Blue 50 `#E8F0FE`) — a section heading's own fill; contrast ~14:1
     *  against [TEXT_PRIMARY_RGB]. */
    val SECTION_FILL_RGB = rgb(0xE8, 0xF0, 0xFE)

    private fun rgb(r: Int, g: Int, b: Int): ByteArray = byteArrayOf(r.toByte(), g.toByte(), b.toByte())

    /**
     * WCAG 2.x relative-luminance contrast ratio between two sRGB colours: 1.0 (no contrast) to
     * 21.0 (black on white). The design spec's own binding rule is that every fill/font pair the
     * palette above declares clears the AA text floor, 4.5:1 — ContrastRatioTest is the actual
     * guard; this is a pure, table-driven function on purpose, so that test can exercise it
     * directly rather than reading pixels back out of a rendered style.
     */
    fun contrastRatio(rgbA: ByteArray, rgbB: ByteArray): Double {
        val luminanceA = relativeLuminance(rgbA)
        val luminanceB = relativeLuminance(rgbB)
        val lighter = maxOf(luminanceA, luminanceB)
        val darker = minOf(luminanceA, luminanceB)
        return (lighter + WCAG_CONTRAST_OFFSET) / (darker + WCAG_CONTRAST_OFFSET)
    }

    private fun relativeLuminance(rgb: ByteArray): Double {
        val (r, g, b) = rgb.map { channelLuminance(it) }
        return RED_LUMINANCE_COEFFICIENT * r + GREEN_LUMINANCE_COEFFICIENT * g + BLUE_LUMINANCE_COEFFICIENT * b
    }

    private fun channelLuminance(channelByte: Byte): Double {
        val channel = (channelByte.toInt() and BYTE_MASK) / MAX_CHANNEL_VALUE
        return if (channel <= WCAG_LINEAR_THRESHOLD) {
            channel / WCAG_LINEAR_DIVISOR
        } else {
            ((channel + WCAG_GAMMA_OFFSET) / WCAG_GAMMA_DIVISOR).pow(WCAG_GAMMA_EXPONENT)
        }
    }

    // WCAG 2.x relative-luminance formula constants (https://www.w3.org/TR/WCAG21/#dfn-relative-luminance).
    private const val RED_LUMINANCE_COEFFICIENT = 0.2126
    private const val GREEN_LUMINANCE_COEFFICIENT = 0.7152
    private const val BLUE_LUMINANCE_COEFFICIENT = 0.0722
    private const val WCAG_CONTRAST_OFFSET = 0.05
    private const val WCAG_LINEAR_THRESHOLD = 0.03928
    private const val WCAG_LINEAR_DIVISOR = 12.92
    private const val WCAG_GAMMA_OFFSET = 0.055
    private const val WCAG_GAMMA_DIVISOR = 1.055
    private const val WCAG_GAMMA_EXPONENT = 2.4
    private const val MAX_CHANNEL_VALUE = 255.0
    private const val BYTE_MASK = 0xFF
}
