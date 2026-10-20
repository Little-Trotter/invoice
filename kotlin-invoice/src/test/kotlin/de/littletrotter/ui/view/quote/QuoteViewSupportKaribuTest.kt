package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.quote.QuoteModuleDisabledException
import de.littletrotter.application.quote.QuoteNoNumberRangeException
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.QuoteUnknownValueException
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteByNumberQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDetailQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuotePriceDisplay
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat
import de.littletrotter.application.security.AppPermission
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.notification.UiNotifications
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/** Access rules, labels, guarded calls and route entry shared by the offer views. */
class QuoteViewSupportKaribuTest {

    private val detailQuery: QuoteDetailQueryPort = mock()
    private val byNumberQuery: QuoteByNumberQueryPort = mock()
    private val legacyId = "0b1f6c2e-3a4d-4e5f-8a9b-0c1d2e3f4a5b"

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun labels() = QuoteLabels(testHost())

    @Test
    fun `each access question follows exactly its own permission`() {
        val none = QuoteAccess(QuoteAvailability(true), rolesWith())
        assertFalse(none.mayEdit())
        assertFalse(none.maySend())
        assertFalse(none.mayMaintainLibrary())
        assertFalse(none.mayArchiveLibrary())
        assertFalse(none.mayRequestInvoice())

        assertTrue(QuoteAccess(QuoteAvailability(true), rolesWith(AppPermission.QUOTE_LIBRARY_EDIT)).mayMaintainLibrary())
        assertFalse(QuoteAccess(QuoteAvailability(true), rolesWith(AppPermission.QUOTE_LIBRARY_EDIT)).mayArchiveLibrary())
        assertTrue(QuoteAccess(QuoteAvailability(true), rolesWith(AppPermission.QUOTE_LIBRARY_ARCHIVE)).mayArchiveLibrary())
        assertTrue(QuoteAccess(QuoteAvailability(true), rolesWith(AppPermission.QUOTE_SEND)).maySend())
    }

    @Test
    fun `ordering an invoice needs the offer right and the invoice right together`() {
        assertFalse(QuoteAccess(QuoteAvailability(true), rolesWith(AppPermission.QUOTE_EDIT)).mayRequestInvoice())
        assertFalse(QuoteAccess(QuoteAvailability(true), rolesWith(AppPermission.INVOICE_FULL_EDIT)).mayRequestInvoice())
        assertTrue(
            QuoteAccess(QuoteAvailability(true), rolesWith(AppPermission.QUOTE_EDIT, AppPermission.INVOICE_FULL_EDIT)).mayRequestInvoice(),
        )
    }

    @Test
    fun `admit forwards to the invoice list when the component is off or the permission is missing`() {
        val granted = mock<BeforeEnterEvent>()
        assertTrue(QuoteAccess(QuoteAvailability(true), rolesWith(AppPermission.QUOTE_VIEW)).admit(granted, AppPermission.QUOTE_VIEW))
        verify(granted, never()).forwardTo(any<Class<com.vaadin.flow.component.Component>>())

        val off = mock<BeforeEnterEvent>()
        assertFalse(QuoteAccess(QuoteAvailability(false), rolesWith(AppPermission.QUOTE_VIEW)).admit(off, AppPermission.QUOTE_VIEW))
        verify(off).forwardTo(de.littletrotter.ui.view.invoice.InvoiceListView::class.java)

        val denied = mock<BeforeEnterEvent>()
        assertFalse(QuoteAccess(QuoteAvailability(true), rolesWith()).admit(denied, AppPermission.QUOTE_VIEW))
        verify(denied).forwardTo(de.littletrotter.ui.view.invoice.InvoiceListView::class.java)
    }

    @Test
    fun `the routes build their paths from the number`() {
        assertEquals("quotes/AN-1", QuoteRoutes.detail("AN-1"))
        assertEquals("quotes/AN-1/edit", QuoteRoutes.edit("AN-1"))
        assertEquals(listOf(QuoteRoutes.TAB_ITEMS), QuoteRoutes.itemsTab().parameters[QuoteRoutes.TAB_PARAM])
    }

