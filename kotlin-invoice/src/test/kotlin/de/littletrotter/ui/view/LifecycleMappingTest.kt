package de.littletrotter.ui.view

import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.LifecycleMeaning
import de.littletrotter.ui.component.LifecycleSubject
import de.littletrotter.ui.component.lifecycleGlyph
import de.littletrotter.ui.component.lifecycleHeroClass
import de.littletrotter.ui.component.lifecycleTone
import de.littletrotter.ui.view.invoice.cockpitHeroStatusClass
import de.littletrotter.ui.view.invoice.invoiceLifecycleMeaning
import de.littletrotter.ui.view.invoice.invoiceStatusGlyphIcon
import de.littletrotter.ui.view.invoice.invoiceStatusTone
import de.littletrotter.ui.view.quote.QuoteCockpitHero
import de.littletrotter.ui.view.quote.QuoteLabels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Offer and invoice statuses share one tone/symbol mapping; the same meaning must look the same. */
class LifecycleMappingTest {

    private val invoiceMeanings = InvoiceStatus.entries.associateWith(::invoiceLifecycleMeaning)
    private val quoteMeanings = QuoteStatus.known.associateWith(QuoteLabels::lifecycleMeaning)

    @Test
    fun `every status of both kinds has exactly one meaning and the pairing is pinned`() {
        assertEquals(
            mapOf(
                InvoiceStatus.DRAFT to LifecycleMeaning.DRAFT,
                InvoiceStatus.IN_REVIEW to LifecycleMeaning.IN_REVIEW,
                InvoiceStatus.ISSUED to LifecycleMeaning.SENT,
                InvoiceStatus.VOIDED to LifecycleMeaning.VOIDED,
                InvoiceStatus.CANCELLED to LifecycleMeaning.ENDED_NEGATIVE,
            ),
            invoiceMeanings,
        )
        assertEquals(
            mapOf(
                QuoteStatus.DRAFT to LifecycleMeaning.DRAFT,
                QuoteStatus.REVIEW to LifecycleMeaning.IN_REVIEW,
                QuoteStatus.SENT to LifecycleMeaning.SENT,
                QuoteStatus.CANCELLED to LifecycleMeaning.VOIDED,
                QuoteStatus.REJECTED to LifecycleMeaning.ENDED_NEGATIVE,
                QuoteStatus.ACCEPTED to LifecycleMeaning.ENDED_POSITIVE,
            ),
            quoteMeanings,
        )
        assertEquals(LifecycleMeaning.entries.toSet(), invoiceMeanings.values.toSet() + quoteMeanings.values.toSet())
    }

    @Test
    fun `the same meaning gives the same tone and the same hero edge for invoice and offer`() {
        val invoiceTones = invoiceMeanings.entries.associate { (status, meaning) -> meaning to invoiceStatusTone(status) }
        val quoteTones = quoteMeanings.entries.associate { (status, meaning) -> meaning to QuoteLabels.statusTone(status) }
        invoiceTones.forEach { (meaning, tone) ->
            quoteTones[meaning]?.let { assertEquals(tone, it, "$meaning") }
        }
        invoiceMeanings.forEach { (status, meaning) -> assertEquals(lifecycleHeroClass(meaning), cockpitHeroStatusClass(status)) }
        quoteMeanings.forEach { (status, meaning) -> assertEquals(lifecycleHeroClass(meaning), QuoteCockpitHero.heroClass(status)) }
    }

    @Test
    fun `tone and symbol per meaning`() {
        val expected = mapOf(
            LifecycleMeaning.DRAFT to (ChipTone.NEUTRAL to VaadinIcon.EDIT),
            LifecycleMeaning.IN_REVIEW to (ChipTone.REVIEW to VaadinIcon.EYE),
            LifecycleMeaning.SENT to (ChipTone.ACCENT to VaadinIcon.PAPERPLANE),
            LifecycleMeaning.VOIDED to (ChipTone.ERROR to VaadinIcon.BAN),
            LifecycleMeaning.ENDED_NEGATIVE to (ChipTone.CANCELLATION to null),
            LifecycleMeaning.ENDED_POSITIVE to (ChipTone.OK to VaadinIcon.CHECK),
        )
        assertEquals(LifecycleMeaning.entries.toSet(), expected.keys)
        expected.forEach { (meaning, pair) ->
            assertEquals(pair.first, lifecycleTone(meaning), "$meaning")
            pair.second?.let { icon -> LifecycleSubject.entries.forEach { assertEquals(icon, lifecycleGlyph(meaning, it), "$meaning") } }
        }
        assertEquals(VaadinIcon.ROTATE_LEFT, lifecycleGlyph(LifecycleMeaning.ENDED_NEGATIVE, LifecycleSubject.INVOICE))
        assertEquals(VaadinIcon.CLOSE_SMALL, lifecycleGlyph(LifecycleMeaning.ENDED_NEGATIVE, LifecycleSubject.OFFER))
    }

    @Test
    fun `the symbols of one document kind never repeat`() {
        assertEquals(InvoiceStatus.entries.size, InvoiceStatus.entries.map(::invoiceStatusGlyphIcon).toSet().size)
        assertEquals(QuoteStatus.known.size, QuoteStatus.known.map(QuoteLabels::statusGlyph).toSet().size)
    }

    @Test
    fun `the word cancelled in the offer and voided in the invoice read the same`() {
        assertEquals(invoiceStatusTone(InvoiceStatus.VOIDED), QuoteLabels.statusTone(QuoteStatus.CANCELLED))
        assertEquals(invoiceStatusGlyphIcon(InvoiceStatus.VOIDED), QuoteLabels.statusGlyph(QuoteStatus.CANCELLED))
        assertTrue(QuoteLabels.statusTone(QuoteStatus.ACCEPTED) == ChipTone.OK)
    }
}
