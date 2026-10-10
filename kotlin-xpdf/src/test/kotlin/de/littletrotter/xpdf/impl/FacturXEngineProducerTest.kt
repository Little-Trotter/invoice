package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.FacturXEngineOutcome
import de.littletrotter.facturx.HttpFacturXEngineClient
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * XPR-3 — [FacturXEngineProducer] is a plain `@ApplicationScoped`/`@Produces` wrapper (no
 * `@QuarkusTest` needed, exactly like [S3DocumentStore]'s own test doesn't need one to exercise
 * its constructor/field wiring): this test proves the producer's config-reading constructor
 * actually reaches the produced [HttpFacturXEngineClient] instance, not just that it compiles.
 */
class FacturXEngineProducerTest {

    private fun invoice(): InvoiceDto = InvoiceDto(
        invoiceNumber = InvoiceNumberDto("INV-PRODUCER-1"),
        dates = InvoiceDatesDto(invoiceDate = "2026-02-10"),
        senderProfile = PartyDto(name = "Sender GmbH", street = "Hafenstr. 12", postalCode = "28195", city = "Bremen", country = "DE"),
        recipient = PartyDto(name = "Acme AG", street = "Musterstr. 42", postalCode = "10115", city = "Berlin", country = "DE"),
        items = listOf(
            InvoiceItemDto(
                positionId = "1",
                description = "Beratung",
                taxCategory = "S",
                taxRatePercent = BigDecimal("19"),
                netAmount = Money(BigDecimal("100.00")),
                vatAmount = Money(BigDecimal("19.00")),
            ),
        ),
        totals = InvoiceTotalsDto(
            netAmount = Money(BigDecimal("100.00")),
            vatAmount = Money(BigDecimal("19.00")),
            grossAmount = Money(BigDecimal("119.00")),
        ),
    )

    @Test
    fun `produce wires the config values through to the real HttpFacturXEngineClient, not the defaults`() {
        val producer = FacturXEngineProducer(
            baseUrl = "http://localhost:1", // nothing listens on port 1
            connectTimeoutMs = 200,
            requestTimeoutMs = 200,
            maxUploadMb = 0, // any non-empty payload exceeds 0 MB — proves this exact value, not
            // the class's own Kotlin default (25), reached the produced instance
        )

        val engine = producer.produce()
        assertTrue(engine is HttpFacturXEngineClient)

        val outcome = engine.convert(basePdf = byteArrayOf(1, 2, 3), invoice = invoice())
        assertTrue(outcome is FacturXEngineOutcome.Failure)
        val finding = (outcome as FacturXEngineOutcome.Failure).findings.single()
        assertTrue(
            finding.ruleId == "FACTURX-PAYLOAD-TOO-LARGE",
            "expected the 0 MB limit configured above to reject any payload before any HTTP call, got: $finding",
        )
    }

    @Test
    fun `health reports false for a base-url nothing listens on`() {
        val producer = FacturXEngineProducer(
            baseUrl = "http://localhost:1",
            connectTimeoutMs = 200,
            requestTimeoutMs = 200,
            maxUploadMb = 25,
        )
        assertFalse(producer.produce().health())
    }
}
