package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.testsupport.OutputIntentTestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class OutputIntentVerifierTest {
    private val verifier = OutputIntentTestKeys.verifier
    private val today = LocalDate.of(2026, 6, 15)
    private val subject = "DE123456789"

    @Test
    fun `no token never covers`() {
        assertFalse(verifier.covers(null, subject, today))
        assertFalse(verifier.covers("", subject, today))
    }

    @Test
    fun `a malformed token never covers`() {
        assertFalse(verifier.covers("oi2.abc.-.def", subject, today))
        assertFalse(verifier.covers("garbage", subject, today))
    }

    @Test
    fun `a valid unlimited grant covers its seller`() {
        assertTrue(verifier.covers(OutputIntentTestKeys.grantFor(subject), subject, today))
    }

    @Test
    fun `a grant is bound to its seller`() {
        assertFalse(verifier.covers(OutputIntentTestKeys.grantFor("DE999999999"), subject, today))
    }

    @Test
    fun `a missing subject is never covered`() {
        assertFalse(verifier.covers(OutputIntentTestKeys.grantFor(subject), null, today))
    }

    @Test
    fun `an expired grant does not cover but the last day still does`() {
        val token = OutputIntentTestKeys.grantFor(subject, today)
        assertTrue(verifier.covers(token, subject, today))
        assertFalse(verifier.covers(token, subject, today.plusDays(1)))
    }

    @Test
    fun `a tampered expiry invalidates the signature`() {
        val token = OutputIntentTestKeys.grantFor(subject, today)
        val tampered = token.replace(today.toString(), today.plusYears(10).toString())
        assertFalse(verifier.covers(tampered, subject, today))
    }

    @Test
    fun `a grant signed by another key does not cover`() {
        val foreign = OutputIntentTestKeys.grantFor(subject, signWith = OutputIntentTestKeys.newKeyPair())
        assertFalse(verifier.covers(foreign, subject, today))
    }

    @Test
    fun `the embedded product-owner key rejects a grant signed by any other key`() {
        val production = OutputIntentVerifier()
        assertFalse(production.covers(OutputIntentTestKeys.grantFor(subject), subject, today))
    }

    @Test
    fun `a grant signed with an earlier key still holds once a newer key has been added`() {
        val earlier = OutputIntentTestKeys.newKeyPair()
        val verifier = OutputIntentVerifier(listOf(earlier.public, OutputIntentTestKeys.newKeyPair().public))
        assertTrue(verifier.covers(OutputIntentTestKeys.grantFor(subject, signWith = earlier), subject, today))
    }

    @Test
    fun `the shipped key list is non-empty and every entry is a valid Ed25519 key`() {
        assertTrue(OutputIntentVerifier.PUBLIC_KEYS_BASE64.isNotEmpty())
        assertFalse(OutputIntentVerifier().covers(null, subject, today))
    }

    @Test
    fun `among several whitespace-separated tokens the seller's own one covers it`() {
        val tokens = "${OutputIntentTestKeys.grantFor("DE999999999")}\n  garbage ${OutputIntentTestKeys.grantFor(subject)} "
        assertTrue(verifier.covers(tokens, subject, today))
    }

    @Test
    fun `several tokens of other sellers never cover`() {
        val tokens = "${OutputIntentTestKeys.grantFor("DE999999999")} ${OutputIntentTestKeys.grantFor("DE888888888")}"
        assertFalse(verifier.covers(tokens, subject, today))
    }

    @Test
    fun `subject hash is url-safe unpadded SHA-256`() {
        assertEquals(43, verifier.subjectHash(subject).length)
    }
}
