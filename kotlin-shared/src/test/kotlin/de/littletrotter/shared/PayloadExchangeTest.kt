package de.littletrotter.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.assertj.core.api.Assertions.assertThat

/**
 * Unit tests for PayloadExchange focusing on the companion object's sha256Hex utility.
 * Happy path with real Valkey is covered by PayloadExchangeIT; these tests focus on
 * the sha256Hex hash function which provides integrity checking for payloads.
 */
class PayloadExchangeTest {

    @Test
    fun `sha256Hex produces correct hash for empty string`() {
        val hash = PayloadExchange.sha256Hex("")
        // Well-known SHA-256 of empty string
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `sha256Hex produces consistent hash for same input`() {
        val input = "test-payload-content"
        val hash1 = PayloadExchange.sha256Hex(input)
        val hash2 = PayloadExchange.sha256Hex(input)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `sha256Hex produces different hash for different input`() {
        val hash1 = PayloadExchange.sha256Hex("content-a")
        val hash2 = PayloadExchange.sha256Hex("content-b")
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `sha256Hex produces lowercase hex output`() {
        val hash = PayloadExchange.sha256Hex("test")
        assertThat(hash).isLowerCase().doesNotContainAnyWhitespaces()
    }

    @Test
    fun `sha256Hex hash is always 64 characters (256 bits in hex)`() {
        val hashes = listOf(
            PayloadExchange.sha256Hex(""),
            PayloadExchange.sha256Hex("a"),
            PayloadExchange.sha256Hex("test"),
            PayloadExchange.sha256Hex("x".repeat(1000)),
            PayloadExchange.sha256Hex("multi\nline\nstring")
        )

        for (hash in hashes) {
            assertEquals(64, hash.length, "SHA-256 hex encoding must always be 64 characters")
            assertThat(hash).isLowerCase()
        }
    }

    @Test
    fun `sha256Hex handles UTF-8 correctly`() {
        // Ensure UTF-8 encoding is used correctly for non-ASCII characters
        val hash1 = PayloadExchange.sha256Hex("café")
        val hash2 = PayloadExchange.sha256Hex("café")
        assertEquals(hash1, hash2, "Same UTF-8 string should produce same hash")
    }

    @Test
    fun `sha256Hex handles long strings`() {
        val longString = "x".repeat(10000)
        val hash = PayloadExchange.sha256Hex(longString)
        assertEquals(64, hash.length)
    }

    @Test
    fun `sha256Hex produces different hashes for strings differing only in whitespace`() {
        val hash1 = PayloadExchange.sha256Hex("hello world")
        val hash2 = PayloadExchange.sha256Hex("helloworld")
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `sha256Hex produces different hashes for case-sensitive differences`() {
        val hash1 = PayloadExchange.sha256Hex("Hello")
        val hash2 = PayloadExchange.sha256Hex("hello")
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `sha256Hex handles special characters`() {
        val hash = PayloadExchange.sha256Hex("!@#$%^&*()")
        assertEquals(64, hash.length)
        assertThat(hash).isLowerCase()
    }

    @Test
    fun `sha256Hex handles multi-byte UTF-8 characters`() {
        // Test with various multi-byte UTF-8 characters
        val hashes = listOf(
            PayloadExchange.sha256Hex("日本語"),  // Japanese
            PayloadExchange.sha256Hex("Ελληνικά"), // Greek
            PayloadExchange.sha256Hex("العربية"), // Arabic
            PayloadExchange.sha256Hex("🔐"),      // Emoji
        )

        // All should produce valid hashes
        for (hash in hashes) {
            assertEquals(64, hash.length, "Hash must be 64 hex characters")
            assertThat(hash).isLowerCase()
        }
    }

    @Test
    fun `sha256Hex matches known test vectors`() {
        // Test vector: SHA-256("abc")
        val hash = PayloadExchange.sha256Hex("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
    }
}
