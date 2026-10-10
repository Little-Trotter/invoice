package de.littletrotter.infrastructure.quote

import de.littletrotter.shared.Money
import de.littletrotter.shared.quote.QuoteB2bValueView
import de.littletrotter.shared.quote.QuoteDetailView
import de.littletrotter.shared.quote.QuoteInvoiceOrderView
import de.littletrotter.shared.quote.QuoteItemView
import de.littletrotter.shared.quote.QuotePartyView
import de.littletrotter.shared.quote.QuoteSummaryView
import de.littletrotter.shared.quote.QuoteTotalsView
import de.littletrotter.shared.quote.QuoteVatGroupView
import java.math.BigDecimal
import java.time.LocalDate

/** Synthetic wire views shared by the offer adapter and translator tests. */
object QuoteFixtures {

    val DAY: LocalDate = LocalDate.of(2026, 10, 1)

    fun summary(status: String = "DRAFT") =
        QuoteSummaryView("q-1", "Muster GmbH", status, DAY, DAY.plusDays(30), Money.of("100.5"), "A-1", "Betreff")

    fun detail(
        status: String = "ACCEPTED",
        priceDisplay: String = "GROSS",
        vatCategory: String = "STANDARD",
        full: Boolean = true,
        invoiceOrder: QuoteInvoiceOrderView? = null,
    ) = QuoteDetailView(
        id = "q-1",
        version = 3,
        status = status,
        party = QuotePartyView("c-1", "Muster GmbH", "DE", "B2B", "de", "K-1", "Weg 1", "12345", "Ort", "DE123", "a@b.de"),
        priceDisplay = priceDisplay,
        quoteDate = DAY,
        validUntil = DAY.plusDays(30),
        totals = QuoteTotalsView(
            Money.of("100"),
            Money.of("19"),
            Money.of("119"),
            listOf(QuoteVatGroupView(vatCategory, BigDecimal("19"), Money.of("100"), Money.of("19"))),
        ),
        number = "A-1".takeIf { full },
        subject = "S".takeIf { full },
        introText = "I".takeIf { full },
        closingText = "C".takeIf { full },
        noteText = "N".takeIf { full },
        showsGrossPrices = full,
        acceptedOn = DAY.takeIf { full },
        customerOrderReference = "PO-1".takeIf { full },
        rejectionReason = "R".takeIf { full },
        invoiceNumber = "RE-1".takeIf { full },
        items = listOf(
            QuoteItemView("i-1", 1, "Beratung", BigDecimal("2"), "SERVICE", "HOUR", Money.of("50"), vatCategory, BigDecimal("19"), Money.of("100"), "a-1", "AN-1", "REF"),
        ),
        b2bValues = listOf(QuoteB2bValueView(10, "x")),
        invoiceOrder = invoiceOrder,
    )
}