    @Test
    fun `labels read the German texts of the bundle`() {
        val labels = labels()
        assertEquals("In Prüfung", labels.status(QuoteStatus.REVIEW))
        assertEquals("Nettopreise", labels.priceDisplay(QuotePriceDisplay.NET))
        assertEquals("Versand", labels.logKind(QuoteLogKind.SEND))
        assertEquals("Intern", labels.attachmentKind(QuoteAttachmentKind.INTERNAL))
        assertEquals("Bereit", labels.documentStatus(QuoteDocumentStatus.COMPLETED))
        assertEquals("UBL 2.1", labels.xmlFormat(QuoteXmlFormat.UBL))
    }

    @Test
    fun `a sent offer past its validity turns warning-toned with an explanation`() {
        val labels = labels()
        val yesterday = TODAY.minusDays(1)
        val glyph = labels.listStatusGlyph(QuoteStatus.SENT, yesterday, TODAY)
        val chip = labels.statusChip(QuoteStatus.SENT, yesterday, TODAY)

        assertEquals("Gesendet – Gültigkeit abgelaufen", glyph.element.getAttribute("aria-label"))
        assertEquals("Gesendet – Gültigkeit abgelaufen", glyph.element.getAttribute("title"))
        assertTrue(glyph.hasClassName("status-glyph--warn"))
        assertEquals("Gesendet – Gültigkeit abgelaufen", chip.element.getAttribute("aria-label"))
        assertTrue(chip.hasClassName("status-chip--warn"))
        assertEquals("Versendet", chip.text)
    }

    @Test
    fun `only a sent offer can expire`() {
        val yesterday = TODAY.minusDays(1)
        assertFalse(QuoteLabels.isValidityExpired(QuoteStatus.SENT, TODAY, TODAY))
        assertFalse(QuoteLabels.isValidityExpired(QuoteStatus.ACCEPTED, yesterday, TODAY))
        assertFalse(QuoteLabels.isValidityExpired(QuoteStatus.DRAFT, yesterday, TODAY))
        assertTrue(QuoteLabels.isValidityExpired(QuoteStatus.SENT, yesterday, TODAY))
    }

    @Test
    fun `status chip, list glyph and quick filter share label and tone of the status`() {
        val labels = labels()
        assertEquals("Versendet", labels.statusChip(QuoteStatus.SENT, TODAY.plusDays(1), TODAY).text)
        assertEquals("Versendet", labels.listStatusGlyph(QuoteStatus.SENT, TODAY, TODAY).element.getAttribute("aria-label"))
        val spec = labels.quickFilterSpec(QuoteStatus.ACCEPTED)
        assertEquals("Angenommen", spec.label)
        assertEquals(ChipTone.OK, spec.tone)
        assertEquals(VaadinIcon.CHECK, spec.glyph)
        assertEquals("Angenommen: 3 Angebote", spec.countAriaLabel(3))
    }

    @Test
    fun `every status and document status has its own glyph`() {
        assertEquals(QuoteStatus.entries.size, QuoteStatus.entries.map { QuoteLabels.statusGlyph(it) }.toSet().size)
        assertEquals(
            listOf(VaadinIcon.HOURGLASS, VaadinIcon.CHECK, VaadinIcon.EXCLAMATION_CIRCLE),
            QuoteDocumentStatus.entries.map { QuoteLabels.documentStatusGlyph(it) },
        )
    }

    @Test
    fun `money, date and quantities use German notation without insignificant zeros`() {
        val labels = labels()
        assertEquals("2,5", labels.number(BigDecimal("2.50")))
        assertEquals("19", labels.number(BigDecimal("19.000")))
        assertEquals("1.234,5678", labels.number(BigDecimal("1234.56781")))
        assertEquals("01.10.2026", labels.date(LocalDate.of(2026, 10, 1)))
        assertTrue(labels.money(Money.of("250.00")).contains("250,00"))
    }

    @Test
    fun `a business term reads plain wording plus its code, an unknown one only the code`() {
        val labels = labels()
        assertEquals("Kontierungsreferenz des Empfängers (BT-19)", labels.btLabel(19))
        assertTrue(labels.btHelp(19)!!.startsWith("Kostenstelle oder Buchungskonto"))
        assertEquals("BT-9999", labels.btLabel(9999))
        assertNull(labels.btHelp(9999))
    }

    @Test
    fun `guarded passes a result through and a missing number range yields null with a notification`() {
        assertEquals("ok", guarded { "ok" })
        assertEquals(Unit, guarded { })

        assertNull(guarded<String> { throw QuoteNoNumberRangeException() })
        assertTrue(notificationTexts().any { it.contains(UiNotifications.tr(QuoteNoNumberRangeException.MESSAGE_KEY)) })
    }

