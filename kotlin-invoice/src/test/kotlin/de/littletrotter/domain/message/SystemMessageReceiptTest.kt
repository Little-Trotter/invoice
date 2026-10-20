package de.littletrotter.domain.message

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** Entity-level validation for [SystemMessageReceipt]. */
class SystemMessageReceiptTest {

    private val readAt: OffsetDateTime = OffsetDateTime.parse("2026-08-25T10:20:00Z")

    @Test
    fun `a well-formed receipt is valid`() {
        assertDoesNotThrow { SystemMessageReceipt.of(1L, "system", readAt) }
    }

    @Test
    fun `a blank user id is rejected`() {
        assertThrows(InvalidParameterException::class.java) { SystemMessageReceipt.of(1L, " ", readAt) }
    }

    @Test
    fun `a non-positive message id is rejected`() {
        assertThrows(InvalidParameterException::class.java) { SystemMessageReceipt.of(0L, "system", readAt) }
    }
}
