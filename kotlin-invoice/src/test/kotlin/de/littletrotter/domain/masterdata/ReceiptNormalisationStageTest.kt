package de.littletrotter.domain.masterdata

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

/** Pins the three stages a receipt PDF reducer tries in order — see [ReceiptNormalisationStage]'s
 *  own KDoc for why the order matters (each stage changes more of the document than the last). */
class ReceiptNormalisationStageTest {

    @Test
    fun `stages are ordered from least to most destructive`() {
        assertArrayEquals(
            arrayOf(
                ReceiptNormalisationStage.NONE,
                ReceiptNormalisationStage.IMAGES_DOWNSAMPLED,
                ReceiptNormalisationStage.RASTERISED,
            ),
            ReceiptNormalisationStage.entries.toTypedArray(),
        )
    }
}
