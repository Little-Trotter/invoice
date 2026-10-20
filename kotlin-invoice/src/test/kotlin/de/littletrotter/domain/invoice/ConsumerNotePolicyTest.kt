package de.littletrotter.domain.invoice

import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.wording.WordingProfile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsumerNotePolicyTest {
    @Test
    fun `applies for craft-trade profile, private customer and enabled switch`() {
        assertTrue(ConsumerNotePolicy.applies(WordingProfile.HANDWERK, CustomerType.CONSUMER, true))
    }

    @Test
    fun `G1 other profiles never apply`() {
        WordingProfile.entries.filter { it != WordingProfile.HANDWERK }.forEach {
            assertFalse(ConsumerNotePolicy.applies(it, CustomerType.CONSUMER, true), it.name)
        }
    }

    @Test
    fun `G2 business and unknown recipient type do not apply`() {
        assertFalse(ConsumerNotePolicy.applies(WordingProfile.HANDWERK, CustomerType.BUSINESS, true))
        assertFalse(ConsumerNotePolicy.applies(WordingProfile.HANDWERK, null, true))
    }

    @Test
    fun `G3 switched off does not apply`() {
        assertFalse(ConsumerNotePolicy.applies(WordingProfile.HANDWERK, CustomerType.CONSUMER, false))
    }
}
