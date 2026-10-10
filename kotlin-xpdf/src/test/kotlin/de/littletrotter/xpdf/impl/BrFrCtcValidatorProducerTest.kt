package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.BrFrCtcValidator
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * XPR-3 — [BrFrCtcValidatorProducer] is a trivial wrapper (no config, no eager init — see its
 * own class doc), but still a real object graph edge worth pinning: `produce()` must return a
 * genuinely usable [BrFrCtcValidator] instance, not throw or return null.
 * `BrFrCtcValidatorTest` (kotlin-facturx) already covers the real rule-set behavior against real
 * CII fixtures; this test only proves the producer's own wiring.
 */
class BrFrCtcValidatorProducerTest {

    @Test
    fun `produce returns a usable BrFrCtcValidator`() {
        val validator: BrFrCtcValidator = BrFrCtcValidatorProducer().produce()
        assertNotNull(validator)
    }
}
