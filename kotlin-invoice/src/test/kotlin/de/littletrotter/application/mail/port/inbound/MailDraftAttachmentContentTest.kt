package de.littletrotter.application.mail.port.inbound

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** Content equality compares the bytes, not the array identity. */
class MailDraftAttachmentContentTest {

    private fun content(bytes: ByteArray = byteArrayOf(1, 2), name: String = "a.pdf", type: String = "application/pdf") =
        MailDraftAttachmentContent(name, type, bytes)

    @Test
    fun `equal fields with different array instances are equal and hash alike`() {
        val first = content()
        val second = content(byteArrayOf(1, 2))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `a differing byte, name or content type breaks equality`() {
        val base = content()

        assertNotEquals(base, content(bytes = byteArrayOf(1, 3)))
        assertNotEquals(base, content(name = "b.pdf"))
        assertNotEquals(base, content(type = "text/plain"))
    }

    @Test
    fun `an instance equals itself and never another type`() {
        val base = content()

        assertEquals(base, base)
        assertFalse(base.equals("a.pdf"))
    }
}
