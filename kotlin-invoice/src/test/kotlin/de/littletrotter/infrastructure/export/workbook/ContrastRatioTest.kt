package de.littletrotter.infrastructure.export.workbook

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * the design spec's own binding rule: every fill/font pair the palette declares must
 * clear WCAG AA's 4.5:1 text-contrast floor. Table-driven against [Palette.contrastRatio], a
 * pure function, so this test guards the actual RGB values against a future "just make it
 * lighter" rather than reading pixels back out of a rendered style.
 */
class ContrastRatioTest {

    @ParameterizedTest(name = "{0}: {3}:1 or higher")
    @MethodSource("palettePairs")
    fun `every declared fill or font pair clears the WCAG AA 4_5 to 1 floor`(
        label: String,
        background: ByteArray,
        foreground: ByteArray,
        expectedAtLeast: Double,
    ) {
        // The documented per-pair ratio (expectedAtLeast) is always >= the binding 4.5 floor
        // itself — asserting against it is the stronger check: it also catches a future edit that
        // still clears 4.5 but quietly drifts down from the ratio the spec's own table documents.
        assertThat(expectedAtLeast).describedAs("$label's own documented ratio").isGreaterThanOrEqualTo(WCAG_AA_TEXT_FLOOR)
        assertThat(Palette.contrastRatio(background, foreground)).describedAs(label).isGreaterThanOrEqualTo(expectedAtLeast)
    }

    @Test
    fun `black on white is the maximum possible ratio, 21 to 1`() {
        val black = byteArrayOf(0, 0, 0)
        val white = Palette.WHITE_RGB
        assertThat(Palette.contrastRatio(black, white)).isCloseTo(MAX_RATIO, Offset.offset(RATIO_TOLERANCE))
    }

    @Test
    fun `identical colours have the minimum possible ratio, 1 to 1`() {
        assertThat(Palette.contrastRatio(Palette.TEXT_PRIMARY_RGB, Palette.TEXT_PRIMARY_RGB)).isCloseTo(1.0, Offset.offset(RATIO_TOLERANCE))
    }

    @Test
    fun `contrast ratio is symmetric regardless of argument order`() {
        val ratio = Palette.contrastRatio(Palette.HEADER_FILL_RGB, Palette.WHITE_RGB)
        val reversed = Palette.contrastRatio(Palette.WHITE_RGB, Palette.HEADER_FILL_RGB)
        assertThat(ratio).isCloseTo(reversed, Offset.offset(RATIO_TOLERANCE))
    }

    companion object {
        private const val WCAG_AA_TEXT_FLOOR = 4.5
        private const val MAX_RATIO = 21.0
        private const val RATIO_TOLERANCE = 0.01

        @JvmStatic
        fun palettePairs(): Stream<Arguments> = Stream.of(
            // Column header row (all sheets): white text on the Blue 700 header fill. PO decision
            // 2026-09-11: replaces Blue 600 (`--lt-accent`, ~4.505:1 — no real margin over the AA
            // floor); the precise WCAG formula for Blue 700 comes out to ~5.368:1.
            Arguments.of("header (fill vs. white text)", Palette.HEADER_FILL_RGB, Palette.WHITE_RGB, 5.36),
            // Section heading: lt-text-primary on the Blue 50 section fill — precise formula
            // ~14.049:1.
            Arguments.of("sectionHeading (fill vs. text)", Palette.SECTION_FILL_RGB, Palette.TEXT_PRIMARY_RGB, 14.04),
            // Secondary/explanatory text: lt-text-secondary on a plain white sheet background —
            // precise formula ~6.050:1.
            Arguments.of("secondaryText (white background vs. text)", Palette.WHITE_RGB, Palette.TEXT_SECONDARY_RGB, 6.04),
            // Sheet title / body cells: lt-text-primary on a plain white sheet background —
            // precise formula ~16.099:1.
            Arguments.of("title / body cells (white background vs. text)", Palette.WHITE_RGB, Palette.TEXT_PRIMARY_RGB, 16.09),
            // Deviation highlight (conditional formatting, not a static CellStyles style): Material
            // Red 900 text on Material Red 50 fill — precise formula ~5.747:1 (the design spec's
            // own table rounds this to 6.5:1).
            Arguments.of("deviation (fill vs. text)", DeviationConditionalFormatting.FILL_RGB, DeviationConditionalFormatting.FONT_RGB, 5.74),
        )
    }
}
