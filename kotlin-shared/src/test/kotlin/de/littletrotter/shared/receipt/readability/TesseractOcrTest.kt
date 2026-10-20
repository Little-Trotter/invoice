package de.littletrotter.shared.receipt.readability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TesseractOcrTest {

    @Test
    fun `identical text has accuracy one regardless of whitespace`() {
        assertEquals(1.0, TesseractOcr.accuracy("Summe 12,50\nEUR", "Summe  12,50 EUR"), 0.0)
    }

    @Test
    fun `one wrong character in ten lowers accuracy by a tenth`() {
        assertEquals(0.9, TesseractOcr.accuracy("0123456789", "0123456780"), 1e-9)
    }

    @Test
    fun `empty truth is trivially matched`() {
        assertEquals(1.0, TesseractOcr.accuracy("", "anything"), 0.0)
    }

    @Test
    fun `accuracy never drops below zero`() {
        assertEquals(0.0, TesseractOcr.accuracy("ab", "zzzzzzzz"), 0.0)
    }
}