    @Test
    fun `a disabled component is reported with its own text`() {
        assertNull(guarded<String> { throw QuoteModuleDisabledException() })
        assertTrue(notificationTexts().any { it.contains(UiNotifications.tr(QuoteModuleDisabledException.MESSAGE_KEY)) })
    }

    @Test
    fun `an unknown value from the component is reported in the bundle text with field and value`() {
        assertNull(guarded<String> { throw QuoteUnknownValueException("document mark", "ARCHIVED") })
        val expected = UiNotifications.tr(QuoteUnknownValueException.MESSAGE_KEY, "document mark", "ARCHIVED")
        assertTrue(expected.contains("document mark") && expected.contains("ARCHIVED"))
        assertTrue(notificationTexts().any { it.contains(expected) })
    }

    @Test
    fun `an unreachable component is reported with the message of the failure`() {
        assertNull(guarded<String> { throw QuoteServiceException("Angebotsdienst nicht erreichbar") })
        assertTrue(notificationTexts().any { it.contains("Angebotsdienst nicht erreichbar") })
    }

    @Test
    fun `an offer without a number is a defect`() {
        val e = assertThrows(IllegalStateException::class.java) { quoteDetail(QuoteStatus.DRAFT).copy(number = null).requireNumber() }
        assertTrue(e.message!!.contains("has no number"))
        assertThrows(IllegalStateException::class.java) { quoteSummary().copy(number = null).requireNumber() }
        assertEquals("AN-2026-0001", quoteSummary().requireNumber())
    }

    @Test
    fun `entry without a number parameter goes back to the list`() {
        val event = quoteEvent(null)
        var loaded = false

        QuoteEntry(detailQuery, byNumberQuery).enter(event, QuoteRoutes::detail) { loaded = true; true }

        verify(event).forwardTo(QuoteListView::class.java)
        assertFalse(loaded)
    }

    @Test
    fun `entry by number loads the offer and keeps the user on the view`() {
        val detail = quoteDetail(QuoteStatus.DRAFT)
        whenever(byNumberQuery.find("AN-2026-0001")).thenReturn(detail)
        val event = quoteEvent("AN-2026-0001")
        var shown: String? = null

        QuoteEntry(detailQuery, byNumberQuery).enter(event, QuoteRoutes::detail) { shown = it.id; true }

        assertEquals(detail.id, shown)
        verify(event, never()).forwardTo(any<Class<com.vaadin.flow.component.Component>>())
    }

    @Test
    fun `entry goes back to the list when the load callback declines or the number is unknown`() {
        whenever(byNumberQuery.find("AN-2026-0001")).thenReturn(quoteDetail(QuoteStatus.DRAFT))
        val declined = quoteEvent("AN-2026-0001")
        QuoteEntry(detailQuery, byNumberQuery).enter(declined, QuoteRoutes::detail) { false }
        verify(declined).forwardTo(QuoteListView::class.java)

        whenever(byNumberQuery.find("AN-0000-0000")).thenThrow(QuoteNotFoundException("AN-0000-0000"))
        val unknown = quoteEvent("AN-0000-0000")
        var loaded = false
        QuoteEntry(detailQuery, byNumberQuery).enter(unknown, QuoteRoutes::detail) { loaded = true; true }
        verify(unknown).forwardTo(QuoteListView::class.java)
        assertFalse(loaded)
    }

    @Test
    fun `a bookmark with the technical id is forwarded to the number route with its query`() {
        whenever(detailQuery.find(legacyId)).thenReturn(quoteDetail(QuoteStatus.DRAFT))
        val event = quoteEvent(legacyId, "quotes/$legacyId")

        QuoteEntry(detailQuery, byNumberQuery).enter(event, QuoteRoutes::detail) { true }

        verify(event).forwardTo(eq("quotes/AN-2026-0001"), any<QueryParameters>())
        verify(byNumberQuery, never()).find(any())
    }

    @Test
    fun `a technical id nobody knows goes back to the list`() {
        whenever(detailQuery.find(legacyId)).thenThrow(QuoteServiceException("unbekannt"))
        val event = quoteEvent(legacyId)

        QuoteEntry(detailQuery, byNumberQuery).enter(event, QuoteRoutes::detail) { true }

        verify(event).forwardTo(QuoteListView::class.java)
    }

    private companion object {
        val TODAY: java.time.LocalDate = java.time.LocalDate.of(2026, 10, 15)
    }
}
