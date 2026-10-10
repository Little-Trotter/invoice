package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.testsupport.OutputIntentTestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Base64

class OutputIntentGrantTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val hash = b64.encodeToString(ByteArray(32) { 1 })
    private val sig = b64.encodeToString(ByteArray(64) { 2 })

    @Test
    fun `a well-formed unlimited token parses`() {
        val grant = checkNotNull(OutputIntentGrant.parse("oi1.$hash.-.$sig"))
        assertEquals(hash, grant.subjectHash)
        assertNull(grant.notAfter)
        assertEquals(64, grant.signature.size)
        assertEquals("oi1.$hash.-", grant.signedPayload)
    }

    @Test
    fun `a dated token parses and surrounding whitespace is trimmed`() {
        val grant = checkNotNull(OutputIntentGrant.parse("  oi1.$hash.2026-12-31.$sig\n"))
        assertEquals(LocalDate.of(2026, 12, 31), grant.notAfter)
        assertEquals("oi1.$hash.2026-12-31", grant.signedPayload)
    }

    @Test
    fun `absent and blank tokens are rejected`() {
        assertNull(OutputIntentGrant.parse(null))
        assertNull(OutputIntentGrant.parse(""))
        assertNull(OutputIntentGrant.parse("   "))
    }

    @Test
    fun `malformed tokens are rejected without throwing`() {
        val short = b64.encodeToString(ByteArray(31))
        val shortSig = b64.encodeToString(ByteArray(63))
        val bad = listOf(
            "oi2.$hash.-.$sig",
            "oi1.$hash.$sig",
            "oi1.$hash.-.$sig.extra",
            "oi1.!!!.-.$sig",
            "oi1.$hash.-.!!!",
            "oi1.$short.-.$sig",
            "oi1.$hash.-.$shortSig",
            "oi1.$hash.2026-13-01.$sig",
            "oi1.$hash.2026-1-1.$sig",
            "oi1.$hash..$sig",
        )
        bad.forEach { assertNull(OutputIntentGrant.parse(it), it) }
    }

    @Test
    fun `a token minted by the test keys round-trips`() {
        val token = OutputIntentTestKeys.grantFor("DE123", LocalDate.of(2030, 1, 1))
        assertEquals(LocalDate.of(2030, 1, 1), checkNotNull(OutputIntentGrant.parse(token)).notAfter)
    }
}
