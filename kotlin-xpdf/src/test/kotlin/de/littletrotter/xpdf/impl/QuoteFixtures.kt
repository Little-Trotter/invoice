package de.littletrotter.xpdf.impl

import de.littletrotter.shared.documentjob.QuoteDocumentItem
import de.littletrotter.shared.documentjob.QuoteDocumentMark
import de.littletrotter.shared.documentjob.QuoteDocumentParty
import de.littletrotter.shared.documentjob.QuoteDocumentPayload
import de.littletrotter.shared.documentjob.QuoteDocumentReference
import de.littletrotter.shared.documentjob.QuoteDocumentTotals
import de.littletrotter.shared.documentjob.QuoteDocumentVatGroup

/** Synthetic offers for the renderer, pipeline and layout tests; no real customer or seller data. */
object QuoteFixtures {

    val seller = QuoteDocumentParty(
        name = "Musterwerk Handwerk GmbH",
        street = "Beispielweg 12",
        postalCode = "54290",
        city = "Trier",
        countryCode = "DE",
        vatId = "DE123456789",
        taxNumber = "42/123/45678",
        phone = "0651 123456",
        email = "angebot@musterwerk.example",
        website = "https://www.musterwerk.example/",
        iban = "DE89370400440532013000",
        bic = "COBADEFFXXX",
        bankName = "Musterbank Trier",
        commercialRegisterNumber = "HRB 12345",
        registerCourt = "Amtsgericht Trier",
        managingDirector = "Erika Mustermann",
    )

    val customer = QuoteDocumentParty(
        name = "Beispiel Bau AG",
        street = "Hauptstraße 1",
        postalCode = "50667",
        city = "Köln",
        countryCode = "DE",
        customerNumber = "K-10042",
    )

    private val items = listOf(
        QuoteDocumentItem(
            position = 1, description = "Fliesenarbeiten Bad", quantity = "12.5", unit = "SQUARE_METER",
            unitPrice = "48.00", lineAmount = "600.00", vatRate = "19.00",
            articleNumber = "LV-4711", customerReference = "KD-991", customerArticleName = "Bodenfliese 30x60 anthrazit",
        ),
        QuoteDocumentItem(
            position = 2, description = "Anfahrt und Material", quantity = "1", unit = "FLAT_RATE",
            unitPrice = "85.50", lineAmount = "85.50", vatRate = "19.00",
        ),
        QuoteDocumentItem(
            position = 3, description = "Stundenlohn Geselle", quantity = "8", unit = "HOUR",
            unitPrice = "52.00", lineAmount = "416.00", vatRate = "19.00",
        ),
    )

    private val totals = QuoteDocumentTotals(
        net = "1101.50", vat = "209.29", gross = "1310.79",
        groups = listOf(QuoteDocumentVatGroup("19.00", "1101.50", "209.29")),
    )

    fun payload(
        mark: QuoteDocumentMark = QuoteDocumentMark.DISPATCH,
        language: String = "de",
    ) = QuoteDocumentPayload(
        quoteId = "8f6c0f2e-0000-4000-8000-000000000001",
        quoteNumber = "AN-2026-00007",
        quoteDate = "2026-10-05",
        validUntil = "2026-11-04",
        mark = mark,
        language = language,
        grossPrices = false,
        seller = seller,
        customer = customer,
        items = items,
        totals = totals,
        subject = "Sanierung Badezimmer",
        introText = "vielen Dank für Ihre Anfrage. Gerne bieten wir Ihnen **wie folgt** an:",
        closingText = "Wir freuen uns auf Ihren Auftrag.\n\n*Mit freundlichen Grüßen*",
        noteText = "Die Ausführung erfolgt nach Terminabsprache.",
        references = listOf(QuoteDocumentReference(10, "BAU-2026-17")),
    )
}
