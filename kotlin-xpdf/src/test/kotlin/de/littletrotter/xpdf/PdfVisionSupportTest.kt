package de.littletrotter.xpdf

import de.littletrotter.shared.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Branch coverage tests for PdfVisionSupport.
 * Tests prompt template generation with various invoice and layout report conditions.
 */
class PdfVisionSupportTest {

    private val testInvoice = InvoiceDto(
        invoiceNumber = InvoiceNumberDto("TEST-2026-001"),
        senderProfile = PartyDto(
            name = "Test Company",
            street = "Test Street 1",
            postalCode = "12345",
            city = "Test City",
            country = "DE"
        ),
        recipient = PartyDto(
            name = "Test Customer",
            street = "Customer Street 1",
            postalCode = "54321",
            city = "Customer City",
            country = "DE"
        ),
        items = listOf(
            InvoiceItemDto(
                positionId = "1",
                description = "Test Item",
                quantity = BigDecimal("1"),
                unitLabel = "Stück",
                unitPriceNet = Money(BigDecimal("100.00")),
                netAmount = Money(BigDecimal("100.00")),
                vatAmount = Money(BigDecimal("19.00")),
                taxRatePercent = BigDecimal("19")
            )
        ),
        dates = InvoiceDatesDto(
            invoiceDate = "2026-01-01",
            dueDate = "2026-02-01"
        ),
        totals = InvoiceTotalsDto(
            netAmount = Money(BigDecimal("100.00")),
            vatAmount = Money(BigDecimal("19.00")),
            grossAmount = Money(BigDecimal("119.00"))
        )
    )

    @Test
    fun `buildInspectionPrompt replaces invoice number placeholder`() {
        val prompt = PdfVisionSupport.buildInspectionPrompt(testInvoice, layoutReport = "Test Layout")

        assertTrue(prompt.contains("TEST-2026-001"), "Should replace invoice number placeholder")
        assertFalse(prompt.contains("{{invoiceNumber}}"), "Should not contain unreplaced placeholder")
    }

    @Test
    fun `buildInspectionPrompt replaces layout report placeholder with provided report`() {
        val layoutReport = "Seiten: 1, Format: 210×297mm"
        val prompt = PdfVisionSupport.buildInspectionPrompt(testInvoice, layoutReport = layoutReport)

        assertTrue(prompt.contains(layoutReport), "Should replace layout report placeholder with provided report")
        assertFalse(prompt.contains("{{layoutReport}}"), "Should not contain unreplaced placeholder")
    }

    @Test
    fun `buildInspectionPrompt uses fallback message when layout report is blank`() {
        val prompt = PdfVisionSupport.buildInspectionPrompt(testInvoice, layoutReport = "")

        assertTrue(
            prompt.contains("(keine deterministische Messung übergeben)"),
            "Should use fallback message for blank layout report"
        )
    }

    @Test
    fun `buildInspectionPrompt uses fallback message when layout report is whitespace`() {
        val prompt = PdfVisionSupport.buildInspectionPrompt(testInvoice, layoutReport = "   ")

        assertTrue(
            prompt.contains("(keine deterministische Messung übergeben)"),
            "Should use fallback message for whitespace-only layout report"
        )
    }

    @Test
    fun `buildInspectionPrompt with default layout report parameter`() {
        val prompt = PdfVisionSupport.buildInspectionPrompt(testInvoice)

        assertTrue(
            prompt.contains("(keine deterministische Messung übergeben)"),
            "Should use fallback message when layout report parameter is omitted"
        )
    }

    @Test
    fun `buildInspectionPrompt includes template content`() {
        val prompt = PdfVisionSupport.buildInspectionPrompt(testInvoice, layoutReport = "Test Report")

        assertFalse(prompt.isBlank(), "Prompt should not be empty")
        assertTrue(prompt.length > 50, "Prompt should have meaningful content from template")
    }

    @Test
    fun `happy path - both placeholders are replaced with the invoice number and the layout report`() {
        val prompt = PdfVisionSupport.buildInspectionPrompt(testInvoice, layoutReport = "Seiten: 1, Format: 210x297mm")

        assertTrue(prompt.contains("TEST-2026-001"))
        assertTrue(prompt.contains("Seiten: 1, Format: 210x297mm"))
        assertFalse(prompt.contains("{{invoiceNumber}}"))
        assertFalse(prompt.contains("{{layoutReport}}"))
    }

    @Test
    fun `boundary - an invoice number that itself contains a placeholder token is not recursively substituted`() {
        // Business risk: a naive sequential replace could re-scan its own output. String#replace
        // on a literal CharSequence does not re-scan the replacement text, so this must stay safe.
        val invoiceWithPlaceholderLikeNumber = testInvoice.copy(
            invoiceNumber = InvoiceNumberDto("{{layoutReport}}-2026"),
        )

        val prompt = PdfVisionSupport.buildInspectionPrompt(invoiceWithPlaceholderLikeNumber, layoutReport = "Messbericht X")

        assertTrue(prompt.contains("{{layoutReport}}-2026"), "The literal invoice number text must survive unmangled")
        assertTrue(prompt.contains("Messbericht X"), "The real layout report placeholder must still resolve")
    }

    @Test
    fun `boundary - repeated calls return an independently built string each time, not a shared mutable reference`() {
        val first = PdfVisionSupport.buildInspectionPrompt(testInvoice, layoutReport = "Report A")
        val second = PdfVisionSupport.buildInspectionPrompt(testInvoice, layoutReport = "Report B")

        assertTrue(first.contains("Report A"))
        assertTrue(second.contains("Report B"))
        assertFalse(second.contains("Report A"), "The cached template must not retain a prior call's substitution")
    }

    @Test
    fun `boundary - an empty invoice number still produces a well-formed prompt without the placeholder token`() {
        val invoiceWithEmptyNumber = testInvoice.copy(invoiceNumber = InvoiceNumberDto(""))

        val prompt = PdfVisionSupport.buildInspectionPrompt(invoiceWithEmptyNumber, layoutReport = "Report")

        assertFalse(prompt.contains("{{invoiceNumber}}"))
        assertFalse(prompt.isBlank())
    }

    @Test
    fun `boundary - a missing prompt template resource fails loudly`() {
        val error = assertThrows(IllegalStateException::class.java) { PdfVisionSupport.loadTemplate("/prompts/does-not-exist.md") }
        assertTrue(error.message!!.contains("/prompts/does-not-exist.md"))
    }
}
